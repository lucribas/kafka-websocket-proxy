package net.scalytica.kafka.wsproxy.producer

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.kafka.scaladsl.Producer
import akka.kafka.{ProducerMessage, ProducerSettings}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink}
import com.typesafe.scalalogging.Logger
import io.circe.Decoder
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.ProducerInterceptorClass
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.AvroProducerRecord
import net.scalytica.kafka.wsproxy.codecs.WsProxyAvroSerde
import net.scalytica.kafka.wsproxy.models._
import org.apache.kafka.clients.producer.ProducerConfig._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.Serializer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * Functions for initialising Kafka producer sinks and flows.
 */
object WsProducer {

  private[this] val logger = Logger(getClass)

  /** Create producer settings to use for the Kafka producer. */
  private[this] def baseProducerSettings[K, V](
      cfg: AppCfg,
      as: ActorSystem,
      ks: Option[Serializer[K]],
      vs: Option[Serializer[V]]
  ) = {
    val kafkaUrl = cfg.server.kafkaBootstrapUrls.mkString(",")

    ProducerSettings(as, ks, vs)
      .withBootstrapServers(kafkaUrl)
      .withProperties(
        // scalastyle:off
        // Enables stream monitoring in confluent control center
        INTERCEPTOR_CLASSES_CONFIG -> ProducerInterceptorClass
        // scalastyle:on
      )
  }

  /**
   * Creates an instance of producer settings with key and value serializers.
   */
  private[this] def producerSettingsWithKey[K, V](
      implicit
      cfg: AppCfg,
      as: ActorSystem,
      ks: Serializer[K],
      vs: Serializer[V]
  ) = baseProducerSettings(cfg, as, Option(ks), Option(vs))

  /**
   * Parses an input message, in the form of a JSON String, into an instance of
   * [[WsProducerRecord]], which will be passed on to Kafka down-stream.
   *
   * @param jsonStr the String containing the JSON formatted message
   * @param keyDec  the JSON decoder to use for the message key
   * @param valDec  the JSON decoder to use for the message value
   * @tparam K      the message key type
   * @tparam V      the message value type
   * @return an instance of [[WsProducerRecord]]
   */
  private[this] def parseInput[K, V](jsonStr: String)(
      implicit
      keyDec: Decoder[K],
      valDec: Decoder[V]
  ): WsProducerRecord[K, V] = {
    import io.circe._
    import io.circe.parser._
    import net.scalytica.kafka.wsproxy.codecs.Decoders._

    parse(jsonStr) match {
      case Left(ParsingFailure(message, err)) =>
        logger.error(s"Error parsing JSON string $message")
        logger.debug(s"JSON was: $jsonStr")
        throw err

      case Right(json) =>
        json.as[WsProducerRecord[K, V]] match {
          case Left(err)  => throw err
          case Right(wpr) => wpr
        }
    }
  }

  /**
   * Converts a [[WsProducerRecord]] into a Kafka [[ProducerRecord]].
   *
   * @param topic the topic name the record is to be written to.
   * @param msg   the message to send to the Kafka topic.
   * @tparam K    the message key type
   * @tparam V    the message value type
   * @return an instance of [[ProducerRecord]]
   */
  private[this] def asKafkaProducerRecord[K, V](
      topic: TopicName,
      msg: WsProducerRecord[K, V]
  ): ProducerRecord[K, V] = msg match {
    case kvm: ProducerKeyValueRecord[K, V] =>
      new ProducerRecord[K, V](topic.value, kvm.key.value, kvm.value.value)

    case vm: ProducerValueRecord[V] =>
      new ProducerRecord[K, V](topic.value, vm.value.value)

    case ProducerEmptyMessage =>
      throw new IllegalStateException(
        "EmptyMessage passed through stream pipeline, but should have" +
          " been filtered out."
      )
  }

  /**
   *
   * @param args input arguments defining the base configs for the producer.
   * @param cfg  the [[AppCfg]] containing application configurations.
   * @param as   actor system to use
   * @param mat  actor materializer to use
   * @param ks   the message key serializer to use
   * @param vs   the message value serializer to use
   * @param kd   the JSON decoder to use for the message key
   * @param vd   the JSON decoder to use for the message value
   * @tparam K   the message key type
   * @tparam V   the message value type
   * @return a [[Flow]] that sends messages to Kafka and passes on the result
   *         down-stream for further processing. For example sending the
   *         metadata to the external web client for it to process locally.
   */
  def produceJson[K, V](args: InSocketArgs)(
      implicit
      cfg: AppCfg,
      as: ActorSystem,
      mat: ActorMaterializer,
      ks: Serializer[K],
      vs: Serializer[V],
      kd: Decoder[K],
      vd: Decoder[V]
  ): Flow[Message, WsProducerResult, NotUsed] = {
    implicit val ec: ExecutionContext = as.dispatcher

    Flow[Message]
      .mapConcat {
        case tm: TextMessage   => TextMessage(tm.textStream) :: Nil
        case bm: BinaryMessage => bm.dataStream.runWith(Sink.ignore); Nil
      }
      .mapAsync(1)(_.toStrict(2 seconds).map(_.text))
      .map(str => parseInput[K, V](str))
      .filter(_.nonEmpty)
      .map { wpr =>
        val record = asKafkaProducerRecord(args.topic, wpr)
        ProducerMessage.Message(record, record)
      }
      .via(Producer.flexiFlow(producerSettingsWithKey[K, V]))
      .map(r => WsProducerResult.fromProducerResults(r))
      .flatMapConcat(seqToSource)
  }

  def produceAvro[K, V](args: InSocketArgs)(
      implicit
      cfg: AppCfg,
      as: ActorSystem,
      mat: ActorMaterializer,
      serde: WsProxyAvroSerde[AvroProducerRecord]
  ): Flow[Message, WsProducerResult, NotUsed] = {
    implicit val ec: ExecutionContext = as.dispatcher

    import net.scalytica.kafka.wsproxy.codecs.BasicSerdes.ByteArrSerializer

    Flow[Message]
      .mapConcat {
        case tm: TextMessage   => tm.textStream.runWith(Sink.ignore); Nil
        case bm: BinaryMessage => BinaryMessage(bm.dataStream) :: Nil
      }
      .mapAsync(1)(_.toStrict(2 seconds).map(_.data))
      .map(bs => serde.deserialize("", bs.toArray))
      .map { wpr =>
        val record =
          asKafkaProducerRecord(args.topic, WsProducerRecord.fromAvro(wpr))
        ProducerMessage.Message(record, record)

      }
      .via(
        Producer.flexiFlow(producerSettingsWithKey[Array[Byte], Array[Byte]])
      )
      .map(r => WsProducerResult.fromProducerResults(r))
      .flatMapConcat(seqToSource)
  }

}