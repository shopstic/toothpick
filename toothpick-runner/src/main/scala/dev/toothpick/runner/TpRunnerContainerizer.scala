package dev.toothpick.runner

import better.files.File
import com.google.cloud.tools.jib.api._
import com.google.cloud.tools.jib.api.buildplan.{AbsoluteUnixPath, FileEntriesLayer}
import com.google.cloud.tools.jib.event.events.TimerEvent
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.toothpick.runner.intellij.TpIntellijTestRunArgsParser.TpRunnerContext
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.auto._
import logstage.Level
import zio.{RIO, Task}

object TpRunnerContainerizer {
  final case class TpRunnerContainerizerConfig(
    targetImage: NonEmptyString,
    baseImage: NonEmptyString,
    runTimeoutSeconds: PosInt,
    killAfterRunTimeoutSeconds: PosInt,
    javaOptions: List[String]
  )

  //noinspection MatchToPartialFunction
  def containerize(
    context: TpRunnerContext,
    containerizerConfig: TpRunnerContainerizerConfig
  ): RIO[IzLogging, String] = {
    IzLogging.logger.flatMap { logger =>
      Task {
        import scala.jdk.CollectionConverters._

        val env = context.environment
        val classpathFiles = env.classpath.map(File(_))

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

        val entrypoint = Vector(
          "/usr/bin/dumb-init",
          "--",
          "timeout",
          "--signal=15",
          s"--kill-after=${containerizerConfig.killAfterRunTimeoutSeconds}s",
          s"${containerizerConfig.runTimeoutSeconds}s",
          "java"
        ) ++ containerizerConfig.javaOptions ++ env.systemProperties.map(p => s"-D$p") ++ Vector(
          "-cp",
          classpathListBuilder.result().mkString(":")
        ) :+ env.runnerClass

        val targetImageString = containerizerConfig.targetImage.value
        val baseImage = RegistryImage.named(
          containerizerConfig.baseImage
        )
        val targetImage = RegistryImage
          .named(targetImageString)

        val containerized = Jib
          .from(baseImage)
          .addFileEntriesLayer(jarsLayerBuilder.build())
          .addFileEntriesLayer(classesLayerBuilder.build())
          .addFileEntriesLayer(testClassesLayerBuilder.build())
          .setEntrypoint(entrypoint.asJava)
          .containerize(Containerizer.to(targetImage).addEventHandler { (event: JibEvent) =>
            event match {
              case log: LogEvent if log.getLevel.compareTo(LogEvent.Level.PROGRESS) <= 0 =>
                val level = log.getLevel match {
                  case LogEvent.Level.PROGRESS | LogEvent.Level.LIFECYCLE => Level.Info
                  case LogEvent.Level.ERROR => Level.Error
                  case LogEvent.Level.WARN => Level.Warn
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
          })

        s"$targetImageString@${containerized.getDigest.toString}"
      }
    }
  }
}
