package shield.actors.listeners

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.WordSpecLike
import org.specs2.matcher.MustMatchers
import shield.config.{DomainSettings, Settings}
import spray.http.HttpHeaders.RawHeader
import spray.http.HttpRequest
import spray.json.JsString

class LogCollectorSpec extends TestKit(ActorSystem("testSystem"))
// Using the ImplicitSender trait will automatically set `testActor` as the sender
with ImplicitSender
with WordSpecLike
with MustMatchers {

  import akka.testkit.TestActorRef
  val settings = Settings(system)
  val domainSettings = new DomainSettings(settings.config.getConfigList("shield.domains").get(0), system)

  val actorRef = TestActorRef(new LogCollector("1",domainSettings,Seq[ActorRef](),5))
  val actor = actorRef.underlyingActor

  "LogCollector" should {
    "Extracts headers and adds them to access logs" in {
      val request = HttpRequest().withHeaders(
        RawHeader("sample", "header"),
        RawHeader("test", "test1"),
        RawHeader("test2", "123"),
        RawHeader("test-header-3", "abc"),
        RawHeader("hh", "aaa"),
        RawHeader("hhh", "bbb")
      )

      val extractedHeaders = actor.extractHeaders(request.headers, Set("test-header-3", "hh", "sample", "DNE"))

      extractedHeaders.keys.size must be equalTo 3
      extractedHeaders.get("hh").get must be equalTo JsString("aaa")
      extractedHeaders.get("test-header-3").get must be equalTo JsString("abc")
      extractedHeaders.get("sample").get must be equalTo JsString("header")
    }
  }
}
