package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import net.scalytica.kafka.wsproxy.Configuration.AppCfg

import scala.concurrent.{ExecutionContext, Future}

object Server extends App with ServerRoutes {

  // scalastyle:off
  println(
    """
      |               _   __         __  _
      |              | | / /        / _|| |
      |              | |/ /   __ _ | |_ | | __ __ _
      |              |    \  / _` ||  _|| |/ // _` |
      |              | |\  \| (_| || |  |   <| (_| |
      |              \_| \_/ \__,_||_|_____\_\\__,_|
      | _    _        _      _____               _          _
      || |  | |      | |    /  ___|             | |        | |
      || |  | |  ___ | |__  \ `--.   ___    ___ | | __ ___ | |_
      || |/\| | / _ \| '_ \  `--. \ / _ \  / __|| |/ // _ \| __|
      |\  /\  /|  __/| |_) |/\__/ /| (_) || (__ |   <|  __/| |_
      | \/  \/  \___||_.__/ \____/  \___/  \___||_|\_\\___| \__|
      |               _____
      |              | ___ \
      |              | |_/ /_ __  ___ __  __ _   _
      |              |  __/| '__|/ _ \\ \/ /| | | |
      |              | |   | |  | (_) |>  < | |_| |
      |              \_|   |_|   \___//_/\_\ \__, |
      |                                       __/ |
      |                                      |___/
      |
      |""".stripMargin
    // scalastyle:on
  )

  val config = Configuration.loadTypesafeConfig()

  implicit val cfg: AppCfg            = Configuration.loadConfig(config)
  implicit val sys: ActorSystem       = ActorSystem("kafka-ws-proxy", config)
  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val ctx: ExecutionContext  = sys.dispatcher

  private[this] val port = cfg.server.port

  val (sessionHandlerStream, routes) = wsProxyRoutes

  val ctrl = sessionHandlerStream.run()

  /** Bind to network interface and port, starting the server */
  val bindingFuture = Http().bindAndHandle(
    handler = routes,
    interface = "localhost",
    port = port
  )

  private[this] def shutdown(): Unit = {
    ctrl.drainAndShutdown(
      // scalastyle:off
      Future.successful(println("Session data consumer shutdown."))
      // scalastyle:on
    )
    // scalastyle:on

    /** Unbind from the network interface and port, shutting down the server. */
    bindingFuture.flatMap(_.unbind()).onComplete(_ => sys.terminate())
  }

  scala.sys.addShutdownHook {
    shutdown()
  }

  // scalastyle:off
  println(
    s"""Server online at http://localhost:$port/ ..."""
  )
  // scalastyle:on

}
