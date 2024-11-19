package slack

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json
import slack.models.SlackEvent
import slack.models.slackEventReads

import java.io.{File, FileInputStream}
import scala.io.Source
import scala.util.Try

class JsonMessagesDeserializationTest extends AnyWordSpec with Matchers {

  "JSON message deserialization" can {
    new File("./src/test/resources/json-messages").listFiles().toList.foreach { file =>
      file.getName should {
        "be parsed without an error" in {
          val scalaObject = Json.parse(new FileInputStream(file)).as[SlackEvent]
          val newFileName = file.getAbsolutePath.replace("json-messages", "expected-tostring")
            .replace(".json", ".txt")

          val expectedToString =
            Try(Source.fromFile(new File(newFileName)).mkString).getOrElse("MISSING")
          val actualToString = scalaObject.toString

          actualToString shouldBe(expectedToString)
        }
      }
    }
  }
}
