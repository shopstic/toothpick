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
import dev.toothpick.runner.intellij.TpIntellijServiceMessages.{Attrs, Names}

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
          Names.TEST_STARTED,
          Map(
            Attrs.NAME -> test.name,
            Attrs.CAPTURE_STANDARD_OUTPUT -> "false",
            Attrs.NODE_ID -> test.id.toString,
            Attrs.PARENT_NODE_ID -> test.parentId.toString
          ) ++ test.location
            .map { loc =>
              Map(
                Attrs.LOCATION_HINT -> toLocationHint(loc)
              )
            }
            .getOrElse(Map.empty)
        )

      case group: TpTestGroup =>
        render(
          Names.TEST_SUITE_STARTED,
          Map(
            Attrs.NAME -> s"${group.name}${group.childNamePrefix.fold("")(p => " " + p)}",
            Attrs.CAPTURE_STANDARD_OUTPUT -> "false",
            Attrs.NODE_ID -> group.id.toString,
            Attrs.PARENT_NODE_ID -> group.parentId.toString
          ) ++ group
            .location
            .map { loc =>
              Map(
                Attrs.LOCATION_HINT -> toLocationHint(loc)
              )
            }
            .getOrElse(Map.empty)
        )

      case suite: TpTestSuite =>
        val prefix = if (suite.duplicateSeq == 0) "" else s"(${suite.duplicateSeq}) "
        render(
          Names.TEST_SUITE_STARTED,
          Map(
            Attrs.NAME -> s"$prefix${suite.name.drop(suite.name.lastIndexOf(".") + 1)}",
            Attrs.LOCATION_HINT -> s"scalatest://TopOfClass:${suite.name}TestName:_",
            Attrs.CAPTURE_STANDARD_OUTPUT -> "false",
            Attrs.NODE_ID -> suite.id.toString,
            Attrs.PARENT_NODE_ID -> ROOT_NODE_ID.toString
          )
        )

      case _ => ???
    }
  }
}
