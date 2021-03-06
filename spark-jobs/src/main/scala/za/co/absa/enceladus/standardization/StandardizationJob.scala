/*
 * Copyright 2018 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.enceladus.standardization

import java.io.{PrintWriter, StringWriter}
import java.text.MessageFormat
import java.time.Instant
import java.util.UUID

import com.typesafe.config.ConfigFactory
import org.apache.spark.SPARK_VERSION
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.sql.{Column, DataFrame, DataFrameReader, SparkSession}
import org.slf4j.LoggerFactory
import za.co.absa.atum.AtumImplicits
import za.co.absa.atum.core.Atum
import za.co.absa.enceladus.common.RecordIdGeneration.{IdType, _}
import za.co.absa.enceladus.common._
import za.co.absa.enceladus.common.plugin.PostProcessingService
import za.co.absa.enceladus.common.plugin.menas.{MenasPlugin, MenasRunUrl}
import za.co.absa.enceladus.common.version.SparkVersionGuard
import za.co.absa.enceladus.dao.MenasDAO
import za.co.absa.enceladus.dao.auth.MenasCredentials
import za.co.absa.enceladus.dao.rest.{MenasConnectionStringParser, RestDaoFactory}
import za.co.absa.enceladus.model.Dataset
import za.co.absa.enceladus.plugins.builtin.utils.SecureKafka
import za.co.absa.enceladus.standardization.interpreter.StandardizationInterpreter
import za.co.absa.enceladus.standardization.interpreter.stages.PlainSchemaGenerator
import za.co.absa.enceladus.utils.fs.FileSystemVersionUtils
import za.co.absa.enceladus.utils.general.{ConfigReader, ProjectMetadataTools}
import za.co.absa.enceladus.utils.performance.{PerformanceMeasurer, PerformanceMetricTools}
import za.co.absa.enceladus.utils.schema.{MetadataKeys, SchemaUtils, SparkUtils}
import za.co.absa.enceladus.utils.time.TimeZoneNormalizer
import za.co.absa.enceladus.utils.udf.UDFLibrary
import za.co.absa.enceladus.utils.unicode.ParameterConversion._
import za.co.absa.enceladus.utils.validation.ValidationException

import scala.collection.immutable.HashMap
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object StandardizationJob {
  TimeZoneNormalizer.normalizeJVMTimeZone()

  private val log = LoggerFactory.getLogger(this.getClass)
  private val conf = ConfigFactory.load()
  private val confReader: ConfigReader = new ConfigReader(conf)
  private val menasBaseUrls = MenasConnectionStringParser.parse(conf.getString("menas.rest.uri"))
  private final val SparkCSVReaderMaxColumnsDefault: Int = 20480

  def main(args: Array[String]) {
    // This should be the first thing the app does to make secure Kafka work with our CA.
    // After Spring activates JavaX, it will be too late.
    SecureKafka.setSecureKafkaProperties(conf)

    SparkVersionGuard.fromDefaultSparkCompatibilitySettings.ensureSparkVersionCompatibility(SPARK_VERSION)

    confReader.logEffectiveConfigProps(Constants.ConfigKeysToRedact)

    implicit val cmd: StdCmdConfig = StdCmdConfig.getCmdLineArguments(args)
    implicit val spark: SparkSession = obtainSparkSession()
    implicit val fsUtils: FileSystemVersionUtils = new FileSystemVersionUtils(spark.sparkContext.hadoopConfiguration)
    implicit val udfLib: UDFLibrary = new UDFLibrary
    val menasCredentials = cmd.menasCredentialsFactory.getInstance()
    implicit val dao: MenasDAO = RestDaoFactory.getInstance(menasCredentials, menasBaseUrls)

    dao.authenticate()

    val dataset = dao.getDataset(cmd.datasetName, cmd.datasetVersion)
    val schema: StructType = dao.getSchema(dataset.schemaName, dataset.schemaVersion)
    val reportVersion = getReportVersion(cmd, dataset)
    val pathCfg = getPathCfg(cmd, dataset, reportVersion)
    val recordIdGenerationStrategy = getRecordIdGenerationStrategyFromConfig(conf)

    log.info(s"input path: ${pathCfg.inputPath}")
    log.info(s"output path: ${pathCfg.outputPath}")
    // die if the output path exists
    if (fsUtils.hdfsExists(pathCfg.outputPath)) {
      throw new IllegalStateException(
        s"Path ${pathCfg.outputPath} already exists. Increment the run version, or delete ${pathCfg.outputPath}"
      )
    }

    // Enable Spline
    import za.co.absa.spline.core.SparkLineageInitializer._
    spark.enableLineageTracking()

    // Enable Control Framework
    enableControlFramework(pathCfg, cmd, reportVersion)

    // init performance measurer
    val performance = new PerformanceMeasurer(spark.sparkContext.appName)
    val dfAll: DataFrame = prepareDataFrame(schema, cmd, pathCfg.inputPath, dataset)

    try {
      executeStandardization(performance, dfAll, schema, cmd, menasCredentials, pathCfg, recordIdGenerationStrategy)
      cmd.performanceMetricsFile.foreach(this.writePerformanceMetrics(performance, _))
      log.info("Standardization finished successfully")

      // read written data from parquet directly
      val standardizedDf = spark.read.parquet(pathCfg.outputPath)
      val postProcessingService = getPostProcessingService(cmd, pathCfg, dataset, MenasPlugin.runNumber, Atum.getControlMeasure.runUniqueId)
      postProcessingService.onSaveOutput(standardizedDf) // all enabled postProcessors will be run with the std df
    } finally {
      postStandardizationSteps(cmd)
    }
  }

  private def getPostProcessingService(cmd: StdCmdConfig, pathCfg: PathCfg, dataset: Dataset,
                                        runNumber: Option[Int], uniqueRunId: Option[String]
                                       )(implicit fsUtils: FileSystemVersionUtils): PostProcessingService = {
    val runId = MenasPlugin.runNumber

    if (runId.isEmpty) {
      log.warn("No run number found, the Run URL cannot be properly reported!")
    }

    // reporting the UI url(s) - if more than one, its comma-separated
    val runUrl: Option[String] = runId.map { runNumber =>
      menasBaseUrls.map { menasBaseUrl =>
        MenasRunUrl.getMenasUiRunUrl(menasBaseUrl, dataset.name, dataset.version, runNumber)
      }.mkString(",")
    }

    PostProcessingService.forStandardization(conf, dataset.name, dataset.version, cmd.reportDate,
      getReportVersion(cmd, dataset), pathCfg.outputPath, Atum.getControlMeasure.metadata.sourceApplication, runUrl,
      runId, uniqueRunId, Instant.now)
  }

  private def getReportVersion(cmd: StdCmdConfig, dataset: Dataset)(implicit fsUtils: FileSystemVersionUtils): Int = {
    cmd.reportVersion match {
      case Some(version) => version
      case None =>
        val newVersion = fsUtils.getLatestVersion(dataset.hdfsPublishPath, cmd.reportDate) + 1
        log.warn(s"Report version not provided, inferred report version: $newVersion")
        log.warn("This is an EXPERIMENTAL feature.")
        log.warn(" -> It can lead to issues when running multiple jobs on a dataset concurrently.")
        log.warn(" -> It may not work as desired when there are gaps in the versions of the data being landed.")
        newVersion
    }
  }

  private def getPathCfg(cmd: StdCmdConfig, dataset: Dataset, reportVersion: Int): PathCfg = {
    val dateTokens = cmd.reportDate.split("-")
    PathCfg(
      inputPath = buildRawPath(cmd, dataset, dateTokens, reportVersion),
      outputPath = MessageFormat.format(conf.getString("standardized.hdfs.path"),
        cmd.datasetName,
        cmd.datasetVersion.toString,
        cmd.reportDate,
        reportVersion.toString)
    )
  }

  private def obtainSparkSession()(implicit cmd: StdCmdConfig): SparkSession = {
    val enceladusVersion = ProjectMetadataTools.getEnceladusVersion
    log.info(s"Enceladus version $enceladusVersion")
    val reportVersion = cmd.reportVersion.map(_.toString).getOrElse("")
    val spark = SparkSession.builder()
      .appName(s"Standardisation $enceladusVersion ${cmd.datasetName} ${cmd.datasetVersion} ${cmd.reportDate} $reportVersion")
      .getOrCreate()
    TimeZoneNormalizer.normalizeSessionTimeZone(spark)
    spark
  }

  /**
    * Returns a Spark reader with all format-specific options applied.
    * Options are provided by command line parameters.
    *
    * @param cmd      Command line parameters containing format-specific options
    * @param dataset  A dataset definition
    * @param numberOfColumns (Optional) number of columns, enables reading CSV files with the number of columns
    *                        larger than Spark default
    * @return The updated dataframe reader
    */
  def getFormatSpecificReader(cmd: StdCmdConfig, dataset: Dataset, numberOfColumns: Int = 0)
                             (implicit spark: SparkSession, dao: MenasDAO): DataFrameReader = {
    val dfReader = spark.read.format(cmd.rawFormat)
    // applying format specific options
    val options = getCobolOptions(cmd, dataset) ++
      getGenericOptions(cmd) ++
      getXmlOptions(cmd) ++
      getCsvOptions(cmd, numberOfColumns) ++
      getFixedWidthOptions(cmd)

    // Applying all the options
    options.foldLeft(dfReader) { (df, optionPair) =>
      optionPair match {
        case (key, Some(value)) =>
          value match {
            // Handle all .option() overloads
            case StringParameter(s) => df.option(key, s)
            case BooleanParameter(b) => df.option(key, b)
            case LongParameter(l) => df.option(key, l)
            case DoubleParameter(d) => df.option(key, d)
          }
        case (_, None)          => df
      }
    }
  }

  private def getGenericOptions(cmd: StdCmdConfig): HashMap[String, Option[RawFormatParameter]] = {
    val mode = if (cmd.failOnInputNotPerSchema) {
      "FAILFAST"
    } else {
      "PERMISSIVE"
    }
    HashMap(
      "charset" -> cmd.charset.map(StringParameter),
      "mode" -> Option(StringParameter(mode))
    )
  }

  private def getXmlOptions(cmd: StdCmdConfig): HashMap[String, Option[RawFormatParameter]] = {
    if (cmd.rawFormat.equalsIgnoreCase("xml")) {
      HashMap("rowtag" -> cmd.rowTag.map(StringParameter))
    } else {
      HashMap()
    }
  }

  private def getCsvOptions(cmd: StdCmdConfig, numberOfColumns: Int = 0): HashMap[String, Option[RawFormatParameter]] = {
    if (cmd.rawFormat.equalsIgnoreCase("csv")) {
      HashMap(
        "delimiter" -> cmd.csvDelimiter.map(s => StringParameter(s.includingUnicode.includingNone)),
        "header" -> cmd.csvHeader.map(BooleanParameter),
        "quote" -> cmd.csvQuote.map(s => StringParameter(s.includingUnicode.includingNone)),
        "escape" -> cmd.csvEscape.map(s => StringParameter(s.includingUnicode.includingNone)),
        // increase the default limit on the number of columns if needed
        // default is set at org.apache.spark.sql.execution.datasources.csv.CSVOptions maxColumns
        "maxColumns" -> {if (numberOfColumns > SparkCSVReaderMaxColumnsDefault) Some(LongParameter(numberOfColumns)) else None}
      )
    } else {
      HashMap()
    }
  }

  private def getFixedWidthOptions(cmd: StdCmdConfig): HashMap[String, Option[RawFormatParameter]] = {
    if (cmd.rawFormat.equalsIgnoreCase("fixed-width")) {
      HashMap("trimValues" -> cmd.fixedWidthTrimValues.map(BooleanParameter))
    } else {
      HashMap()
    }
  }

  private def getCobolOptions(cmd: StdCmdConfig, dataset: Dataset)(implicit dao: MenasDAO): HashMap[String, Option[RawFormatParameter]] = {
    if (cmd.rawFormat.equalsIgnoreCase("cobol")) {
      val cobolOptions = cmd.cobolOptions.getOrElse(CobolOptions())
      val isXcomOpt = if (cobolOptions.isXcom) Some(true) else None
      val isTextOpt = if (cobolOptions.isText) Some(true) else None
      val isAscii = cobolOptions.encoding.exists(_.equalsIgnoreCase("ascii"))
      // For ASCII files --charset is converted into Cobrix "ascii_charset" option
      // For EBCDIC files --charset is converted into Cobrix "ebcdic_code_page" option
      HashMap(
        getCopybookOption(cobolOptions, dataset),
        "is_xcom" -> isXcomOpt.map(BooleanParameter),
        "is_text" -> isTextOpt.map(BooleanParameter),
        "string_trimming_policy" -> cobolOptions.trimmingPolicy.map(StringParameter),
        "encoding" -> cobolOptions.encoding.map(StringParameter),
        "ascii_charset" -> cmd.charset.flatMap(charset => if (isAscii) Option(StringParameter(charset)) else None),
        "ebcdic_code_page" -> cmd.charset.flatMap(charset => if (!isAscii) Option(StringParameter(charset)) else None),
        "schema_retention_policy" -> Some(StringParameter("collapse_root"))
      )
    } else {
      HashMap()
    }
  }

  private def getCopybookOption(opts: CobolOptions, dataset: Dataset)(implicit dao: MenasDAO): (String, Option[RawFormatParameter]) = {
    val copybook = opts.copybook
    if (copybook.isEmpty) {
      log.info("Copybook location is not provided via command line - fetching the copybook attached to the schema...")
      val copybookContents = dao.getSchemaAttachment(dataset.schemaName, dataset.schemaVersion)
      log.info(s"Applying the following copybook:\n$copybookContents")
      ("copybook_contents", Option(StringParameter(copybookContents)))
    } else {
      log.info(s"Use copybook at $copybook")
      ("copybook", Option(StringParameter(copybook)))
    }
  }

  private def prepareDataFrame(schema: StructType,
                               cmd: StdCmdConfig,
                               path: String,
                               dataset: Dataset)
                              (implicit spark: SparkSession,
                               fsUtils: FileSystemVersionUtils,
                               dao: MenasDAO): DataFrame = {
    val numberOfColumns = schema.fields.length
    val dfReaderConfigured = getFormatSpecificReader(cmd, dataset, numberOfColumns)

    val readerWithOptSchema = cmd.rawFormat.toLowerCase() match {
      case "parquet" | "cobol" =>
        dfReaderConfigured
      case _ =>
        val optColumnNameOfCorruptRecord = getColumnNameOfCorruptRecord(schema, cmd)
        val inputSchema = PlainSchemaGenerator.generateInputSchema(schema, optColumnNameOfCorruptRecord)
        dfReaderConfigured.schema(inputSchema)
    }

    val dfWithSchema = readerWithOptSchema.load(s"$path/*")
    ensureSplittable(dfWithSchema, path, schema)
  }

  private def getColumnNameOfCorruptRecord(schema: StructType, cmd: StdCmdConfig)
                                          (implicit spark: SparkSession): Option[String] = {
    // SparkUtils.setUniqueColumnNameOfCorruptRecord is called even if result is not used to avoid conflict
    val columnNameOfCorruptRecord = SparkUtils.setUniqueColumnNameOfCorruptRecord(spark, schema)
    if (cmd.rawFormat.equalsIgnoreCase("fixed-width")  || cmd.failOnInputNotPerSchema) {
      None
    } else {
      Option(columnNameOfCorruptRecord)
    }
  }

  //scalastyle:off parameter.number
  private def executeStandardization(performance: PerformanceMeasurer,
                                     dfAll: DataFrame,
                                     schema: StructType,
                                     cmd: StdCmdConfig,
                                     menasCredentials: MenasCredentials,
                                     pathCfg: PathCfg,
                                     recordIdGenerationStrategy: IdType)
                                    (implicit spark: SparkSession, udfLib: UDFLibrary, fsUtils: FileSystemVersionUtils): Unit = {
    //scalastyle:on parameter.number
    val rawDirSize: Long = fsUtils.getDirectorySize(pathCfg.inputPath)
    performance.startMeasurement(rawDirSize)

    handleControlInfoValidation()

    PerformanceMetricTools.addJobInfoToAtumMetadata("std", pathCfg.inputPath, pathCfg.outputPath,
      menasCredentials.username, cmd.cmdLineArgs.mkString(" "))
    val standardizedDF = try {
      StandardizationInterpreter.standardize(dfAll, schema, cmd.rawFormat, cmd.failOnInputNotPerSchema, recordIdGenerationStrategy)
    } catch {
      case e@ValidationException(msg, errors)                  =>
        AtumImplicits.SparkSessionWrapper(spark).setControlMeasurementError("Schema Validation", s"$msg\nDetails: ${
          errors.mkString("\n")
        }", "")
        throw e
      case NonFatal(e) if !e.isInstanceOf[ValidationException] =>
        val sw = new StringWriter
        e.printStackTrace(new PrintWriter(sw))
        AtumImplicits.SparkSessionWrapper(spark).setControlMeasurementError("Standardization", e.getMessage, sw.toString)
        throw e
    }

    //register renames with ATUM
    import za.co.absa.atum.AtumImplicits._
    val fieldRenames = SchemaUtils.getRenamesInSchema(schema)
    fieldRenames.foreach {
      case (destinationName, sourceName) => standardizedDF.registerColumnRename(sourceName, destinationName)
    }

    standardizedDF.setCheckpoint("Standardization - End", persistInDatabase = false)

    val recordCount = standardizedDF.lastCheckpointRowCount match {
      case None    => standardizedDF.count
      case Some(p) => p
    }
    if (recordCount == 0) { handleEmptyOutputAfterStandardization() }

    standardizedDF.write.parquet(pathCfg.outputPath)
    // Store performance metrics
    // (record count, directory sizes, elapsed time, etc. to _INFO file metadata and performance file)
    val stdDirSize = fsUtils.getDirectorySize(pathCfg.outputPath)
    performance.finishMeasurement(stdDirSize, recordCount)
    cmd.rowTag.foreach(rowTag => Atum.setAdditionalInfo("xml_row_tag" -> rowTag))
    if (cmd.csvDelimiter.isDefined) {
      cmd.csvDelimiter.foreach(delimiter => Atum.setAdditionalInfo("csv_delimiter" -> delimiter))
    }
    PerformanceMetricTools.addPerformanceMetricsToAtumMetadata(spark, "std", pathCfg.inputPath, pathCfg.outputPath,
      menasCredentials.username, cmd.cmdLineArgs.mkString(" "))
    standardizedDF.writeInfoFile(pathCfg.outputPath)
  }

  private def handleControlInfoValidation(): Unit = {
    ControlInfoValidation.addRawAndSourceRecordCountsToMetadata() match {
      case Failure(ex: za.co.absa.enceladus.utils.validation.ValidationException) => {
        val confEntry = "control.info.validation"
        conf.getString(confEntry) match {
          case "strict" => throw ex
          case "warning" => log.warn(ex.msg)
          case "none" =>
          case _ => throw new RuntimeException(s"Invalid $confEntry value")
        }
      }
      case Failure(ex) => throw ex
      case Success(_) =>
    }
  }

  private def handleEmptyOutputAfterStandardization()(implicit spark: SparkSession): Unit = {
    import za.co.absa.atum.core.Constants._

    val areCountMeasurementsAllZero = Atum.getControlMeasure.checkpoints
      .flatMap(checkpoint =>
        checkpoint.controls.filter(control =>
          control.controlName.equalsIgnoreCase(controlTypeRecordCount)))
      .forall(m => Try(m.controlValue.toString.toDouble).toOption.contains(0D))

    if (areCountMeasurementsAllZero) {
      log.warn("Empty output after running Standardization. Previous checkpoints show this is correct.")
    } else {
      val errMsg = "Empty output after running Standardization, while previous checkpoints show non zero record count"
      AtumImplicits.SparkSessionWrapper(spark).setControlMeasurementError("Standardization", errMsg, "")
      throw new IllegalStateException(errMsg)
    }
  }

  private def ensureSplittable(df: DataFrame, path: String, schema: StructType)
                              (implicit spark: SparkSession, fsUtils: FileSystemVersionUtils) = {
    if (fsUtils.isNonSplittable(path)) {
      convertToSplittable(df, path, schema)
    } else {
      df
    }
  }

  private def convertToSplittable(df: DataFrame, path: String, schema: StructType)
                                 (implicit spark: SparkSession, fsUtils: FileSystemVersionUtils) = {
    log.warn("Dataset is stored in a non-splittable format. This can have a severe performance impact.")

    val tempParquetDir = s"/tmp/nonsplittable-to-parquet-${UUID.randomUUID()}"
    log.warn(s"Converting to Parquet in temporary dir: $tempParquetDir")

    // Handle renaming of source columns in case there are columns
    // that will break because of issues in column names like spaces
    df.select(schema.fields.map { field: StructField =>
      renameSourceColumn(df, field)
    }: _*).write.parquet(tempParquetDir)

    fsUtils.deleteOnExit(tempParquetDir)
    // Reload from temp parquet and reverse column renaming above
    val dfTmp = spark.read.parquet(tempParquetDir)
    dfTmp.select(schema.fields.map { field: StructField =>
      reverseRenameSourceColumn(dfTmp, field)
    }: _*)
  }

  private def renameSourceColumn(df: DataFrame, field: StructField): Column = {
    if (field.metadata.contains(MetadataKeys.SourceColumn)) {
      val sourceColumnName = field.metadata.getString(MetadataKeys.SourceColumn)
      log.info(s"schema field : ${field.name} : rename : $sourceColumnName")
      df.col(sourceColumnName).as(field.name, field.metadata)
    } else {
      df.col(field.name)
    }
  }

  private def reverseRenameSourceColumn(df: DataFrame, field: StructField): Column = {
    if (field.metadata.contains(MetadataKeys.SourceColumn)) {
      val sourceColumnName = field.metadata.getString(MetadataKeys.SourceColumn)
      log.info(s"schema field : $sourceColumnName : reverse rename : ${field.name}")
      df.col(field.name).as(sourceColumnName)
    } else {
      df.col(field.name)
    }
  }

  private def enableControlFramework(pathCfg: PathCfg, cmd: StdCmdConfig, reportVersion: Int)
                                    (implicit spark: SparkSession, dao: MenasDAO): Unit = {
    // Enable Control Framework
    import za.co.absa.atum.AtumImplicits.SparkSessionWrapper
    spark.enableControlMeasuresTracking(s"${pathCfg.inputPath}/_INFO").setControlMeasuresWorkflow("Standardization")

    // Enable control framework performance optimization for pipeline-like jobs
    Atum.setAllowUnpersistOldDatasets(true)

    // Enable non-default persistence storage level if provided in the command line
    cmd.persistStorageLevel.foreach(Atum.setCachingStorageLevel)

    // Enable Menas plugin for Control Framework
    MenasPlugin.enableMenas(
      conf,
      cmd.datasetName,
      cmd.datasetVersion,
      cmd.reportDate,
      reportVersion,
      isJobStageOnly = true,
      generateNewRun = true)

    // Add report date and version (aka Enceladus info date and version) to Atum's metadata
    Atum.setAdditionalInfo(Constants.InfoDateColumn -> cmd.reportDate)
    Atum.setAdditionalInfo(Constants.InfoVersionColumn -> reportVersion.toString)

    // Add the raw format of the input file(s) to Atum's metadta as well
    Atum.setAdditionalInfo("raw_format" -> cmd.rawFormat)
  }

  private def writePerformanceMetrics(performance: PerformanceMeasurer, fileName: String): Unit = {
    try {
      performance.writeMetricsToFile(fileName)
    } catch {
      case NonFatal(e) => log.error(s"Unable to write performance metrics to file '$fileName': ${e.getMessage}")
    }
  }

  private def postStandardizationSteps(cmd: StdCmdConfig): Unit = {
    Atum.getControlMeasure.runUniqueId

    val name = cmd.datasetName
    val version = cmd.datasetVersion
    MenasPlugin.runNumber.foreach { runNumber =>
      menasBaseUrls.foreach { menasBaseUrl =>
        val apiUrl = MenasRunUrl.getMenasApiRunUrl(menasBaseUrl, name, version, runNumber)
        val uiUrl = MenasRunUrl.getMenasUiRunUrl(menasBaseUrl, name, version, runNumber)

        log.info(s"Menas API Run URL: $apiUrl")
        log.info(s"Menas UI Run URL: $uiUrl")
      }
    }
  }

  def buildRawPath(cmd: StdCmdConfig, dataset: Dataset, dateTokens: Array[String], reportVersion: Int): String = {
    cmd.rawPathOverride match {
      case None =>
        val folderSuffix = s"/${dateTokens(0)}/${dateTokens(1)}/${dateTokens(2)}/v$reportVersion"
        cmd.folderPrefix match {
          case None => s"${dataset.hdfsPath}$folderSuffix"
          case Some(folderPrefix) => s"${dataset.hdfsPath}/$folderPrefix$folderSuffix"
        }
      case Some(rawPathOverride) => rawPathOverride
    }
  }

  private final case class PathCfg(inputPath: String, outputPath: String)
}
