package net.scalytica.kafka.wsproxy.websockets

import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Route, ValidationRejection}
import akka.kafka.scaladsl.Consumer
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.typed.scaladsl.ActorSink
import akka.util.{ByteString, Timeout}
import akka.{Done, NotUsed}
import io.circe.Encoder
import io.circe.Printer.noSpaces
import io.circe.parser.parse
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.SocketProtocol.{AvroPayload, JsonPayload}
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.codecs.Decoders._
import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.codecs.ProtocolSerdes.{
  avroCommitSerde,
  avroConsumerRecordSerde
}
import net.scalytica.kafka.wsproxy.consumer.{CommitStackHandler, WsConsumer}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{
  Formats,
  OutSocketArgs,
  WsCommit,
  WsConsumerRecord
}
import net.scalytica.kafka.wsproxy.session.SessionHandler._
import net.scalytica.kafka.wsproxy.session.{Session, SessionHandlerProtocol}
import net.scalytica.kafka.wsproxy.{
  WithSchemaRegistryConfig,
  wsMessageToStringFlow,
  _
}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

trait OutboundWebSocket extends WithSchemaRegistryConfig with WithProxyLogger {

  implicit private[this] val timeout: Timeout = 3 seconds

  /** Convenience function for logging and throwing an error in a Flow */
  def logAndThrow[T](message: String, t: Throwable): T = {
    logger.error(message, t)
    throw t
  }

  // scalastyle:off method.length
  /**
   * Request handler for the outbound Kafka WebSocket connection.
   *
   * @param args the output arguments to pass on to the consumer.
   * @return a [[Route]] for accessing the outbound WebSocket functionality.
   */
  def outboundWebSocket(
      args: OutSocketArgs
  )(
      implicit cfg: AppCfg,
      as: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext,
      sessionHandler: ActorRef[SessionHandlerProtocol.Protocol]
  ): Route = {
    logger.debug("Initialising outbound websocket")

    implicit val scheduler = as.scheduler.toTyped

    val wsAdminClient   = new WsKafkaAdminClient(cfg)
    val topicPartitions = wsAdminClient.topicPartitions(args.topic)

    val serverId = cfg.server.serverId
    val clientId = args.clientId
    val groupId  = args.groupId

    val consumerAddResult = for {
      ir     <- sessionHandler.initSession(groupId, topicPartitions)
      _      <- logger.debugf(s"Session ${ir.session.consumerGroupId} ready.")
      addRes <- sessionHandler.addConsumer(groupId, clientId, serverId)
    } yield addRes

    lazy val initSocket = () =>
      prepareOutboundWebSocket(args) { () =>
        sessionHandler.removeConsumer(groupId, clientId).map(_ => Done)
      }

    onSuccess(consumerAddResult) {
      case Session.ConsumerAdded(_) =>
        initSocket()

      case Session.ConsumerExists(_) =>
        reject(
          ValidationRejection(
            s"WebSocket for consumer ${clientId.value} in session " +
              s"${groupId.value} not established because a consumer with the" +
              " same ID is already registered"
          )
        )

      case Session.ConsumerLimitReached(s) =>
        reject(
          ValidationRejection(
            s"The maximum number of WebSockets for session ${groupId.value} " +
              s"has been reached. Limit is ${s.consumerLimit}"
          )
        )

      case Session.SessionNotFound(_) =>
        reject(
          ValidationRejection(
            s"Could not find an active session for ${groupId.value}"
          )
        )

      case wrong =>
        logger.error(
          s"Adding consumer failed with an unexpected state." +
            s" Session:\n ${wrong.session}"
        )
        failWith(
          new InternalError(
            "An unexpected error occurred when trying to establish the " +
              s"WebSocket consumer ${clientId.value} in session" +
              s" ${groupId.value}"
          )
        )
    }
  }
  // scalastyle:on

