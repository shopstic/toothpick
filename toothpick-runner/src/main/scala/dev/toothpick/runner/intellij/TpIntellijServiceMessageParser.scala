package dev.toothpick.runner.intellij

import scala.annotation.nowarn

@SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
object TpIntellijServiceMessageParser {
  import fastparse.SingleLineWhitespace._
  import fastparse.{parse => _, _}

  val TC_PREFIX = "##teamcity["

  final case class TeamCityServiceMessage(name: String, attributes: Map[String, String])

  private def parseLine[_: P] = P(TC_PREFIX ~ CharsWhile(_ != ' ').! ~ parseAttributes.rep ~ "]" ~ End)

  private def stringChars(c: Char) = c != '\'' && c != '|'

  private def strChars[_: P] = P(CharsWhile(stringChars))

  private def unicodeEscape[_: P] = P("0x" ~ hexDigit ~ hexDigit ~ hexDigit ~ hexDigit)

  @nowarn("cat=unused")
  private def escape[_: P] = P("|" ~ (CharIn("|'nr[]") | unicodeEscape))

  private def hexDigit[_: P] = P(CharIn("0-9a-fA-F"))

  private def parseAttributes[_: P] =
    P(CharsWhile(c => c != '=' && c != ' ').! ~ "=" ~ "'" ~/ (strChars | escape).rep.! ~ "'")

  def parse(line: String): Either[String, TeamCityServiceMessage] = {
    fastparse.parse(line, parseLine(_), verboseFailures = true) match {
      case Parsed.Success(value, _) =>
        val (name, attributes) = value
        Right(TeamCityServiceMessage(name, attributes.toMap))
      case failure: Parsed.Failure =>
        Left(failure.longMsg)
    }
  }

  def main(args: Array[String]): Unit = {}
}
