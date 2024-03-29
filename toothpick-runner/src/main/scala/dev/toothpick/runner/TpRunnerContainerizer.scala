package dev.toothpick.runner

import better.files.File
import com.google.cloud.tools.jib.api._
import com.google.cloud.tools.jib.api.buildplan.{AbsoluteUnixPath, FileEntriesLayer, Platform}
import com.google.cloud.tools.jib.event.events.TimerEvent
import com.google.cloud.tools.jib.registry.credentials.DockerConfigCredentialRetriever
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.fp.zio_ext.ZIOExtensions
import dev.toothpick.runner.intellij.TpIntellijTestRunArgsParser.TpRunnerContext
import eu.timepit.refined.types.string.NonEmptyString
import logstage.Level
import pureconfig.ConfigReader
import zio.clock.Clock
import zio.{RIO, Task, ZIO}

import java.nio.file.Paths
import java.util.function.Consumer

object TpRunnerContainerizer {
  final case class ContainerizerImageCredentials(
    registry: NonEmptyString,
    dockerConfigFile: NonEmptyString
  )

  final case class ContainerizerImageConfig(
    name: NonEmptyString,
    credentials: Option[ContainerizerImageCredentials] = None
  ) {
    def toImage(logger: Consumer[LogEvent]): RegistryImage = {
      val ref = RegistryImage.named(name.value)
      credentials match {
        case Some(creds) =>
          val retriever =
            DockerConfigCredentialRetriever.create(creds.registry.value, Paths.get(creds.dockerConfigFile.value))

          ref.addCredentialRetriever(() => {
            retriever.retrieve(logger)
          })
        case None =>
          ref
      }
    }
  }

  final case class TpRunnerContainerizerConfig(
    targetImage: ContainerizerImageConfig,
    baseImage: ContainerizerImageConfig,
    entrypointPrefix: Vector[String],
    environment: Map[String, String],
    javaOptions: List[String]
  )

  object TpRunnerContainerizerConfig {
    // noinspection TypeAnnotation
    implicit lazy val configReader = {
      import dev.chopsticks.util.config.PureconfigConverters._
      import pureconfig.module.enumeratum._
      ConfigReader[TpRunnerContainerizerConfig]
    }
  }

  // noinspection MatchToPartialFunction
  def containerize(
    context: TpRunnerContext,
    containerizerConfig: TpRunnerContainerizerConfig
  ): RIO[IzLogging with Clock, String] = {
    for {
      logger <- IzLogging.logger
      imageRef <- ZIO.effectSuspend {
        import scala.jdk.CollectionConverters._

        val env = context.environment
        val classpathFiles = env.classpath.map(File(_)).filter(_.exists)

        val jarsLayerBuilder = FileEntriesLayer.builder()
        val classesLayerBuilder = FileEntriesLayer.builder()
        val testClassesLayerBuilder = FileEntriesLayer.builder()
        val classpathListBuilder = List.newBuilder[String]

        classpathFiles.zipWithIndex.foreach { case (file, index) =>
          val name = file.name
          val path = file.path

          val destinationPath =
            if (file.extension.contains(".jar")) {
              val dest = s"/app/jars/$name"
              val _ = jarsLayerBuilder.addEntry(path, AbsoluteUnixPath.get(dest))
              dest
            }
            else if (name == "classes") {
              val destinationPath = s"/app/classes/$index"
              val _ = classesLayerBuilder.addEntryRecursive(path, AbsoluteUnixPath.get(destinationPath))
              destinationPath
            }
            else if (name == "test-classes") {
              val destinationPath = s"/app/test-classes/$index"
              val _ = testClassesLayerBuilder.addEntryRecursive(path, AbsoluteUnixPath.get(destinationPath))
              destinationPath
            }
            else {
              throw new IllegalArgumentException(s"Unsupported classpath item: ${file.toString}")
            }

          val _ = classpathListBuilder += destinationPath
        }

        val entrypoint = containerizerConfig.entrypointPrefix ++ Vector(
          "java"
        ) ++ containerizerConfig.javaOptions ++ env.systemProperties.map(p => s"-D$p") ++ Vector(
          "-cp",
          classpathListBuilder.result().mkString(":")
        ) :+ env.runnerClass

        def handleEvent(event: JibEvent): Unit = {
          event match {
            case log: LogEvent if log.getLevel.compareTo(LogEvent.Level.PROGRESS) <= 0 =>
              val level = log.getLevel match {
                case LogEvent.Level.PROGRESS | LogEvent.Level.LIFECYCLE => Level.Info
                case LogEvent.Level.ERROR => Level.Error
                case LogEvent.Level.WARN => Level.Warn
                case LogEvent.Level.INFO => Level.Info
                case _ => Level.Debug
              }

              val message = log.getMessage

              if (message.nonEmpty) {
                logger.log(level)(s"${message -> "jib-message"}")
              }

            case timer: TimerEvent =>
              logger.debug(
                s"${timer.getState -> "jib-state"} ${timer.getDescription -> "" -> null}"
              )
            case _ =>
          }
        }

        val baseImage = containerizerConfig.baseImage.toImage(handleEvent)
        val targetImage = containerizerConfig.targetImage.toImage(handleEvent)

        val imagePlatforms = {
          import scala.jdk.CollectionConverters._
          Set(new Platform("amd64", "linux"), new Platform("arm64", "linux")).asJava
        }

        Task {
          val builder = Jib
            .from(baseImage)
            .setPlatforms(imagePlatforms)
            .addFileEntriesLayer(jarsLayerBuilder.build())
            .addFileEntriesLayer(classesLayerBuilder.build())
            .addFileEntriesLayer(testClassesLayerBuilder.build())
            .setEntrypoint(entrypoint.asJava)

          val builderWithEnvironment = if (containerizerConfig.environment.nonEmpty)
            builder.setEnvironment(containerizerConfig.environment.asJava)
          else builder

          val containerized =
            builderWithEnvironment.containerize(Containerizer.to(targetImage).addEventHandler(handleEvent))

          s"${containerizerConfig.targetImage.name}@${containerized.getDigest.toString}"
        }
          .log(s"containerize to target: $targetImage")

      }
    } yield imageRef
  }
}
