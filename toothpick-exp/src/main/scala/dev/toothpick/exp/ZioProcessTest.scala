package dev.toothpick.exp

import zio.process.{Command, ProcessInput}
import zio.stream.ZStream
import zio.{Chunk, ExitCode, UIO, URIO}

import java.nio.charset.StandardCharsets

object ZioProcessTest extends zio.App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val stdin = s"""
      |
      |for i in `seq 1 3`;
      |do
      |echo "out $$i"
      |>&2 echo "err $$i"
      |sleep 1
      |done
      |
      |exit 123
      |""".stripMargin

    val app = for {
      process <- Command("/bin/bash")
        .stdin(ProcessInput(Some(
          ZStream
            .fromChunk(Chunk.fromArray(stdin.getBytes(StandardCharsets.UTF_8)))
        )))
        .run
      _ <- process
        .stdout
        .linesStream
        .map(l => s"stdout: $l")
        .merge(
          process.stderr.linesStream.map(l => s"stderr: $l")
        )
        .foreach { l =>
          zio.console.putStrLn(l)
        }
      _ <- process.successfulExitCode
    } yield ExitCode(0)

    app.catchAll(e => UIO(println(s"Error: $e")).as(ExitCode(1)))
  }
}
