package experiments

import data.DataPoint
import experiments.GenerateSignals.{getCorrelation, getDelta, getMax, getMean, getMin, getSampleEntropy, getStdev, processSOFA, processSignal}
import models.LSTMSequenceClassifier
import org.apache.commons.lang3.time.StopWatch
import org.apache.flink.api.common.eventtime.{SerializableTimestampAssigner, WatermarkStrategy}
import org.apache.flink.api.common.serialization.{SimpleStringEncoder, SimpleStringSchema}
import org.apache.flink.api.java.tuple.Tuple5
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.core.fs.Path
import org.apache.flink.streaming.api.functions.sink.filesystem.StreamingFileSink
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.streaming.api.windowing.assigners.{SlidingEventTimeWindows, TumblingEventTimeWindows}
import org.apache.flink.api.scala.createTypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.triggers.CountTrigger
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer
import util.signalgeneration.{GenerateSignalsMap, GenerateTuplesForModel}

import java.time.{Duration, LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.Properties
import scala.collection.mutable.ListBuffer

object IntegratedExperiment {
  // Arguments for running the program:
//  --input
//  ./src/main/resources/mimic2wdb/train-set/C1/signals_66152_processed.csv
//  --orderMA
//    60
//  --slideMA
//    1
//  --output
//    generatedSignal.csv
//  --kafkaTopic
//    mimicdata
//  --modelDir
//    "FlinkSequences/seq_classification/python/lstm_model_vitals_new/"

  def main(args: Array[String]): Unit = {

    val parameters: ParameterTool = ParameterTool.fromArgs(args)
    val signals =
      Array("HR", "ABPSys", "ABPDias", "ABPMean", "RESP", "SpO2", "SOFA_SCORE")

    val mimicFile = parameters.getRequired("input")
    val outCsvFile = parameters.getRequired("output")
    val kafkaTopic = parameters.getRequired("kafkaTopic")

    val modelDir = parameters.getRequired("modelDir")

    val orderMA = parameters.getRequired("orderMA").toLong
    val slideMA = parameters.getRequired("slideMA").toLong
    println("  Input file: " + mimicFile)
    println("  Result sink in kafka topic: " + kafkaTopic)

    // Properties for writing stream in Kafka
    val properties = new Properties
    properties.setProperty("bootstrap.servers", "localhost:9092")

    val kafkaProducer = new FlinkKafkaProducer[String](
      kafkaTopic,
      new SimpleStringSchema(),
      properties
    )

    val sink: StreamingFileSink[String] = StreamingFileSink
      .forRowFormat(
        new Path(outCsvFile),
        new SimpleStringEncoder[String]("UTF-8")
      )
      .build()

//    val conf: Configuration = new Configuration()

    val env: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
//      StreamExecutionEnvironment.createLocalEnvironmentWithWebUI(conf)
//    env.setParallelism(2)
    env.getConfig.enableObjectReuse()


    val mimicData = env.readTextFile(mimicFile).filter(t => !t.contains("TIME"))

    val watermarkStrategy = WatermarkStrategy
      .forBoundedOutOfOrderness(Duration.ofMinutes(10))
//      .forMonotonousTimestamps()
      .withTimestampAssigner(
        new SerializableTimestampAssigner[DataPoint[Double]] {
          override def extractTimestamp(t: DataPoint[Double], l: Long): Long =
            t.t
        }
      )

    val mimicDataWithTimestamps: DataStream[DataPoint[Double]] = mimicData
      .flatMap((line: String) => {
        val data: Array[String] = line.split(",")
        val timestamp = LocalDateTime
          .parse(data(0), DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/uuuu"))
          .atZone(ZoneId.systemDefault())
          .toInstant
          .toEpochMilli
        var list: ListBuffer[DataPoint[Double]] =
          new ListBuffer[DataPoint[Double]]
        (data.slice(1, data.length), signals).zipped.foreach((item, signal) =>
          list += new DataPoint[Double](timestamp, signal, item.toDouble)
        )
        list
      })
      .assignTimestampsAndWatermarks(watermarkStrategy)

    val hrProcessedSignal = processSignal(mimicDataWithTimestamps, "HR", 60, 1)
    val respProcessedSignal =
      processSignal(mimicDataWithTimestamps, "RESP", 60, 1)
    val abpmProcessedSignal =
      processSignal(mimicDataWithTimestamps, "ABPMean", 60, 1)
    val abpsProcessedSignal =
      processSignal(mimicDataWithTimestamps, "ABPSys", 60, 1)
    val abpdProcessedSignal =
      processSignal(mimicDataWithTimestamps, "ABPDias", 60, 1)
    val spo2ProcessedSignal =
      processSignal(mimicDataWithTimestamps, "SpO2", 60, 1)
    val sofascore = processSOFA(mimicDataWithTimestamps, 60, 1)

    val hrRespCorrelation = getCorrelation(
      hrProcessedSignal,
      respProcessedSignal,
      "HR",
      "RESP",
      orderMA,
      slideMA
    )
    val hrAbpmeanCorrelation = getCorrelation(
      hrProcessedSignal,
      abpmProcessedSignal,
      "HR",
      "ABPMean",
      orderMA,
      slideMA
    )
    val hrAbpsysCorrelation = getCorrelation(
      hrProcessedSignal,
      abpsProcessedSignal,
      "HR",
      "ABPSys",
      orderMA,
      slideMA
    )
    val hrAbpdiasCorrelation = getCorrelation(
      hrProcessedSignal,
      abpdProcessedSignal,
      "HR",
      "ABPDias",
      orderMA,
      slideMA
    )
    val hrSpo2Correlation = getCorrelation(
      hrProcessedSignal,
      spo2ProcessedSignal,
      "HR",
      "SpO2",
      orderMA,
      slideMA
    )
    val respAbpmeanCorrelation = getCorrelation(
      respProcessedSignal,
      abpmProcessedSignal,
      "RESP",
      "ABPMean",
      orderMA,
      slideMA
    )
    val respAbpsysCorrelation = getCorrelation(
      respProcessedSignal,
      abpsProcessedSignal,
      "RESP",
      "ABPSys",
      orderMA,
      slideMA
    )
    val respAbpdiasCorrelation = getCorrelation(
      respProcessedSignal,
      abpdProcessedSignal,
      "RESP",
      "ABPDias",
      orderMA,
      slideMA
    )
    val respSpo2Correlation = getCorrelation(
      respProcessedSignal,
      spo2ProcessedSignal,
      "RESP",
      "SpO2",
      orderMA,
      slideMA
    )
    val abpmeanAbpsysCorrelation = getCorrelation(
      abpmProcessedSignal,
      abpsProcessedSignal,
      "ABPMean",
      "ABPSys",
      orderMA,
      slideMA
    )
    val abpmeanAbpdiasCorrelation = getCorrelation(
      abpmProcessedSignal,
      abpdProcessedSignal,
      "ABPMean",
      "ABPDias",
      orderMA,
      slideMA
    )
    val abpmeanSpo2Correlation = getCorrelation(
      abpmProcessedSignal,
      spo2ProcessedSignal,
      "ABPMean",
      "SpO2",
      orderMA,
      slideMA
    )
    val abpsysAbpdiasCorrelation = getCorrelation(
      abpsProcessedSignal,
      abpdProcessedSignal,
      "ABPSys",
      "ABPDias",
      orderMA,
      slideMA
    )
    val abpsysSpo2Correlation = getCorrelation(
      abpsProcessedSignal,
      spo2ProcessedSignal,
      "ABPSys",
      "SpO2",
      orderMA,
      slideMA
    )
    val abpdiasSpo2Correlation = getCorrelation(
      abpdProcessedSignal,
      spo2ProcessedSignal,
      "ABPDias",
      "SpO2",
      orderMA,
      slideMA
    )

    val sampleEntropyHR = getSampleEntropy(hrProcessedSignal, "HR", orderMA)
    val sampleEntropyRESP =
      getSampleEntropy(respProcessedSignal, "RESP", orderMA)
    val sampleEntropyABPMean =
      getSampleEntropy(abpmProcessedSignal, "ABPMean", orderMA)
    val sampleEntropyABPSys =
      getSampleEntropy(abpsProcessedSignal, "ABPSys", orderMA)
    val sampleEntropyABPDias =
      getSampleEntropy(abpdProcessedSignal, "ABPDias", orderMA)
    val sampleEntropySpO2 =
      getSampleEntropy(spo2ProcessedSignal, "SpO2", orderMA)

    val meanHR = getMean(hrProcessedSignal, orderMA, slideMA)
    val stdevHR = getStdev(hrProcessedSignal, orderMA, slideMA)
    val minHR = getMin(hrProcessedSignal, orderMA, slideMA)
    val maxHR = getMax(hrProcessedSignal, orderMA, slideMA)

    val meanRESP = getMean(respProcessedSignal, orderMA, slideMA)
    val stdevRESP = getStdev(respProcessedSignal, orderMA, slideMA)
    val minRESP = getMin(respProcessedSignal, orderMA, slideMA)
    val maxRESP = getMax(respProcessedSignal, orderMA, slideMA)

    val meanABPMean = getMean(abpmProcessedSignal, orderMA, slideMA)
    val stdevABPMean = getStdev(abpmProcessedSignal, orderMA, slideMA)
    val minABPMean = getMin(abpmProcessedSignal, orderMA, slideMA)
    val maxABPMean = getMax(abpmProcessedSignal, orderMA, slideMA)

    val meanABPSys = getMean(abpsProcessedSignal, orderMA, slideMA)
    val stdevABPSys = getStdev(abpsProcessedSignal, orderMA, slideMA)
    val minABPSys = getMin(abpsProcessedSignal, orderMA, slideMA)
    val maxABPSys = getMax(abpsProcessedSignal, orderMA, slideMA)

    val meanABPDias = getMean(abpdProcessedSignal, orderMA, slideMA)
    val stdevABPDias = getStdev(abpdProcessedSignal, orderMA, slideMA)
    val minABPDias = getMin(abpdProcessedSignal, orderMA, slideMA)
    val maxABPDias = getMax(abpdProcessedSignal, orderMA, slideMA)

    val meanSpO2 = getMean(spo2ProcessedSignal, orderMA, slideMA)
    val stdevSpO2 = getStdev(spo2ProcessedSignal, orderMA, slideMA)
    val minSpO2 = getMin(spo2ProcessedSignal, orderMA, slideMA)
    val maxSpO2 = getMax(spo2ProcessedSignal, orderMA, slideMA)

    val deltaHR = getDelta(hrProcessedSignal, "HR")
    val deltaDeltaHR = getDelta(deltaHR, "deltaHR")
    val deltaResp = getDelta(respProcessedSignal, "RESP")
    val deltaDeltaResp = getDelta(deltaResp, "deltaRESP")
    val deltaAbpm = getDelta(abpmProcessedSignal, "ABPMean")
    val deltaDeltaAbpm = getDelta(deltaAbpm, "deltaABPMean")
    val deltaAbps = getDelta(abpsProcessedSignal, "ABPSys")
    val deltaDeltaAbps = getDelta(deltaAbps, "deltaABPSys")
    val deltaAbpd = getDelta(abpdProcessedSignal, "ABPDias")
    val deltaDeltaAbpd = getDelta(deltaAbpd, "deltaABPDias")
    val deltaSpO2 = getDelta(spo2ProcessedSignal, "SpO2")
    val deltaDeltaSpO2 = getDelta(deltaSpO2, "deltaSpO2")

    val lstmInput = hrRespCorrelation
      .filter(t => !t.value.isNaN && !t.value.isInfinity)
      .union(
        hrAbpmeanCorrelation.filter(t => !t.value.isNaN && !t.value.isInfinity)
      )
      .union(
        hrAbpsysCorrelation.filter(t => !t.value.isNaN && !t.value.isInfinity)
      )
      .union(
        hrAbpdiasCorrelation.filter(t => !t.value.isNaN && !t.value.isInfinity)
      )
      .union(
        hrSpo2Correlation.filter(t => !t.value.isNaN && !t.value.isInfinity)
      )
      .union(
        respAbpmeanCorrelation.filter(t =>
          !t.value.isNaN && !t.value.isInfinity
        )
      )
      .union(
        respAbpsysCorrelation.filter(t => !t.value.isNaN && !t.value.isInfinity)
      )
      .union(
        respAbpdiasCorrelation.filter(t =>
          !t.value.isNaN && !t.value.isInfinity
        )
      )
      .union(
        respSpo2Correlation.filter(t => !t.value.isNaN && !t.value.isInfinity)
      )
      .union(
        abpmeanAbpsysCorrelation.filter(t =>
          !t.value.isNaN && !t.value.isInfinity
        )
      )
      .union(
        abpmeanAbpdiasCorrelation.filter(t =>
          !t.value.isNaN && !t.value.isInfinity
        )
      )
      .union(
        abpmeanSpo2Correlation.filter(t =>
          !t.value.isNaN && !t.value.isInfinity
        )
      )
      .union(
        abpsysAbpdiasCorrelation.filter(t =>
          !t.value.isNaN && !t.value.isInfinity
        )
      )
      .union(
        abpsysSpo2Correlation.filter(t => !t.value.isNaN && !t.value.isInfinity)
      )
      .union(
        abpdiasSpo2Correlation.filter(t =>
          !t.value.isNaN && !t.value.isInfinity
        )
      )
      .union(meanHR.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(stdevHR.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(minHR.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(maxHR.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(meanRESP.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(stdevRESP.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(minRESP.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(maxRESP.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(meanABPMean.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(stdevABPMean.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(minABPMean.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(maxABPMean.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(meanABPSys.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(stdevABPSys.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(minABPSys.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(maxABPSys.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(meanABPDias.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(stdevABPDias.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(minABPDias.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(maxABPDias.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(meanSpO2.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(stdevSpO2.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(minSpO2.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(maxSpO2.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(sampleEntropyHR.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(
        sampleEntropyRESP.filter(t => !t.value.isNaN && !t.value.isInfinity)
      )
      .union(
        sampleEntropyABPMean.filter(t => !t.value.isNaN && !t.value.isInfinity)
      )
      .union(
        sampleEntropyABPSys.filter(t => !t.value.isNaN && !t.value.isInfinity)
      )
      .union(
        sampleEntropyABPDias.filter(t => !t.value.isNaN && !t.value.isInfinity)
      )
      .union(
        sampleEntropySpO2.filter(t => !t.value.isNaN && !t.value.isInfinity)
      )
      .union(deltaHR.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(deltaDeltaHR.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(deltaResp.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(deltaDeltaResp.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(deltaAbpm.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(deltaDeltaAbpm.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(deltaAbps.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(deltaDeltaAbps.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(deltaAbpd.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(deltaDeltaAbpd.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(deltaSpO2.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(deltaDeltaSpO2.filter(t => !t.value.isNaN && !t.value.isInfinity))
      .union(sofascore)
      .keyBy(t => t.t)
      .window(TumblingEventTimeWindows.of(Time.minutes(orderMA)))
      .trigger(CountTrigger.of(58))
      .process(GenerateSignalsMap())
      .map(t => t._2)
      .map(new GenerateTuplesForModel())

    val output: DataStream[Tuple5[String, String, String, Double, Double]] =
      lstmInput
        .keyBy(t => t.f0)
        .window(SlidingEventTimeWindows.of(Time.hours(3), Time.hours(1)))
        .process(
          new LSTMSequenceClassifier(
            modelDir
          )
        )

    output
      .map(t => t.toString)
      .addSink(sink)

    env.execute("MimicDataJob")

  }
}
