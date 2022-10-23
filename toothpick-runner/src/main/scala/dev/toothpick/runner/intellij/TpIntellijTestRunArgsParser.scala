package dev.toothpick.runner.intellij

import better.files.File

import scala.annotation.tailrec
import scala.collection.immutable.Queue

object TpIntellijTestRunArgsParser {
  final case class TpRunnerEnvironment(classpath: List[String], runnerClass: String, systemProperties: List[String])

  final case class TpRunnerSuiteFilter(suiteClassName: String, filterTestNames: Queue[String] = Queue.empty)

  sealed trait TpRunnerContext {
    def environment: TpRunnerEnvironment
    def filters: Queue[TpRunnerSuiteFilter]
  }

  final case class TpScalaTestContext(environment: TpRunnerEnvironment, filters: Queue[TpRunnerSuiteFilter])
      extends TpRunnerContext
  final case class TpZTestContext(environment: TpRunnerEnvironment, filters: Queue[TpRunnerSuiteFilter])
      extends TpRunnerContext

  def parse(args: List[String]): TpRunnerContext = {
    extractClasspath(Queue.empty, args)
  }

  @tailrec
  def extractClasspath(systemProperties: Queue[String], args: List[String]): TpRunnerContext = {
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

  def extractRunner(classpath: String, systemProperties: Queue[String], args: List[String]): TpRunnerContext = {
    args match {
      case runnerClass :: rest if runnerClass.endsWith("ScalaTestRunner") =>
        implicit val ctx: TpRunnerEnvironment =
          TpRunnerEnvironment(parseClasspath(classpath), runnerClass, systemProperties.toList)
        extractScalaTestRunnerArgs(rest)

      case runnerClass :: rest if runnerClass.endsWith("ZTestRunner") =>
        implicit val ctx: TpRunnerEnvironment =
          TpRunnerEnvironment(parseClasspath(classpath), runnerClass, systemProperties.toList)
        extractZTestRunnerArgs(rest)

      case invalid =>
        throw new IllegalArgumentException(s"Missing runner class: $invalid")
    }
  }

  @tailrec
  def extractScalaTestRunnerMultiSuites(suites: Queue[TpRunnerSuiteFilter], args: List[String])(implicit
    ctx: TpRunnerEnvironment
  ): TpScalaTestContext = {
    args match {
      case "-testName" :: _ :: "-s" :: suiteClassName :: tail if suiteClassName.nonEmpty =>
        val suite = TpRunnerSuiteFilter(suiteClassName)
        extractScalaTestRunnerMultiSuites(suites.enqueue(suite), tail)
      case "-s" :: suiteClassName :: tail if suiteClassName.nonEmpty =>
        val suite = TpRunnerSuiteFilter(suiteClassName)
        extractScalaTestRunnerMultiSuites(suites.enqueue(suite), tail)
      case Nil =>
        TpScalaTestContext(ctx, suites)
      case invalid =>
        throw new IllegalArgumentException(s"Invalid multi-suite arguments: $invalid")
    }
  }

  def extractScalaTestRunnerArgs(args: List[String])(implicit ctx: TpRunnerEnvironment): TpScalaTestContext = {
    args match {
      case "-s" :: suiteClassName :: tail if suiteClassName.nonEmpty =>
        extractScalaTestRunnerSingleSuiteTestNames(TpRunnerSuiteFilter(suiteClassName), tail)
      case possibleFileReference :: _ if possibleFileReference.startsWith("@") =>
        extractScalaTestRunnerMultiSuites(
          Queue.empty,
          File(possibleFileReference.drop(1)).lines.filter(_.nonEmpty).toList
        )
      case _ =>
        TpScalaTestContext(ctx, Queue.empty)
    }
  }

  def extractZTestRunnerArgs(args: List[String])(implicit ctx: TpRunnerEnvironment): TpZTestContext = {
    args match {
      case "-s" :: suiteClassName :: tail if suiteClassName.nonEmpty =>
        extractZTestRunnerSingleSuiteTestNames(TpRunnerSuiteFilter(suiteClassName), tail)
      case _ =>
        TpZTestContext(ctx, Queue.empty)
    }
  }

  @tailrec
  def extractZTestRunnerSingleSuiteTestNames(
    suite: TpRunnerSuiteFilter,
    args: List[String]
  )(implicit ctx: TpRunnerEnvironment): TpZTestContext = {
    args match {
      case "-t" :: testName :: tail if testName.nonEmpty =>
        extractZTestRunnerSingleSuiteTestNames(
          suite.copy(filterTestNames = suite.filterTestNames.enqueue(testName)),
          tail
        )
      case _ =>
        TpZTestContext(ctx, Queue(suite))
    }
  }

  @tailrec
  def extractScalaTestRunnerSingleSuiteTestNames(
    suite: TpRunnerSuiteFilter,
    args: List[String]
  )(implicit ctx: TpRunnerEnvironment): TpScalaTestContext = {
    args match {
      case "-testName" :: testName :: tail if testName.nonEmpty =>
        extractScalaTestRunnerSingleSuiteTestNames(
          suite.copy(filterTestNames = suite.filterTestNames.enqueue(testName)),
          tail
        )
      case _ =>
        TpScalaTestContext(ctx, Queue(suite))
    }
  }
}
