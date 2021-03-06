import Dependencies._
import Settings._
import net.scalytica.sbt.plugin.DockerTasksPlugin
import sbtrelease.ReleaseStateTransformations._

import scala.language.postfixOps

// scalastyle:off

name := "kafka-websocket-proxy"

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies, // : ReleaseStep
  inquireVersions,           // : ReleaseStep
  runClean,                  // : ReleaseStep
  runTest,                   // : ReleaseStep
  setReleaseVersion,         // : ReleaseStep
  commitReleaseVersion,      // : ReleaseStep, performs the initial git checks
  tagRelease,                // : ReleaseStep
  setNextVersion,            // : ReleaseStep
  commitNextVersion,         // : ReleaseStep
  pushChanges                // : ReleaseStep, also checks that an upstream branch is properly configured
)

lazy val root = (project in file("."))
  .enablePlugins(DockerTasksPlugin)
  .settings(BaseSettings: _*)
  .settings(NoPublish)
  .aggregate(avro, server)

lazy val avro = (project in file("avro"))
  .settings(BaseSettings: _*)
  .settings(NoPublish)
  .settings(resolvers ++= Dependencies.Resolvers)
  .settings(scalastyleFailOnWarning := true)
  .settings(
    coverageExcludedPackages := "<empty>;net.scalytica.kafka.wsproxy.avro.*;"
  )
  .settings(libraryDependencies ++= Avro.All)
  .settings(libraryDependencies += Testing.ScalaTest % Test)
  .settings(libraryDependencies += Logging.Slf4jNop % Test)
  .settings(dependencyOverrides ++= Overrides.Deps: _*)

lazy val server = (project in file("server"))
  .enablePlugins(JavaServerAppPackaging, DockerPlugin)
  .settings(NoPublish)
  .settings(BaseSettings: _*)
  .settings(dockerSettings(8078))
  .settings(scalastyleFailOnWarning := true)
  .settings(libraryDependencies ++= Config.All)
  .settings(libraryDependencies ++= Circe.All)
  .settings(libraryDependencies ++= Logging.All)
  .settings(
    libraryDependencies ++= Seq(
      Akka.Actor,
      Akka.ActorTyped,
      Akka.Slf4j,
      Akka.Stream,
      Akka.StreamTyped,
      Akka.Http,
      Akka.AkkaStreamKafka,
      Avro.Avro4sKafka,
      Kafka.Clients,
      ConfluentKafka.AvroSerializer,
      ConfluentKafka.MonitoringInterceptors,
      Logging.Log4jOverSlf4j         % Test,
      Logging.JulToSlf4j             % Test,
      Testing.ScalaTest              % Test,
      Testing.EmbeddedKafka          % Test,
      Testing.EmbeddedSchemaRegistry % Test,
      Testing.AkkaTestKit            % Test,
      Testing.AkkaTypedTestKit       % Test,
      Testing.AkkaHttpTestKit        % Test,
      Testing.AkkaStreamTestKit      % Test,
      Testing.AkkaStreamKafkaTestKit % Test,
      Testing.Scalactic              % Test
    )
  )
  .settings(dependencyOverrides ++= Overrides.Deps: _*)
  .dependsOn(avro)
