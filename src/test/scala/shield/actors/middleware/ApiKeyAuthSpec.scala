package shield.actors.middleware

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import org.scalatest.{MustMatchers, WordSpecLike}
import shield.actors.{DownstreamRequest, ForwardRequestCmd, ForwardResponseCmd, ResponseDetails}
import shield.config.Settings
import shield.proxying.FailBalancer
import shield.routing._
import spray.http.HttpHeaders.RawHeader
import spray.http._

class ApiKeyAuthSpec extends TestKit(ActorSystem("testSystem"))
  with WordSpecLike
  with MustMatchers
  with ImplicitSender {

  "ApiKeyAuthTest middleware actor" must {
    val stage = "myStage"
    val settings = Settings(system)
    val location = settings.DefaultServiceLocation
    val getEndpoint = EndpointTemplate(HttpMethods.GET, Path("/foobar"))
    val routingDestination = RoutingDestination(getEndpoint, List(), List(), FailBalancer)

    def httpRequest(headers: List[HttpHeader]): HttpRequest = HttpRequest(HttpMethods.GET, "/v4/mobile/stores", headers)

    def forwardResponseCmd(response: HttpResponse) = {
      ForwardResponseCmd(
        stage,
        ResponseDetails(
          location,
          settings.LocalServiceName,
          getEndpoint,
          None,
          response)
      )
    }

    "reply with Forbidden when created with bad parameters" in {
      val actor = TestActorRef(ApiKeyAuth.props("", Set(""), true, location))
      actor ! DownstreamRequest(stage, routingDestination, httpRequest(List()))
      expectMsg(forwardResponseCmd(HttpResponse(StatusCodes.Forbidden)))
    }

    "reply with Forbidden when given no headers" in {
      val actor = TestActorRef(ApiKeyAuth.props("pid", Set("BA914464-C559-4F81-A37E-521B830F1634"), true, location))
      actor ! DownstreamRequest(stage, routingDestination, httpRequest(List()))
      expectMsg(forwardResponseCmd(HttpResponse(StatusCodes.Forbidden)))
    }

    "reply with Unauthorized when given an incorrect header" in {
      val actor = TestActorRef(ApiKeyAuth.props("pid", Set("BA914464-C559-4F81-A37E-521B830F1634"), true, location))
      actor ! DownstreamRequest(stage, routingDestination, httpRequest(List(RawHeader("pid","asdasdada"))))
      expectMsg(forwardResponseCmd(HttpResponse(StatusCodes.Unauthorized)))
    }

    "succeed with a downstream request when given the correct header and value" in {
      val request = httpRequest(List(RawHeader("pid","BA914464-C559-4F81-A37E-521B830F1634")))
      val actor = TestActorRef(ApiKeyAuth.props("pid", Set("BA914464-C559-4F81-A37E-521B830F1634"), true, location))
      actor ! DownstreamRequest(stage, routingDestination, request)
      expectMsg(ForwardRequestCmd(stage, request, None))
    }

    "succeed with a downstream request when given a correct but capitalized header" in {
      val request = httpRequest(List(RawHeader("PID","BA914464-C559-4F81-A37E-521B830F1634")))
      val actor = TestActorRef(ApiKeyAuth.props("pid", Set("BA914464-C559-4F81-A37E-521B830F1634"), true, location))
      actor ! DownstreamRequest(stage, routingDestination, request)
      expectMsg(ForwardRequestCmd(stage, request, None))
    }

    "succeed with a downstream request when given a case-insensitive value and case sensitivity is off" in {
      val request = httpRequest(List(RawHeader("pid","ba914464-c559-4f81-a37e-521b830f1634")))
      val actor = TestActorRef(ApiKeyAuth.props("pid", Set("BA914464-C559-4F81-A37E-521B830F1634"), false, location))
      actor ! DownstreamRequest(stage, routingDestination, request)
      expectMsg(ForwardRequestCmd(stage, request, None))
    }

    "reply with Unauthorized when given a case-insensitive value and case sensitivity is on" in {
      val request = httpRequest(List(RawHeader("pid","ba914464-c559-4f81-a37e-521b830f1634")))
      val actor = TestActorRef(ApiKeyAuth.props("pid", Set("BA914464-C559-4F81-A37E-521B830F1634"), true, location))
      actor ! DownstreamRequest(stage, routingDestination, request)
      expectMsg(forwardResponseCmd(HttpResponse(StatusCodes.Unauthorized)))
    }

  }
}