  /**
   * Actual preparation and setup of the outbound WebSocket [[Route]].
   *
   * @param args the [[OutSocketArgs]]
   * @param terminateConsumer termination logic for current websocket consumer.
   * @param cfg the implicit [[AppCfg]] to use
   * @param as the implicit untyped [[ActorSystem]] to use
   * @param mat the implicit [[Materializer]] to use
   * @param ec the implicit [[ExecutionContext]] to use
   * @return the WebSocket [[Route]]
   */
  private[this] def prepareOutboundWebSocket(
      args: OutSocketArgs
  )(terminateConsumer: () => Future[Done])(
      implicit cfg: AppCfg,
      as: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext
  ): Route = {
    val commitHandlerRef =
      if (args.autoCommit) None
      else Some(as.spawn(CommitStackHandler.commitStack, args.clientId.value))

    val messageParser = args.socketPayload match {
      case JsonPayload => jsonMessageToWsCommit
      case AvroPayload => avroMessageToWsCommit
    }

    val sink   = prepareSink(messageParser, commitHandlerRef)
    val source = kafkaSource(args, commitHandlerRef)

    handleWebSocketMessages {
      Flow
        .fromSinkAndSourceCoupledMat(sink, source)(Keep.both)
        .watchTermination() { (m, f) =>
          val termination = for {
            done <- f
            _    <- terminateConsumer()
          } yield done

          termination.onComplete {
            case scala.util.Success(_) =>
              m._2.drainAndShutdown(Future.successful(Done))
              logger.info(s"Consumer client has disconnected.")

            case scala.util.Failure(e) =>
              m._2.drainAndShutdown(Future.successful(Done))
              logger.error("Disconnection failure", e)
          }
        }
    }
  }

  /**
   * Prepares the appropriate Sink to use for incoming messages on the outbound
   * socket. The Sink is set up based on the desire to auto-commit or not.
   *
   * - If the client requests auto-commit, all incoming messages are ignored.
   * - If the client disables auto-commit, the Sink accepts JSON representation
   *   of [[WsCommit]] messages. These are then passed on to an Actor with
   *   [[CommitStackHandler]] behaviour.
   *
   * @param messageParser parser to use for decoding incoming messages.
   * @param aref          an optional [[ActorRef]] to a [[CommitStackHandler]].
   * @return a [[Sink]] for consuming [[Message]]s
   * @see [[CommitStackHandler.commitStack]]
   */
  private[this] def prepareSink(
      messageParser: Flow[Message, WsCommit, NotUsed],
      aref: Option[ActorRef[CommitStackHandler.CommitProtocol]]
  ): Sink[Message, _] =
    aref
      .map { ar =>
        messageParser
          .map(wc => CommitStackHandler.Commit(wc))
          .to(
            ActorSink.actorRef[CommitStackHandler.CommitProtocol](
              ref = ar,
              onCompleteMessage = CommitStackHandler.Stop,
              onFailureMessage = { t: Throwable =>
                logger.error("An error occurred processing commit message", t)
                CommitStackHandler.Continue
              }
            )
          )
      }
      // If no commit handler is defined, incoming messages are ignored.
      .getOrElse(Sink.ignore)

  /**
   * Converts a WebSocket [[Message]] with a JSON String payload into a
   * [[WsCommit]] for down-stream processing.
   *
   * @param mat the Materializer to use
   * @param ec  the ExecutionContext to use
   * @return a [[Flow]] converting [[Message]] to String
   */
  private[this] def jsonMessageToWsCommit(
      implicit mat: Materializer,
      ec: ExecutionContext
  ): Flow[Message, WsCommit, NotUsed] =
    wsMessageToStringFlow
      .recover {
        case t: Throwable =>
          logAndThrow("There was an error processing a JSON message", t)
      }
      .map(msg => parse(msg).flatMap(_.as[WsCommit]))
      .recover {
        case t: Throwable => logAndThrow(s"JSON message could not be parsed", t)
      }
      .collect { case Right(res) => res }

  /**
   * Converts a WebSocket [[Message]] with an Avro payload into a [[WsCommit]]
   * for down-stream processing.
   *
   * @param mat the [[Materializer]] to use
   * @param ec  the [[ExecutionContext]] to use
   * @return a [[Flow]] converting [[Message]] to String
   */
  private[this] def avroMessageToWsCommit(
      implicit mat: Materializer,
      ec: ExecutionContext
  ): Flow[Message, WsCommit, NotUsed] =
    wsMessageToByteStringFlow
      .recover {
        case t: Throwable =>
          logAndThrow("There was an error processing an Avro message", t)
      }
      .map(msg => avroCommitSerde.deserialize(msg.toArray[Byte]))
      .recover {
        case t: Throwable =>
          logAndThrow(s"Avro message could not be deserialised", t)
      }
      .map(WsCommit.fromAvro)

