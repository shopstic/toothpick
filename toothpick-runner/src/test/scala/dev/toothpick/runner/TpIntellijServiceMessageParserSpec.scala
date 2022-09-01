package dev.toothpick.runner

import better.files.Resource
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromStream, JsonValueCodec}
import dev.toothpick.runner.intellij.TpIntellijServiceMessageParser
import dev.toothpick.runner.intellij.TpIntellijServiceMessageParser.TeamCityServiceMessage
import zio.test.Assertion._
import zio.test.{DefaultRunnableSpec, _}

object TpIntellijServiceMessageParserSpec extends DefaultRunnableSpec {
  implicit val messageJsonCodec: JsonValueCodec[TeamCityServiceMessage] = {
    import com.github.plokhotnyuk.jsoniter_scala.macros._
    JsonCodecMaker.make[TeamCityServiceMessage]
  }

  private def parseAndCompareFixture(number: Int) = {
    test(s"should parse sample $number") {
      val raw = Resource.getAsString(s"fixtures/team-city/$number-raw.txt").trim
      val expected =
        readFromStream[TeamCityServiceMessage](Resource.getAsStream(s"fixtures/team-city/$number-expected.json"))
      val parsed = TpIntellijServiceMessageParser.parse(raw)

      assert(parsed)(equalTo(Right(expected)))
    }
  }

  // noinspection TypeAnnotation
  override def spec = suite("TpTeamCityServiceMessageParserSpec")(
    parseAndCompareFixture(1)
  )
}
