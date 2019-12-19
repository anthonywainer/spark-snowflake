/*
 * Copyright 2015-2018 Snowflake Computing
 * Copyright 2015 TouchType Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.snowflake.spark.snowflake

import java.io.{ByteArrayInputStream, InputStream}
import java.util.Base64

import net.snowflake.client.core.QueryResultFormat
import net.snowflake.client.jdbc.internal.apache.arrow.memory.RootAllocator
import net.snowflake.client.jdbc.internal.apache.arrow.vector.VectorSchemaRoot
import net.snowflake.client.jdbc.internal.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.sql._
import org.slf4j.{Logger, LoggerFactory}
import net.snowflake.spark.snowflake.Parameters.MergedParameters
import net.snowflake.spark.snowflake.io.SupportedFormat.SupportedFormat
import net.snowflake.spark.snowflake.io.SupportedFormat
import net.snowflake.spark.snowflake.DefaultJDBCWrapper.DataBaseOperations

import scala.reflect.ClassTag
import net.snowflake.client.jdbc.{
  SnowflakeResultSet,
  SnowflakeResultSetSerializable,
  SnowflakeResultSetSerializableV1
}
import net.snowflake.spark.snowflake.io.SnowflakeResultSetRDD

import scala.collection.JavaConversions

/** Data Source API implementation for Amazon Snowflake database tables */
private[snowflake] case class SnowflakeRelation(
  jdbcWrapper: JDBCWrapper,
  params: MergedParameters,
  userSchema: Option[StructType]
)(@transient val sqlContext: SQLContext)
    extends BaseRelation
    with PrunedFilteredScan
    with InsertableRelation {

  import SnowflakeRelation._

  override def toString: String = {
    "SnowflakeRelation"
  }

  val log: Logger = LoggerFactory.getLogger(getClass) // Create a temporary stage

  override lazy val schema: StructType = {
    userSchema.getOrElse {
      val tableNameOrSubquery =
        params.query.map(q => s"($q)").orElse(params.table.map(_.toString)).get
      val conn = jdbcWrapper.getConnector(params)
      try {
        jdbcWrapper.resolveTable(conn, tableNameOrSubquery, params)
      } finally {
        conn.close()
      }
    }
  }

  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    val saveMode = if (overwrite) {
      SaveMode.Overwrite
    } else {
      SaveMode.Append
    }
    val writer = new SnowflakeWriter(jdbcWrapper)
    writer.save(sqlContext, data, saveMode, params)
  }

  override def unhandledFilters(filters: Array[Filter]): Array[Filter] = {
    filters.filterNot(
      filter =>
        FilterPushdown
          .buildFilterStatement(
            schema,
            filter,
            params.keepOriginalColumnNameCase
          )
          .isDefined
    )
  }

  // Build the RDD from a query string, generated by SnowflakeStrategy.
  // Type can be InternalRow to comply with SparkPlan's doExecute().
  def buildScanFromSQL[T: ClassTag](statement: SnowflakeSQLStatement,
                                    schema: Option[StructType]): RDD[T] = {
    log.debug(Utils.sanitizeQueryText(statement.statementString))

    val conn = jdbcWrapper.getConnector(params)
    val resultSchema = schema.getOrElse(
      try {
        conn.tableSchema(statement, params)
      } finally {
        conn.close()
      })
    getRDD[T](statement, resultSchema)
  }

  // Build RDD result from PrunedFilteredScan interface.
  // Maintain this here for backwards compatibility and for
  // when extra pushdowns are disabled.
  override def buildScan(requiredColumns: Array[String],
                         filters: Array[Filter]): RDD[Row] = {
    if (requiredColumns.isEmpty) {
      // In the special case where no columns were requested, issue a `count(*)` against Snowflake
      // rather than unloading data.
      val whereClause = FilterPushdown.buildWhereStatement(schema, filters)
      val tableNameOrSubquery: SnowflakeSQLStatement =
        params.query
          .map(ConstantString("(") + _ + ")")
          .getOrElse(params.table.get.toStatement !)
      val countQuery =
        ConstantString("SELECT count(*) FROM") + tableNameOrSubquery + whereClause
      log.debug(Utils.sanitizeQueryText(countQuery.statementString))
      val conn = jdbcWrapper.getConnector(params)
      try {
        val results = countQuery.execute(params.bindVariableEnabled)(conn)
        if (results.next()) {
          val numRows = results.getLong(1)
          val parallelism =
            sqlContext.getConf("spark.sql.shuffle.partitions", "200").toInt
          val emptyRow = Row.empty
          sqlContext.sparkContext
            .parallelize(1L to numRows, parallelism)
            .map(_ => emptyRow)
        } else {
          throw new IllegalStateException("Could not read count from Snowflake")
        }
      } finally {
        conn.close()
      }
    } else {
      // Unload data from Snowflake into a temporary directory in S3:
      val prunedSchema = pruneSchema(schema, requiredColumns)

      getRDD[Row](standardStatement(requiredColumns, filters), prunedSchema)
    }
  }

  // Get an RDD from a statement. Provide result schema because
  // when a custom SQL statement is used, this means that we cannot know the results
  // without first executing it.
  private def getRDD[T: ClassTag](statement: SnowflakeSQLStatement,
                                  resultSchema: StructType): RDD[T] = {
    if (params.useCopyUnload) {
      getSnowflakeRDD(statement, resultSchema)
    } else {
      getSnowflakeResultSetRDD(statement, resultSchema)
    }
  }

  // Get an RDD with COPY Unload
  private def getSnowflakeRDD[T: ClassTag](statement: SnowflakeSQLStatement,
                                           resultSchema: StructType): RDD[T] = {
    val format: SupportedFormat =
      if (Utils.containVariant(resultSchema)) SupportedFormat.JSON
      else SupportedFormat.CSV

    val rdd: RDD[String] = io.readRDD(sqlContext, params, statement, format)

    format match {
      case SupportedFormat.CSV =>
        rdd.mapPartitions(CSVConverter.convert[T](_, resultSchema))
      case SupportedFormat.JSON =>
        rdd.mapPartitions(JsonConverter.convert[T](_, resultSchema))
    }
  }

  // Get an RDD with SELECT query directly
  private def getSnowflakeResultSetRDD[T: ClassTag](
    statement: SnowflakeSQLStatement,
    resultSchema: StructType
  ): RDD[T] = {
    val conn = DefaultJDBCWrapper.getConnector(params)
    Utils.genPrologueSql(params).execute(bindVariableEnabled = false)(conn)
    Utils.executePreActions(DefaultJDBCWrapper, conn, params, params.table)
    Utils.setLastSelect(statement.toString)

    val resultSet = statement.execute(bindVariableEnabled = false)(conn)

    Utils.executePostActions(DefaultJDBCWrapper, conn, params, params.table)
    SnowflakeTelemetry.send(conn.getTelemetry)

    // JavaConversions is deprecated from Scala 2.12, JavaConverters is the
    // new API. But we need to support multiple Scala versions like 2.10, 2.11 and 2.12.
    // So JavaConversions.asScalaBuffer is used so far.
    val resultSetSerializables: Array[SnowflakeResultSetSerializable] =
      JavaConversions
        .asScalaBuffer(
          resultSet
            .asInstanceOf[SnowflakeResultSet]
            .getResultSetSerializables(params.expectedPartitionSize)
        )
        .toArray
    printStatForSnowflakeResultSetRDD(resultSetSerializables)

    new SnowflakeResultSetRDD[T](
      resultSchema,
      sqlContext.sparkContext,
      resultSetSerializables,
      params.proxyInfo
    )
  }

  // Print result set statistic information
  private def printStatForSnowflakeResultSetRDD(
    resultSetSerializables: Array[SnowflakeResultSetSerializable]
  ): Unit = {
    var totalRowCount: Long = 0
    var totalCompressedSize: Long = 0
    var totalUnCompressedSize: Long = 0
    var totalURLCount: Long = 0

    resultSetSerializables.foreach { resultSet =>
      {
        val resultSetSerializable =
          resultSet.asInstanceOf[SnowflakeResultSetSerializableV1]

        // Get row count and data size for first data chunk
        val (firstRowCount, firstChunkSize) =
          getFirstChunkSize(resultSetSerializable)
        totalRowCount += firstRowCount
        totalCompressedSize += firstChunkSize
        totalUnCompressedSize += firstChunkSize
        if (firstRowCount > 0) {
          log.info(s"""${SnowflakeResultSetRDD.MASTER_LOG_PREFIX}: First result chunk:
               | rowCount=$firstRowCount chunkSize=$firstChunkSize
               |""".stripMargin.filter(_ >= ' '))
        }

        // Get row count for all pre-signed URLs from metadata
        var index = 0
        val fileChunks = resultSet
          .asInstanceOf[SnowflakeResultSetSerializableV1]
          .getChunkFileMetadatas
        while (index < fileChunks.size) {
          totalRowCount += fileChunks.get(index).getRowCount
          totalCompressedSize += fileChunks.get(index).getCompressedByteSize
          totalUnCompressedSize += fileChunks.get(index).getUncompressedByteSize
          totalURLCount += 1
          index += 1
        }
      }
    }

    val partitionCount = resultSetSerializables.length
    val totalCompressedSizeMB = totalCompressedSize.toDouble / 1024.0 / 1024.0
    val totalUnCompressedSizeMB = totalUnCompressedSize.toDouble / 1024.0 / 1024.0
    log.info(s"""${SnowflakeResultSetRDD.MASTER_LOG_PREFIX}: Total statistics:
         | partitionCount=$partitionCount rowCount=$totalRowCount
         | presignedURLCount=$totalURLCount compressSizeMB=$totalCompressedSizeMB
         | unCompressSizeMB=$totalUnCompressedSizeMB
         |""".stripMargin.filter(_ >= ' '))

    val aveCount = totalRowCount / partitionCount
    val aveUrlCount = totalURLCount / partitionCount
    val aveCompressSizeMB = totalCompressedSizeMB / partitionCount
    val aveUnCompressSizeMB = totalUnCompressedSizeMB / partitionCount
    log.info(
      s"""${SnowflakeResultSetRDD.MASTER_LOG_PREFIX}: Average statistics per partition:
         | rowCount=$aveCount URLCount=$aveUrlCount
         | compressSizeMB=$aveCompressSizeMB unCompressSizeMB=$aveUnCompressSizeMB
         |""".stripMargin.filter(_ >= ' ')
    )
  }

  // Get data size and row count for first data chunk
  private def getFirstChunkSize(
    resultSetSerializable: SnowflakeResultSetSerializableV1
  ): (Long, Long) = {
    val firstChunkData = resultSetSerializable.getFirstChunkStringData

    if (firstChunkData == null || firstChunkData.length < 1) {
      // There is no first chunk
      (0, 0)
    } else if (resultSetSerializable.getQueryResultFormat.equals(
                 QueryResultFormat.ARROW
               )) {
      // The first chunk is ARROW format
      var rowCount: Long = 0
      var inputStream: InputStream = null
      var reader: ArrowStreamReader = null
      var root: VectorSchemaRoot = null
      try {
        val bytes = Base64.getDecoder.decode(firstChunkData)
        inputStream = new ByteArrayInputStream(bytes)

        val rootAllocator = new RootAllocator(Long.MaxValue)

        reader = new ArrowStreamReader(inputStream, rootAllocator)
        root = reader.getVectorSchemaRoot
        root.getFieldVectors
        while (reader.loadNextBatch()) {
          rowCount += root.getRowCount
          root.clear()
        }
      } catch {
        case e: Throwable =>
          val errorMessage = e.getMessage
          log.error(s"""getFirstChunkSize Fail to parse first Arrow chunk:
                       | error message is [ $errorMessage ]
                       |""".stripMargin.filter(_ >= ' '))
          throw e
      } finally {
        if (root != null) {
          root.clear()
        }
        if (reader != null) {
          reader.close()
        }
        if (inputStream != null) {
          inputStream.close()
        }
      }

      (rowCount, firstChunkData.length)
    } else {
      // The first chunk is JSON
      (resultSetSerializable.getFirstChunkRowCount, firstChunkData.length)
    }
  }

  // Build a query out of required columns and filters. (Used by buildScan)
  private def standardStatement(
    requiredColumns: Array[String],
    filters: Array[Filter]
  ): SnowflakeSQLStatement = {

    assert(!requiredColumns.isEmpty)
    // Always quote column names, and uppercase-cast them to make them equivalent to being unquoted
    // (unless already quoted):
    val columnList = requiredColumns
      .map(
        col =>
          if (params.keepOriginalColumnNameCase) Utils.quotedNameIgnoreCase(col)
          else Utils.ensureQuoted(col)
      )
      .mkString(", ")
    val whereClause = FilterPushdown.buildWhereStatement(
      schema,
      filters,
      params.keepOriginalColumnNameCase
    )
    val tableNameOrSubquery: StatementElement =
      params.table
        .map(_.toStatement)
        .getOrElse(ConstantString("(" + params.query.get + ")"))
    ConstantString("SELECT") + columnList + "FROM" + tableNameOrSubquery + whereClause
  }
}

private[snowflake] object SnowflakeRelation {

  private def pruneSchema(schema: StructType,
                          columns: Array[String]): StructType = {
    val fieldMap = Map(schema.fields.map(x => x.name -> x): _*)
    new StructType(columns.map(name => fieldMap(name)))
  }

}
