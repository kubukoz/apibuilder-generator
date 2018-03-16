package scala.models

import scala.generator.ScalaService
import org.scalatest.{ Matchers, FunSpec }

class Play2JsonSpec extends FunSpec with Matchers {

  describe("for models with lists") {

    val json = models.TestHelper.buildJson("""
      "imports": [],
      "headers": [],
      "info": [],
      "unions": [],
      "enums": [],
      "resources": [],
      "attributes": [],
      "models": [
        {
          "name": "content",
          "plural": "contents",
          "attributes": [],
          "fields": [
            { "name": "required_tags", "type": "[string]", "required": true, "attributes": [] },
            { "name": "optional_tags", "type": "[string]", "required": false, "attributes": [] },
            { "name": "data", "type": "map[long]", "required": false, "attributes": [] }
          ]
        }
      ]
    """)

    it("generates valid json readers") {
      val ssd = ScalaService(models.TestHelper.service(json))
      val model = ssd.models.head
      models.TestHelper.assertEqualsFile(
        "/play2-json-spec-model-readers.txt",
        Play2Json(ssd).fieldReaders(model)
      )
    }

  }

  describe("For defaulted fields") {
    val json1 = models.TestHelper.buildJson("""
      "imports": [],
      "headers": [],
      "info": [],
      "unions": [],
      "enums": [],
      "resources": [],
      "attributes": [],
      "models": [
        {
          "name": "a",
          "plural": "as",
          "attributes": [],
          "fields": [
            { "name": "one", "type": "string", "required": true, "default":"adefault", "attributes": [] }
          ]
        }
      ]
    """)

    val json2 = models.TestHelper.buildJson("""
      "imports": [],
      "headers": [],
      "info": [],
      "unions": [],
      "enums": [],
      "resources": [],
      "attributes": [],
      "models": [
        {
          "name": "b",
          "plural": "bs",
          "attributes": [],
          "fields": [
            { "name": "one", "type": "[string]", "required": true, "default":"[]", "attributes": [] },
            { "name": "two", "type": "integer", "required": true, "default":"7", "attributes": [] },
            { "name": "three", "type": "date-iso8601", "required": true, "default":"2008-09-15T15:53:00", "attributes": [] },
            { "name": "four", "type": "boolean", "required": true, "default":"true", "attributes": [] }
          ]
        }
      ]
    """)

    it("generates valid json readers for basic objects") {
      val ssd = ScalaService(models.TestHelper.service(json1))
      val model = ssd.models.head
      Play2Json(ssd).fieldReaders(model) should be (
        """(__ \ "one").readWithDefault[String](Json.parse(""adefault"").as[String]).map { x => new A(one = x) }""".stripMargin)
    }

    it("generates valid json readers for complex objects") {
      val ssd = ScalaService(models.TestHelper.service(json2))
      val model = ssd.models.head
      println(Play2Json(ssd).fieldReaders(model))
      Play2Json(ssd).fieldReaders(model) should be (
        """(
          |  (__ \ "one").readWithDefault[Seq[String]](Json.parse("Nil").as[Seq[String]]) and
          |  (__ \ "two").readWithDefault[Int](Json.parse("7").as[Int]) and
          |  (__ \ "three").readWithDefault[_root_.org.joda.time.LocalDate](Json.parse("new _root_.org.joda.time.LocalDate(2008, 9, 15)").as[_root_.org.joda.time.LocalDate]) and
          |  (__ \ "four").readWithDefault[Boolean](Json.parse("true").as[Boolean])
          |)(B.apply _)""".stripMargin)
    }
  }

  describe("quality schema") {

    lazy val quality = new ScalaService(models.TestHelper.parseFile("/examples/quality.json"))

    describe("plan") {

      lazy val plan = quality.models.find(_.name == "Plan").get

      it("readers") {
        models.TestHelper.assertEqualsFile(
          "/generators/play-2-json-spec-quality-plan-readers.txt",
          Play2Json(quality).readers(plan)
        )
      }

      it("writers") {
        models.TestHelper.assertEqualsFile(
          "/generators/play-2-json-spec-quality-plan-writers.txt",
          Play2Json(quality).writers(plan)
        )
      }
    }

    describe("healthcheck") {

      lazy val healthcheck = quality.models.find(_.name == "Healthcheck").get

      it("readers") {
        models.TestHelper.assertEqualsFile(
          "/generators/play-2-json-spec-quality-healthcheck-readers.txt",
          Play2Json(quality).readers(healthcheck)
        )
      }

      it("writers") {
        models.TestHelper.assertEqualsFile(
          "/generators/play-2-json-spec-quality-healthcheck-writers.txt",
          Play2Json(quality).writers(healthcheck)
        )
      }
    }
  }

}