  /**
   * The Kafka [[Source]] where messages are consumed from the topic.
   *
   * @param args the output arguments to pass on to the consumer.
   * @param cfg the application configuration.
   * @param as the actor system to use.
   * @return a [[Source]] producing [[TextMessage]]s for the outbound WebSocket.
   */
  private[this] def kafkaSource(
      args: OutSocketArgs,
      commitHandlerRef: Option[ActorRef[CommitStackHandler.CommitProtocol]]
  )(
      implicit cfg: AppCfg,
      as: ActorSystem
  ): Source[Message, Consumer.Control] = {
    val keyTpe = args.keyType.getOrElse(Formats.NoType)
    val valTpe = args.valType

    type Key = keyTpe.Aux
    type Val = valTpe.Aux

    // Kafka serdes
    implicit val keyDes = keyTpe.deserializer
    implicit val valDes = valTpe.deserializer
    // JSON encoders
    implicit val keyEnc: Encoder[Key] = keyTpe.encoder
    implicit val valEnc: Encoder[Val] = valTpe.encoder
    implicit val recEnc: Encoder[WsConsumerRecord[Key, Val]] =
      wsConsumerRecordEncoder[Key, Val]

    val msgConverter = socketFormatConverter[Key, Val](args)

    if (args.autoCommit) {
      // if auto-commit is enabled, we don't need to handle manual commits.
      WsConsumer
        .consumeAutoCommit[Key, Val](args)
        .map(cr => enrichWithFormatType(args, cr))
        .map(cr => msgConverter(cr))
    } else {
      // if auto-commit is disabled, we need to ensure messages are sent to a
      // commit handler so its offset can be committed manually by the client.
      val sink = commitHandlerRef.map(manualCommitSink).getOrElse(Sink.ignore)

      WsConsumer
        .consumeManualCommit[Key, Val](args)
        .map(cr => enrichWithFormatType(args, cr))
        .alsoTo(sink) // also send each message to the commit handler sink
        .map(cr => msgConverter(cr))
    }
  }

  private[this] def enrichWithFormatType[K, V](
      args: OutSocketArgs,
      cr: WsConsumerRecord[K, V]
  ): WsConsumerRecord[K, V] =
    args.keyType
      .map(kt => cr.withKeyFormatType(kt))
      .getOrElse(cr)
      .withValueFormatType(args.valType)

  /**
   * Helper function that translates a [[WsConsumerRecord]] into the correct
   * type of [[Message]], based on the {{{args}}}.
   *
   * @param args [[OutSocketArgs]] for building the outbound WebSocket.
   * @param recEnc JSON encoder for [[WsConsumerRecord]]
   * @tparam K the key type
   * @tparam V the value type
   * @return the data as a [[Message]].
   */
  private[this] def socketFormatConverter[K, V](
      args: OutSocketArgs
  )(
      implicit recEnc: Encoder[WsConsumerRecord[K, V]]
  ): WsConsumerRecord[K, V] => Message =
    args.socketPayload match {
      case AvroPayload =>
        cr =>
          val byteString = ByteString(
            avroConsumerRecordSerde
              .serialize(args.topic.value, cr.toAvroRecord[K, V])
          )
          BinaryMessage(byteString)
      case JsonPayload =>
        cr => TextMessage(cr.asJson.printWith(noSpaces))
    }

  /**
   * Builds a Flow that sends consumed [[WsConsumerRecord]]s to the relevant
   * instance of [[CommitStackHandler]] actor Sink, which stashed the record so
   * that its offset can be committed later.
   *
   * @param ref the [[ActorRef]] for the [[CommitStackHandler]]
   * @tparam K the type of the record key
   * @tparam V the type of the record value
   * @return a commit [[Sink]] for [[WsConsumerRecord]] messages
   */
  private[this] def manualCommitSink[K, V](
      ref: ActorRef[CommitStackHandler.CommitProtocol]
  ): Sink[WsConsumerRecord[K, V], NotUsed] = {
    Flow[WsConsumerRecord[K, V]]
      .map(rec => CommitStackHandler.Stash(rec))
      .to(
        ActorSink.actorRef[CommitStackHandler.CommitProtocol](
          ref = ref,
          onCompleteMessage = CommitStackHandler.Continue,
          onFailureMessage = _ => CommitStackHandler.Continue
        )
      )
  }

}
