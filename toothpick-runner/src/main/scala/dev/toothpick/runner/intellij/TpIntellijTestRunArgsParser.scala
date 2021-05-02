package dev.toothpick.runner.intellij

import better.files.File

import scala.annotation.tailrec
import scala.collection.immutable.Queue

object TpIntellijTestRunArgsParser {
  final case class TpRunnerContext(classpath: List[String], runnerClass: String, systemProperties: List[String])

  final case class TpRunnerSuiteFilter(suiteClassName: String, filterTestNames: Queue[String] = Queue.empty)

  sealed trait TpRunnerConfig {
    def context: TpRunnerContext
    def suites: Queue[TpRunnerSuiteFilter]
  }

  final case class TpScalaTestConfig(context: TpRunnerContext, suites: Queue[TpRunnerSuiteFilter])
      extends TpRunnerConfig
  final case class TpZTestConfig(context: TpRunnerContext, suites: Queue[TpRunnerSuiteFilter]) extends TpRunnerConfig

  def parse(args: List[String]): TpRunnerConfig = {
    extractClasspath(Queue.empty, args)
  }

  @tailrec
  def extractClasspath(systemProperties: Queue[String], args: List[String]): TpRunnerConfig = {
    args match {
      case systemProp :: rest if systemProp.startsWith("-D") =>
        extractClasspath(systemProperties.enqueue(systemProp.drop(2)), rest)

      case "-classpath" :: classpath :: tail if classpath.nonEmpty =>
        extractRunner(classpath, systemProperties, tail)

      case _ :: tail =>
        extractClasspath(systemProperties, tail)

      case Nil =>
        throw new IllegalArgumentException("Missing classpath")
    }
  }

  private def parseClasspath(classpath: String): List[String] = {
    classpath.split(":").toList
  }

  def extractRunner(classpath: String, systemProperties: Queue[String], args: List[String]): TpRunnerConfig = {
    args match {
      case runnerClass :: rest if runnerClass.endsWith("ScalaTestRunner") =>
        implicit val ctx: TpRunnerContext =
          TpRunnerContext(parseClasspath(classpath), runnerClass, systemProperties.toList)
        extractScalaTestRunnerArgs(rest)

      case runnerClass :: rest if runnerClass.endsWith("ZTestRunner") =>
        implicit val ctx: TpRunnerContext =
          TpRunnerContext(parseClasspath(classpath), runnerClass, systemProperties.toList)
        extractZTestRunnerArgs(rest)

      case invalid =>
        throw new IllegalArgumentException(s"Missing runner class: $invalid")
    }
  }

  @tailrec
  def extractScalaTestRunnerMultiSuites(suites: Queue[TpRunnerSuiteFilter], args: List[String])(implicit
    ctx: TpRunnerContext
  ): TpScalaTestConfig = {
    args match {
      case "-s" :: suiteClassName :: tail if suiteClassName.nonEmpty =>
        val suite = TpRunnerSuiteFilter(suiteClassName)
        extractScalaTestRunnerMultiSuites(suites.enqueue(suite), tail)
      case Nil =>
        TpScalaTestConfig(ctx, suites)
      case invalid =>
        throw new IllegalArgumentException(s"Invalid multi-suite arguments: $invalid")
    }
  }

  def extractScalaTestRunnerArgs(args: List[String])(implicit ctx: TpRunnerContext): TpScalaTestConfig = {
    args match {
      case "-s" :: suiteClassName :: tail if suiteClassName.nonEmpty =>
        extractScalaTestRunnerSingleSuiteTestNames(TpRunnerSuiteFilter(suiteClassName), tail)
      case possibleFileReference :: _ if possibleFileReference.startsWith("@") =>
        extractScalaTestRunnerMultiSuites(
          Queue.empty,
          File(possibleFileReference.drop(1)).lines.filter(_.nonEmpty).toList
        )
      case _ =>
        TpScalaTestConfig(ctx, Queue.empty)
    }
  }

  def extractZTestRunnerArgs(args: List[String])(implicit ctx: TpRunnerContext): TpZTestConfig = {
    args match {
      case "-s" :: suiteClassName :: tail if suiteClassName.nonEmpty =>
        extractZTestRunnerSingleSuiteTestNames(TpRunnerSuiteFilter(suiteClassName), tail)
      case _ =>
        TpZTestConfig(ctx, Queue.empty)
    }
  }

  @tailrec
  def extractZTestRunnerSingleSuiteTestNames(
    suite: TpRunnerSuiteFilter,
    args: List[String]
  )(implicit ctx: TpRunnerContext): TpZTestConfig = {
    args match {
      case "-t" :: testName :: tail if testName.nonEmpty =>
        extractZTestRunnerSingleSuiteTestNames(
          suite.copy(filterTestNames = suite.filterTestNames.enqueue(testName)),
          tail
        )
      case _ =>
        TpZTestConfig(ctx, Queue(suite))
    }
  }

  @tailrec
  def extractScalaTestRunnerSingleSuiteTestNames(
    suite: TpRunnerSuiteFilter,
    args: List[String]
  )(implicit ctx: TpRunnerContext): TpScalaTestConfig = {
    args match {
      case "-testName" :: testName :: tail if testName.nonEmpty =>
        extractScalaTestRunnerSingleSuiteTestNames(
          suite.copy(filterTestNames = suite.filterTestNames.enqueue(testName)),
          tail
        )
      case _ =>
        TpScalaTestConfig(ctx, Queue(suite))
    }
  }
}
