//package shield.actors.middleware
//
//class BucketRateLimiterSpec {
//
//}

package shield.actors.middleware

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import org.scalatest.{MustMatchers, WordSpecLike}
import shield.actors.{DownstreamRequest, ForwardRequestCmd, ForwardResponseCmd, ResponseDetails}
import shield.config.Settings
import shield.proxying.FailBalancer
import shield.routing._
import shield.kvstore.MemoryStore
import spray.http.HttpHeaders.RawHeader
import spray.http._
import scala.concurrent._
import ExecutionContext.Implicits.global

class BucketRateLimiterSpec extends TestKit(ActorSystem("testSystem"))
  with WordSpecLike
  with MustMatchers
  with ImplicitSender {

  "BucketRateLimiter middleware actor" must {
    val stage = "myStage"
    val settings = Settings(system)
    val getEndpoint = EndpointTemplate(HttpMethods.GET, Path("/foobar"))
    val routingDestination = RoutingDestination(getEndpoint, List(), List(), FailBalancer)

    def httpRequest(headers: List[HttpHeader]): HttpRequest = HttpRequest(HttpMethods.GET, "/v4/mobile/stores", headers)

    def forwardResponseCmd(response: HttpResponse) = {
      ForwardResponseCmd(
        stage,
        ResponseDetails(
          settings.DefaultServiceLocation,
          settings.LocalServiceName,
          getEndpoint,
          None,
          response)
      )
    }

    "reply with Too Many Requests if limit exceeded" in {
      var time = 1
      val store = new MemoryStore("b", 1000, 1000, 1000){
        override def getMillis() = time
      }
      val actor = TestActorRef(BucketRateLimiter.props("a", "", 1, 1, store, settings.DefaultServiceLocation))
      val req = httpRequest(List(RawHeader("client-address", "1.1.1.1")))

      actor ! DownstreamRequest(stage, routingDestination, req)
      expectMsg(ForwardRequestCmd(stage, req))

      actor ! DownstreamRequest(stage, routingDestination, req)
      expectMsg(forwardResponseCmd(HttpResponse(StatusCodes.TooManyRequests)))
    }

    "succeed after waiting for a rate-limit to expire" in {
      var time = 1
      val store = new MemoryStore("b", 1000, 1000, 1000){
        override def getMillis() = time
      }
      val actor = TestActorRef(BucketRateLimiter.props("a", "", 1, 1, store, settings.DefaultServiceLocation))
      val req = httpRequest(List(RawHeader("client-address", "1.1.1.1")))

      actor ! DownstreamRequest(stage, routingDestination, req)
      expectMsg(ForwardRequestCmd(stage, req))

      actor ! DownstreamRequest(stage, routingDestination, req)
      expectMsg(forwardResponseCmd(HttpResponse(StatusCodes.TooManyRequests)))

      time = time + 2000
      actor ! DownstreamRequest(stage, routingDestination, req)
      expectMsg(ForwardRequestCmd(stage, req))
    }



    "succeed if requests spaced out to not hit limit" in {
      var time = 1
      val store = new MemoryStore("b", 1000, 1000, 1000){
        override def getMillis() = time
      }
      val actor = TestActorRef(BucketRateLimiter.props("a", "", 1, 1, store, settings.DefaultServiceLocation))
      val req = httpRequest(List(RawHeader("client-address", "1.1.1.1")))

      actor ! DownstreamRequest(stage, routingDestination, req)
      expectMsg(ForwardRequestCmd(stage, req))

      time = time + 1000
      actor ! DownstreamRequest(stage, routingDestination, req)
      expectMsg(ForwardRequestCmd(stage, req))
    }

    "succeed when another host is limited" in {
      var time = 1
      val store = new MemoryStore("b", 1000, 1000, 1000){
        override def getMillis() = time
      }
      val actor = TestActorRef(BucketRateLimiter.props("a", "", 1, 1, store, settings.DefaultServiceLocation))
      val req1 = httpRequest(List(RawHeader("client-address", "1.1.1.1")))
      val req2 = httpRequest(List(RawHeader("client-address", "2.2.2.2")))

      actor ! DownstreamRequest(stage, routingDestination, req1)
      expectMsg(ForwardRequestCmd(stage, req1))

      actor ! DownstreamRequest(stage, routingDestination, req1)
      expectMsg(forwardResponseCmd(HttpResponse(StatusCodes.TooManyRequests)))

      actor ! DownstreamRequest(stage, routingDestination, req2)
      expectMsg(ForwardRequestCmd(stage, req2))
    }

    "reply with Too Many Requests when another host is limited" in {
      var time = 1
      val store = new MemoryStore("b", 1000, 1000, 1000){
        override def getMillis() = time
      }
      val actor = TestActorRef(BucketRateLimiter.props("a", "", 1, 1, store, settings.DefaultServiceLocation))
      val req1 = httpRequest(List(RawHeader("client-address", "1.1.1.1")))
      val req2 = httpRequest(List(RawHeader("client-address", "2.2.2.2")))

      actor ! DownstreamRequest(stage, routingDestination, req1)
      expectMsg(ForwardRequestCmd(stage, req1))

      actor ! DownstreamRequest(stage, routingDestination, req1)
      expectMsg(forwardResponseCmd(HttpResponse(StatusCodes.TooManyRequests)))

      actor ! DownstreamRequest(stage, routingDestination, req2)
      expectMsg(ForwardRequestCmd(stage, req2))

      actor ! DownstreamRequest(stage, routingDestination, req2)
      expectMsg(forwardResponseCmd(HttpResponse(StatusCodes.TooManyRequests)))
    }

    "succeed when two hosts' rate-limits expire" in {
      var time = 1
      val store = new MemoryStore("b", 1000, 1000, 1000){
        override def getMillis() = time
      }
      val actor = TestActorRef(BucketRateLimiter.props("a", "", 1, 1, store, settings.DefaultServiceLocation))
      val req1 = httpRequest(List(RawHeader("client-address", "1.1.1.1")))
      val req2 = httpRequest(List(RawHeader("client-address", "2.2.2.2")))

      actor ! DownstreamRequest(stage, routingDestination, req1)
      expectMsg(ForwardRequestCmd(stage, req1))

      actor ! DownstreamRequest(stage, routingDestination, req1)
      expectMsg(forwardResponseCmd(HttpResponse(StatusCodes.TooManyRequests)))

      actor ! DownstreamRequest(stage, routingDestination, req2)
      expectMsg(ForwardRequestCmd(stage, req2))

      actor ! DownstreamRequest(stage, routingDestination, req2)
      expectMsg(forwardResponseCmd(HttpResponse(StatusCodes.TooManyRequests)))

      time = time + 1000
      actor ! DownstreamRequest(stage, routingDestination, req1)
      expectMsg(ForwardRequestCmd(stage, req1))

      time = time + 1000
      actor ! DownstreamRequest(stage, routingDestination, req2)
      expectMsg(ForwardRequestCmd(stage, req2))
    }
  }
}
