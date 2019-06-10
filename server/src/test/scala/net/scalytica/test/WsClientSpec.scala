package net.scalytica.test

import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.{
  AvroConsumerRecord,
  AvroProducerRecord,
  AvroProducerResult
}
import net.scalytica.kafka.wsproxy.codecs.WsProxyAvroSerde
import net.scalytica.kafka.wsproxy.models.Formats.FormatType
import org.scalatest.{MustMatchers, Suite}

trait WsClientSpec extends ScalatestRouteTest with MustMatchers { self: Suite =>

  def avroProducerRecordSerde(
      implicit schemaRegistryPort: Int
  ): WsProxyAvroSerde[AvroProducerRecord] =
    WsProxyAvroSerde[AvroProducerRecord](registryConfig)

  implicit def avroProducerResultSerde(
      implicit schemaRegistryPort: Int
  ): WsProxyAvroSerde[AvroProducerResult] =
    WsProxyAvroSerde[AvroProducerResult](registryConfig)

  implicit def avroConsumerRecordSerde(
      implicit schemaRegistryPort: Int
  ): WsProxyAvroSerde[AvroConsumerRecord] =
    WsProxyAvroSerde[AvroConsumerRecord](registryConfig)

  /**
   *
   * @param uri
   * @param routes
   * @param body
   * @param wsClient
   * @tparam T
   * @return
   */
  private[this] def defaultRouteCheck[T](uri: String, routes: Route)(
      body: => T
  )(
      implicit wsClient: WSProbe
  ) =
    WS(uri, wsClient.flow) ~> routes ~> check(body)

  /**
   *
   * @param uri
   * @param routes
   * @param creds
   * @param body
   * @param wsClient
   * @tparam T
   * @return
   */
  private[this] def secureRouteCheck[T](
      uri: String,
      routes: Route,
      creds: BasicHttpCredentials
  )(
      body: => T
  )(
      implicit wsClient: WSProbe
  ) =
    WS(uri, wsClient.flow) ~> addCredentials(creds) ~> routes ~> check(body)

  // scalastyle:off
  /**
   *
   * @param baseUri
   * @param routes
   * @param keyType
   * @param basicCreds
   * @param body
   * @param wsClient
   * @tparam T the return type of the body function
   * @tparam M the type of messages
   * @return
   */
  def checkWebSocket[T, M](
      baseUri: String,
      routes: Route,
      keyType: Option[FormatType],
      basicCreds: Option[BasicHttpCredentials] = None
  )(body: => T)(implicit wsClient: WSProbe): T = {
    val uri = keyType.fold(baseUri)(kt => baseUri + s"&keyType=${kt.name}")

    basicCreds
      .map(c => secureRouteCheck(uri, routes, c)(body))
      .getOrElse(defaultRouteCheck(uri, routes)(body))
  }
  // scalastyle:on
}