package filodb.downsampler.chunk

import scala.concurrent.duration._

import com.typesafe.config.{Config, ConfigFactory}
import net.ceedubs.ficus.Ficus._

import filodb.coordinator.{FilodbSettings, NodeClusterActor}
import filodb.core.store.{IngestionConfig, StoreConfig}
import filodb.downsampler.DownsamplerContext
import filodb.prometheus.ast.InstantExpression
import filodb.prometheus.parse.Parser


/**
 * DownsamplerSettings is always used in the context of an object so that it need not be serialized to a spark executor
 * from the spark application driver.
 */
class DownsamplerSettings(conf: Config = ConfigFactory.empty()) extends Serializable {

  @transient lazy val filodbSettings = new FilodbSettings(conf)

  @transient lazy val filodbConfig = filodbSettings.allConfig.getConfig("filodb")

  @transient lazy val downsamplerConfig = {
    val conf = filodbConfig.getConfig("downsampler")
    DownsamplerContext.dsLogger.info(s"Loaded following downsampler config: ${conf.root().render()}" )
    conf
  }

  @transient lazy val chunkDownsamplerIsEnabled = downsamplerConfig.getBoolean("chunk-downsampler-enabled")

  @transient lazy val cassandraConfig = filodbConfig.getConfig("cassandra")

  @transient lazy val rawDatasetName = downsamplerConfig.getString("raw-dataset-name")

  @transient lazy val rawDatasetIngestionConfig = {
    DownsamplerContext.dsLogger.info(s"Parsing dataset configs at ${filodbSettings.datasetConfPaths}")
    val ingConf = filodbSettings.streamConfigs.map { config =>
      IngestionConfig(config, NodeClusterActor.noOpSource.streamFactoryClass).get
    }.find(_.ref.toString == rawDatasetName).get
    DownsamplerContext.dsLogger.info(s"DatasetConfig for dataset $rawDatasetName was $ingConf")
    ingConf
  }

  @transient lazy val rawSchemaNames = rawDatasetIngestionConfig.downsampleConfig.schemas

  @transient lazy val downsampleResolutions = rawDatasetIngestionConfig.downsampleConfig.resolutions

  @transient lazy val downsampleTtls = rawDatasetIngestionConfig.downsampleConfig.ttls.map(_.toSeconds.toInt)

  @transient lazy val downsampledDatasetRefs =
    rawDatasetIngestionConfig.downsampleConfig.downsampleDatasetRefs(rawDatasetName)

  @transient lazy val downsampleStoreConfig = StoreConfig(downsamplerConfig.getConfig("downsample-store-config"))

  @transient lazy val ttlByResolution = downsampleResolutions.zip(downsampleTtls).toMap

  @transient lazy val batchSize = downsamplerConfig.getInt("cass-write-batch-size")

  @transient lazy val cassFetchSize = downsamplerConfig.getInt("cass-read-fetch-size")

  @transient lazy val cassWriteTimeout = downsamplerConfig.as[FiniteDuration]("cassandra-write-timeout")

  @transient lazy val widenIngestionTimeRangeBy = downsamplerConfig.as[FiniteDuration]("widen-ingestion-time-range-by")

  @transient lazy val downsampleChunkDuration = downsampleStoreConfig.flushInterval.toMillis

  @transient lazy val shouldSleepForMetricsFlush = downsamplerConfig.as[Boolean]("should-sleep-for-metrics-flush")

  @transient lazy val allow = downsamplerConfig.as[Seq[Map[String, String]]]("allow-filters").map(_.toSeq)

  @transient lazy val block = downsamplerConfig.as[Seq[Map[String, String]]]("block-filters").map(_.toSeq)

  @transient lazy val trace = downsamplerConfig.as[Seq[Map[String, String]]]("trace-filters").map(_.toSeq)

  @transient lazy val exportIsEnabled = downsamplerConfig.getBoolean("data-export.enabled")

  @transient lazy val sparkSessionFactoryClass = downsamplerConfig.getString("spark-session-factory")

  @transient lazy val exportRuleKey = downsamplerConfig.as[Seq[String]]("data-export.key-labels")

  @transient lazy val exportBucket = downsamplerConfig.as[String]("data-export.bucket")

  @transient lazy val exportDropLabels = downsamplerConfig.as[Seq[String]]("data-export.drop-labels")

  @transient lazy val exportKeyToRules = {
    val keyRulesPairs = downsamplerConfig.as[Seq[Config]]("data-export.groups").map { group =>
      val key = group.as[Seq[String]]("key")
      val rules = group.as[Seq[Config]]("rules").map { rule =>
        val allowFilterGroups = rule.as[Seq[Seq[String]]]("allow-filters").map{ group =>
          Parser.parseQuery(s"{${group.mkString(",")}}")
            .asInstanceOf[InstantExpression].getUnvalidatedColumnFilters()
        }
        val blockFilterGroups = rule.as[Seq[Seq[String]]]("block-filters").map{ group =>
          Parser.parseQuery(s"{${group.mkString(",")}}")
            .asInstanceOf[InstantExpression].getUnvalidatedColumnFilters()
        }
        val dropLabels = rule.as[Seq[String]]("drop-labels")
        ExportRule(allowFilterGroups, blockFilterGroups, dropLabels)
      }
      (key -> rules)
    }
    assert(keyRulesPairs.map(_._1).distinct.size == keyRulesPairs.size,
      "export rule group keys must be unique")
    keyRulesPairs.toMap
  }

  @transient lazy val exportPathSpecPairs =
    downsamplerConfig.as[Seq[String]]("data-export.path-spec")
      .sliding(2, 2).map(seq => (seq.head, seq.last)).toSeq

  @transient lazy val exportOptions = downsamplerConfig.as[Map[String, String]]("data-export.options")

  @transient lazy val exportSaveMode = downsamplerConfig.getString("data-export.save-mode")

  @transient lazy val exportFormat = downsamplerConfig.getString("data-export.format")

  /**
   * Two conditions should satisfy for eligibility:
   * (a) If allow list is nonEmpty partKey should match a filter in the allow list.
   * (b) It should not match any filter in block
   */
  def isEligibleForDownsample(pkPairs: Seq[(String, String)]): Boolean = {
    if (allow.nonEmpty && !allow.exists(w => w.forall(pkPairs.contains))) {
      false
    } else {
      block.forall(w => !w.forall(pkPairs.contains))
    }
  }

  def shouldTrace(pkPairs: Seq[(String, String)]): Boolean = {
    trace.exists(w => w.forall(pkPairs.contains))
  }
}
