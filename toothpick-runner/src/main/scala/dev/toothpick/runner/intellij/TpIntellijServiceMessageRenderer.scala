package dev.toothpick.runner.intellij

import dev.toothpick.proto.api.{
  TpTest,
  TpTestClassLocation,
  TpTestFileLocation,
  TpTestGroup,
  TpTestLocation,
  TpTestNode,
  TpTestSuite
}
import dev.toothpick.runner.TpRunnerModels._

object TpIntellijServiceMessageRenderer {
  def escape(str: String): String = {
    str
      .replace("|", "||")
      .replace("'", "|'")
      .replace("\n", "|n")
      .replace("\r", "|r")
      .replace("[", "|[")
      .replace("]", "|]")
  }

  def unescape(str: String): String = {
    str
      .replace("||", "|")
      .replace("|'", "'")
      .replace("|n", "\n")
      .replace("|r", "\r")
      .replace("|[", "[")
      .replace("|]", "]")
  }

  def render(name: String, attributes: Map[String, String]): String = {
    attributes
      .map { case (k, v) =>
        s"$k='${escape(v)}'"
      }
      .mkString(s"##teamcity[$name ", " ", "]")
  }

  def renderUnescaped(name: String, attributes: Map[String, String]): String = {
    attributes
      .map { case (k, v) =>
        s"$k='$v'"
      }
      .mkString(s"##teamcity[$name ", " ", "]")
  }

  def toLocationHint(location: TpTestLocation): String = {
    location match {
      case TpTestClassLocation(className, fileName, lineNumber) =>
        s"scalatest://LineInFile:$className:$fileName:${lineNumber}TestName:_"

      case TpTestFileLocation(filePath, lineNumber) =>
        s"file://$filePath:$lineNumber"
    }
  }

  def renderStartEvent(node: TpTestNode): String = {
    node match {
      case test: TpTest =>
        render(
          "testStarted",
          Map(
            "name" -> test.name,
            "captureStandardOutput" -> "false",
            "nodeId" -> test.id.toString,
            "parentNodeId" -> test.parentId.toString
          ) ++ test.location
            .map { loc =>
              Map(
                "locationHint" -> toLocationHint(loc)
              )
            }
            .getOrElse(Map.empty)
        )

      case group: TpTestGroup =>
        render(
          "testSuiteStarted",
          Map(
            "name" -> s"${group.name}${group.childNamePrefix.fold("")(p => " " + p)}",
            "captureStandardOutput" -> "false",
            "nodeId" -> group.id.toString,
            "parentNodeId" -> group.parentId.toString
          ) ++ group
            .location
            .map { loc =>
              Map(
                "locationHint" -> toLocationHint(loc)
              )
            }
            .getOrElse(Map.empty)
        )

      case suite: TpTestSuite =>
        val prefix = if (suite.duplicateSeq == 0) "" else s"(${suite.duplicateSeq}) "
        render(
          "testSuiteStarted",
          Map(
            "name" -> s"$prefix${suite.name.drop(suite.name.lastIndexOf(".") + 1)}",
            "locationHint" -> s"scalatest://TopOfClass:${suite.name}TestName:_",
            "captureStandardOutput" -> "false",
            "nodeId" -> suite.id.toString,
            "parentNodeId" -> ROOT_NODE_ID.toString
          )
        )

      case _ => ???
    }
  }
}
