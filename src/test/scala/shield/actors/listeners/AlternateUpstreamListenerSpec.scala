package shield.actors.listeners

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.scalatest._
import shield.actors.config.{ProxyState, WeightedProxyState}
import shield.actors.{Middleware, RequestProcessorCompleted, ResponseCompleted, ResponseDetails}
import shield.config.{HttpServiceLocation, ServiceLocation, Swagger2ServiceType}
import shield.actors.{RequestProcessorCompleted, ResponseCompleted, ResponseDetails}
import shield.config.{ServiceLocation, Settings, Swagger2ServiceType}
import shield.proxying._
import shield.routing._
import spray.http._
import spray.json.{JsObject, JsString}

import scala.concurrent.duration._

class AlternateUpstreamListenerSpec extends TestKit(ActorSystem("testSystem"))
with WordSpecLike
with MustMatchers
with BeforeAndAfterEach
with BeforeAndAfterAll
with ImplicitSender {

  val foobarTemplate = EndpointTemplate(HttpMethods.GET, Path("/foo/bar"))
  val fizzbuzzTemplate = EndpointTemplate(HttpMethods.GET, Path("/fizz/buzz"))
  val endpoints = Map(foobarTemplate -> EndpointDetails.empty)
  val location = Settings(system).DefaultServiceLocation
  def defaultState(proxy: ActorRef) = WeightedProxyState(1, ProxyState("altUpstream", "0.0.0", Swagger2ServiceType, proxy, endpoints, healthy=true, status="serving", Set.empty))

  "AlternateUpstream" should {
    val hostUri = "http://localhost"
    val hostLocation = HttpServiceLocation(hostUri)
    "send the completed diff to the target" in {
      val target = TestProbe()
      val alternateUpstream = TestProbe()

      val altListener = TestActorRef(new AlternateUpstream("1", location, hostUri, "swagger2", 1, target.ref) {
        override def startingState() = defaultState(alternateUpstream.ref)
      })

      val request = HttpRequest(uri = Uri("/foo/bar"))
      val completed = ResponseCompleted(request, ResponseDetails(HttpServiceLocation(Uri(hostUri)), "defaultUpstream", foobarTemplate, None, HttpProxyLogic.scrubResponse(HttpResponse(entity=HttpEntity(JsObject("groovy" -> JsString("cool")).toString)))))
      val c = RequestProcessorCompleted(completed, 0, Map[String, Long]())

      altListener ! c

      alternateUpstream.expectMsg(ProxyRequest(foobarTemplate, request))
      alternateUpstream.reply(ProxiedResponse(hostLocation, "altUpstream", foobarTemplate, Set.empty, HttpResponse(StatusCodes.Forbidden, entity=HttpEntity(JsObject("groovy" -> JsString("awesome")).toString))))

      target.msgAvailable mustBe true
      val diff : ComparisonDiffFile = target.receiveOne(1.second).asInstanceOf[ComparisonDiffFile]
      println(diff.fileName)
      println(new String(diff.contents))
    }

    "not send a diff to the target if unable to route to a destination" in {
      val target = TestProbe()
      val alternateUpstream = TestProbe()

      val altListener = TestActorRef(new AlternateUpstream("2", location, hostUri, "swagger2", 1, target.ref) {
        override def startingState() = defaultState(alternateUpstream.ref)
      })

      val request = HttpRequest(uri = Uri("/fizz/buzz"))
      val completed = ResponseCompleted(request, ResponseDetails(HttpServiceLocation(Uri(hostUri)), "defaultUpstream", fizzbuzzTemplate, None, HttpProxyLogic.scrubResponse(HttpResponse())))
      val c = RequestProcessorCompleted(completed, 0, Map[String, Long]())

      altListener ! c

      alternateUpstream.msgAvailable mustBe false
      target.msgAvailable mustBe false
    }

    "not send to buffer if the responses are the same" in {
      val target = TestProbe()
      val alternateUpstream = TestProbe()

      val altListener = TestActorRef(new AlternateUpstream("3", location, hostUri, "swagger2", 1, target.ref) {
        override def startingState() = defaultState(alternateUpstream.ref)
      })

      val request = HttpRequest(uri = Uri("/foo/bar"))
      val completed = ResponseCompleted(request, ResponseDetails(HttpServiceLocation(Uri(hostUri)), "defaultUpstream", foobarTemplate, None, HttpProxyLogic.scrubResponse(HttpResponse(status=StatusCodes.Forbidden))))
      val c = RequestProcessorCompleted(completed, 0, Map[String, Long]())

      altListener ! c

      alternateUpstream.expectMsg(ProxyRequest(foobarTemplate, request))
      alternateUpstream.reply(ProxiedResponse(hostLocation, "altUpstream", foobarTemplate, Set.empty, HttpResponse(StatusCodes.Forbidden)))

      target.msgAvailable mustBe false
    }

    "respect the frequency at which alternate requests are sent" in {
      val target = TestProbe()
      val alternateUpstream = TestProbe()

      val altListener = TestActorRef(new AlternateUpstream("4", location, hostUri, "swagger2", 3, target.ref) {
        override def startingState() = defaultState(alternateUpstream.ref)
      })

      val request = HttpRequest(uri = Uri("/foo/bar"))
      val completed = ResponseCompleted(request, ResponseDetails(HttpServiceLocation(Uri(hostUri)), "defaultUpstream", foobarTemplate, None, HttpProxyLogic.scrubResponse(HttpResponse())))
      val c = RequestProcessorCompleted(completed, 0, Map[String, Long]())

      altListener ! c
      alternateUpstream.msgAvailable mustBe false
      target.msgAvailable mustBe false

      altListener ! c
      alternateUpstream.msgAvailable mustBe false
      target.msgAvailable mustBe false

      altListener ! c

      alternateUpstream.expectMsg(ProxyRequest(foobarTemplate, request))
      alternateUpstream.reply(ProxiedResponse(hostLocation, "altUpstream", foobarTemplate, Set.empty, HttpResponse(StatusCodes.Forbidden)))

      target.msgAvailable mustBe true
    }

    "only use routable requests when calculating frequency" in {
      val target = TestProbe()
      val alternateUpstream = TestProbe()

      val altListener = TestActorRef(new AlternateUpstream("5", location, hostUri, "swagger2", 1, target.ref) {
        override def startingState() = defaultState(alternateUpstream.ref)
      })

      val noRouteRequest = HttpRequest(uri = Uri("/fizz/buzz"))
      val noRouteCompleted = ResponseCompleted(noRouteRequest, ResponseDetails(HttpServiceLocation(Uri(hostUri)), "defaultUpstream", fizzbuzzTemplate, None, HttpProxyLogic.scrubResponse(HttpResponse())))
      val noRouteC = RequestProcessorCompleted(noRouteCompleted,0, Map[String, Long]())

      val request = HttpRequest(uri = Uri("/foo/bar"))
      val completed = ResponseCompleted(request, ResponseDetails(HttpServiceLocation(Uri(hostUri)), "defaultUpstream", foobarTemplate, None, HttpProxyLogic.scrubResponse(HttpResponse())))
      val c = RequestProcessorCompleted(completed, 0, Map[String, Long]())

      altListener ! noRouteC
      target.msgAvailable mustBe false

      altListener ! c

      alternateUpstream.expectMsg(ProxyRequest(foobarTemplate, request))
      alternateUpstream.reply(ProxiedResponse(hostLocation, "altUpstream", foobarTemplate, Set.empty, HttpResponse(StatusCodes.Forbidden)))

      target.msgAvailable mustBe true
    }
  }
}
