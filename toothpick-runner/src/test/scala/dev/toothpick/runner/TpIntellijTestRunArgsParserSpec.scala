package dev.toothpick.runner

import better.files.{File, Resource}
import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, _}
import dev.toothpick.runner.intellij.TpIntellijTestRunArgsParser
import dev.toothpick.runner.intellij.TpIntellijTestRunArgsParser.{
  TpRunnerContext,
  TpRunnerEnvironment,
  TpRunnerSuiteFilter,
  TpScalaTestContext,
  TpZTestContext
}
import zio.{Task, ZManaged}
import zio.test.Assertion._
import zio.test.{DefaultRunnableSpec, _}

import scala.collection.immutable.Queue

//noinspection TypeAnnotation
object TpIntellijTestRunArgsParserSpec extends DefaultRunnableSpec {
  implicit val scalaTestJsonCodec: JsonValueCodec[TpScalaTestContext] = {
    import com.github.plokhotnyuk.jsoniter_scala.macros._
    JsonCodecMaker.make[TpScalaTestContext]
  }

  implicit val zTestJsonCodec: JsonValueCodec[TpZTestContext] = {
    import com.github.plokhotnyuk.jsoniter_scala.macros._
    JsonCodecMaker.make[TpZTestContext]
  }

  def parseAndCompareFixture[Cfg <: TpRunnerContext: JsonValueCodec](number: Int) = {
    test(s"should parse sample $number") {
      val args = Resource.getAsString(s"fixtures/run-args/$number-args.txt").linesIterator.toList
      val expected = readFromStream[Cfg](Resource.getAsStream(s"fixtures/run-args/$number-expected.json"))
      val parsed = TpIntellijTestRunArgsParser.parse(args)

      assert(parsed)(equalTo(expected))
    }
  }

  override def spec = suite("IntellijTestRunArgsParser")(
    parseAndCompareFixture[TpScalaTestContext](1),
    parseAndCompareFixture[TpScalaTestContext](2),
    parseAndCompareFixture[TpZTestContext](3),
    testM("should parse list of suites from a file reference") {
      ZManaged.make {
        Task(File.newTemporaryFile())
      } { file =>
        Task(file.delete(swallowIOExceptions = true)).ignore
      }.use { tempFile =>
        Task {
          tempFile.write(
            """
            |-s
            |foo.bar.baz.Foo
            |-s
            |foo.bar.baz.Boom
            |""".stripMargin
          )

          val args = List(
            "-javaagent:whatever",
            "-Dfile.encoding=UTF-8",
            "-classpath",
            "/foo/bar/baz.jar:/foo/bar/boo.jar",
            "org.jetbrains.plugins.scala.testingSupport.scalaTest.ScalaTestRunner",
            s"@${tempFile.pathAsString}",
            "-showProgressMessages",
            "true"
          )

          val parsed = TpIntellijTestRunArgsParser.parse(args)

          assert(parsed)(equalTo {
            TpScalaTestContext(
              TpRunnerEnvironment(
                classpath = List("/foo/bar/baz.jar", "/foo/bar/boo.jar"),
                runnerClass = "org.jetbrains.plugins.scala.testingSupport.scalaTest.ScalaTestRunner",
                systemProperties = List("file.encoding=UTF-8")
              ),
              filters = Queue(
                TpRunnerSuiteFilter(
                  suiteClassName = "foo.bar.baz.Foo"
                ),
                TpRunnerSuiteFilter(
                  suiteClassName = "foo.bar.baz.Boom"
                )
              )
            )
          })
        }
      }
    }
  )
}
