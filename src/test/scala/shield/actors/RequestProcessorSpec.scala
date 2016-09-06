package shield.actors

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest._
import shield.akka.helpers.VirtualScheduler
import shield.config.{HttpServiceLocation, ServiceLocation, Settings}
import shield.proxying.{FailBalancer, ProxiedResponse, ProxyBalancer}
import shield.routing._
import spray.http.HttpHeaders.RawHeader
import spray.http._
import spray.httpx.encoding.Gzip

import scala.concurrent.Future
import scala.concurrent.duration._

class StaticBalancer(response: HttpResponse, serviceLocation: ServiceLocation, serviceName: String, cacheParams: Set[Param]) extends ProxyBalancer {
  var lastRequest : Option[HttpRequest] = None
  def proxy(template: EndpointTemplate, request: HttpRequest): Future[ProxiedResponse] = {
    lastRequest = Some(request)
    Future.successful(ProxiedResponse(serviceLocation, serviceName, template, cacheParams, response))
  }

  def balanceTo(location: ServiceLocation, template: EndpointTemplate, request: HttpRequest) = None
}

class RequestProcessorSpec extends TestKit(ActorSystem("testSystem", ConfigFactory.parseString(
  """
    |akka.scheduler.implementation = shield.akka.helpers.VirtualScheduler
  """.stripMargin).withFallback(ConfigFactory.load())))
with WordSpecLike
with MustMatchers
with BeforeAndAfterEach
with BeforeAndAfterAll
with ImplicitSender {

  val scheduler = system.scheduler.asInstanceOf[VirtualScheduler]
  def virtualTime = scheduler.virtualTime

  override def beforeEach: Unit = {
    scheduler.reset()
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val settings = Settings(system)
  val serviceLocation = HttpServiceLocation("http://example.com")
  val upstreamServiceLocation = HttpServiceLocation("http://example.com")
  val getFoobar = EndpointTemplate(HttpMethods.GET, Path("/foobar"))


  "RequestProcessor" should {
    "respect disabled middleware" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails(
          Set.empty,
          Set.empty,
          Set.empty,
          Set("key"),
          Set.empty
        ),
        EndpointDetails.empty
      ), middleware, FailBalancer)

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))

      ref ! request

      middlewareKeyProbe.msgAvailable mustBe false

      middlewareRateProbe.expectMsg(DownstreamRequest(
        "request.rate",
        destination,
        preprocessRequest(request)
      ))
    }

    "doesn't crash if Remote-Address is missing" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, FailBalancer)

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val request = HttpRequest()

      ref ! request

      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        HttpRequest(headers=List(HttpHeaders.RawHeader("client-address", "127.0.0.1")))
      ))
    }

    "bypass middleware and upstream if router gives a response" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val listenerProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Left(ResponseDetails(settings.DefaultServiceLocation, settings.LocalServiceName, getFoobar, None, HttpResponse()))),
        Map("testProbe" -> listenerProbe.ref),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))

      ref ! request

      middlewareKeyProbe.msgAvailable mustBe false

      val response = HttpResponse(headers = List(HttpHeaders.RawHeader("Via", "HTTP/1.1 Shield")))
      connection.expectMsg(
        response
      )

      val requestCompletedMsg = listenerProbe.receiveOne(100.millis).asInstanceOf[RequestProcessorCompleted]
      requestCompletedMsg.completion.details.template mustEqual getFoobar
      requestCompletedMsg.completion.details.explicitCacheParams mustEqual None
      requestCompletedMsg.completion.details.serviceLocation mustEqual settings.DefaultServiceLocation
      requestCompletedMsg.completion.details.serviceName mustEqual settings.LocalServiceName
      requestCompletedMsg.completion.request mustEqual preprocessRequest(request)
      requestCompletedMsg.completion.details.response mustEqual response
    }

    "forwards the request to the first middleware" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, FailBalancer)

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))

      ref ! request

      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        preprocessRequest(request)
      ))
    }

    "respects the SLA on the request middleware" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, new StaticBalancer(HttpResponse(), upstreamServiceLocation, "upstream", Set.empty))

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val deathProbe = TestProbe()
      deathProbe.watch(ref)
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))

      ref ! request

      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        preprocessRequest(request)
      ))
      middlewareRateProbe.msgAvailable mustBe false
      connection.msgAvailable mustBe false

      virtualTime.advance(999.millis)

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.msgAvailable mustBe false
      connection.msgAvailable mustBe false

      virtualTime.advance(1.milli)

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.expectMsg(DownstreamRequest(
        "request.rate",
        destination,
        preprocessRequest(request)
      ))
      connection.msgAvailable mustBe false

      virtualTime.advance(1999.millis)

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.msgAvailable mustBe false
      connection.msgAvailable mustBe false

      virtualTime.advance(1.milli)

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.msgAvailable mustBe false
      connection.expectMsg(HttpResponse(
        headers = List(HttpHeaders.RawHeader("Via", "HTTP/1.1 Shield"))
      ))

      deathProbe.expectTerminated(ref)
    }

    "applies the ForwardRequestCmd to the next request middleware" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, new StaticBalancer(HttpResponse(), upstreamServiceLocation, "upstream", Set.empty))

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))
      val requestWithKey = request.copy(headers = RawHeader("x-api-key", "foobar") :: request.headers)

      ref ! request

      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        preprocessRequest(request)
      ))
      middlewareRateProbe.msgAvailable mustBe false
      connection.msgAvailable mustBe false

      middlewareKeyProbe.reply(ForwardRequestCmd("request.key", requestWithKey, None))

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.expectMsg(DownstreamRequest(
        "request.rate",
        destination,
        requestWithKey
      ))
      connection.msgAvailable mustBe false
    }

    "registers the response middleware from the ForwardRequestCmd" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, new StaticBalancer(HttpResponse(), upstreamServiceLocation, "upstream", Set.empty))

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))
      val requestWithKey = request.copy(headers = RawHeader("x-api-key", "foobar") :: request.headers)

      ref ! request

      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        preprocessRequest(request)
      ))
      middlewareRateProbe.msgAvailable mustBe false
      connection.msgAvailable mustBe false

      val keyResponseProbe = TestProbe()
      val keyResponseMiddleware = Middleware("response-inject", 1.second, keyResponseProbe.ref)

      middlewareKeyProbe.reply(ForwardRequestCmd("request.key", requestWithKey, Some(keyResponseMiddleware)))

      ref.underlyingActor.pendingResponseStages.exists(_.stageName == "response.response-inject") mustBe true
    }

    "ignores a stale ForwardRequestCmd from request middleware" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, new StaticBalancer(HttpResponse(), upstreamServiceLocation, "upstream", Set.empty))

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))
      val requestWithKey = request.copy(headers = RawHeader("x-api-key", "foobar") :: request.headers)

      ref ! request

      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        preprocessRequest(request)
      ))
      middlewareRateProbe.msgAvailable mustBe false
      connection.msgAvailable mustBe false

      virtualTime.advance(1.second)

      val keyResponseProbe = TestProbe()
      val keyResponseMiddleware = Middleware("response-inject", 1.second, keyResponseProbe.ref)

      middlewareKeyProbe.reply(ForwardRequestCmd("request.key", requestWithKey, Some(keyResponseMiddleware)))

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.expectMsg(DownstreamRequest(
        "request.rate",
        destination,
        preprocessRequest(request)
      ))
      // and no messages behind that one
      middlewareRateProbe.msgAvailable mustBe false
      connection.msgAvailable mustBe false

      ref.underlyingActor.pendingResponseStages.exists(_.stageName == "response.response-inject") mustBe false
    }

    "applies the ForwardResponseCmd from request middleware" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, new StaticBalancer(HttpResponse(), upstreamServiceLocation, "upstream", Set.empty))

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val deathProbe = TestProbe()
      deathProbe.watch(ref)

      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))

      ref ! request

      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        preprocessRequest(request)
      ))
      middlewareRateProbe.msgAvailable mustBe false
      connection.msgAvailable mustBe false

      middlewareKeyProbe.reply(ForwardResponseCmd("request.key", ResponseDetails(settings.DefaultServiceLocation, settings.LocalServiceName, getFoobar, None, HttpResponse())))

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.msgAvailable mustBe false
      connection.expectMsg(HttpResponse(
        headers = List(HttpHeaders.RawHeader("Via", "HTTP/1.1 Shield"))
      ))

      deathProbe.expectTerminated(ref)
    }

    "ignores a stale ForwardResponseCmd from request middleware" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, new StaticBalancer(HttpResponse(), upstreamServiceLocation, "upstream", Set.empty))

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))

      ref ! request

      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        preprocessRequest(request)
      ))
      middlewareRateProbe.msgAvailable mustBe false
      connection.msgAvailable mustBe false

      virtualTime.advance(1.second)

      middlewareKeyProbe.reply(ForwardResponseCmd("request.key", ResponseDetails(settings.DefaultServiceLocation, settings.LocalServiceName, getFoobar, None, HttpResponse())))

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.expectMsg(DownstreamRequest(
        "request.rate",
        destination,
        preprocessRequest(request)
      ))
      connection.msgAvailable mustBe false
    }

    "call the proxy balancer after exhausting request middleware" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val balancer = new StaticBalancer(HttpResponse(), upstreamServiceLocation, "upstream", Set.empty)
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, balancer)

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val deathProbe = TestProbe()
      deathProbe.watch(ref)
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))
      val requestWithKey = request.copy(headers = RawHeader("x-api-key", "foobar") :: request.headers)
      val requestWithRate = requestWithKey.copy(headers = RawHeader("x-rate-limit", "good to go") :: requestWithKey.headers)

      ref ! request

      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        preprocessRequest(request)
      ))
      middlewareRateProbe.msgAvailable mustBe false
      connection.msgAvailable mustBe false

      middlewareKeyProbe.reply(ForwardRequestCmd("request.key", requestWithKey, None))

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.expectMsg(DownstreamRequest(
        "request.rate",
        destination,
        requestWithKey
      ))
      connection.msgAvailable mustBe false

      middlewareRateProbe.reply(ForwardRequestCmd("request.rate", requestWithRate, None))

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.msgAvailable mustBe false
      connection.expectMsg(HttpResponse(
        headers = List(HttpHeaders.RawHeader("Via", "HTTP/1.1 Shield"))
      ))

      balancer.lastRequest mustEqual Some(requestWithRate)

      deathProbe.expectTerminated(ref)
    }

    "call the proxy balancer if there are no request middleware" in {
      val connection = TestProbe()
      val balancer = new StaticBalancer(HttpResponse(), upstreamServiceLocation, "upstream", Set.empty)
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), Nil, balancer)

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val deathProbe = TestProbe()
      deathProbe.watch(ref)
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))

      ref ! request

      connection.expectMsg(HttpResponse(
        headers = List(HttpHeaders.RawHeader("Via", "HTTP/1.1 Shield"))
      ))

      balancer.lastRequest mustEqual Some(preprocessRequest(request))

      deathProbe.expectTerminated(ref)
    }

    "handle the proxy balancer taking too long" in {
      val connection = TestProbe()
      val balancer = FailBalancer
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), Nil, balancer)

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val deathProbe = TestProbe()
      deathProbe.watch(ref)
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))

      ref ! request

      connection.msgAvailable mustBe false

      virtualTime.advance(30.seconds)

      connection.expectMsg(HttpResponse(
        StatusCodes.GatewayTimeout,
        headers = List(HttpHeaders.RawHeader("Via", "HTTP/1.1 Shield"))
      ))

      deathProbe.expectTerminated(ref)
    }

    "visit response middleware in reverse order" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, new StaticBalancer(HttpResponse(), upstreamServiceLocation, "upstream", Set.empty))

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))
      val requestWithKey = request.copy(headers = RawHeader("x-api-key", "foobar") :: request.headers)
      val requestWithRate = requestWithKey.copy(headers = RawHeader("x-ratelimit", "good to go") :: requestWithKey.headers)

      ref ! request

      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        preprocessRequest(request)
      ))
      middlewareRateProbe.msgAvailable mustBe false
      connection.msgAvailable mustBe false

      val keyResponseProbe = TestProbe()
      val keyResponseMiddleware = Middleware("response-key", 1.second, keyResponseProbe.ref)

      middlewareKeyProbe.reply(ForwardRequestCmd("request.key", requestWithKey, Some(keyResponseMiddleware)))

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.expectMsg(DownstreamRequest(
        "request.rate",
        destination,
        requestWithKey
      ))
      keyResponseProbe.msgAvailable mustBe false
      connection.msgAvailable mustBe false


      val rateResponseProbe = TestProbe()
      val rateResponseMiddleware = Middleware("response-rate", 1.second, rateResponseProbe.ref)

      middlewareRateProbe.reply(ForwardRequestCmd("request.rate", requestWithRate, Some(rateResponseMiddleware)))
      val details = ResponseDetails(upstreamServiceLocation, "upstream", getFoobar, Some(Set.empty), HttpResponse())

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.msgAvailable mustBe false
      keyResponseProbe.msgAvailable mustBe false
      rateResponseProbe.expectMsg(UpstreamResponse(
        "response.response-rate",
        requestWithRate,
        details
      ))
      connection.msgAvailable mustBe false

      rateResponseProbe.reply(ForwardResponseCmd("response.response-rate", details))

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.msgAvailable mustBe false
      rateResponseProbe.msgAvailable mustBe false
      keyResponseProbe.expectMsg(UpstreamResponse(
        "response.response-key",
        requestWithKey,
        details
      ))
      connection.msgAvailable mustBe false
    }

    "respects the sla of response middleware" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, new StaticBalancer(HttpResponse(), upstreamServiceLocation, "upstream", Set.empty))

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))
      val requestWithKey = request.copy(headers = RawHeader("x-api-key", "foobar") :: request.headers)
      val requestWithRate = requestWithKey.copy(headers = RawHeader("x-ratelimit", "good to go") :: requestWithKey.headers)

      ref ! request

      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        preprocessRequest(request)
      ))
      val keyResponseProbe = TestProbe()
      val keyResponseMiddleware = Middleware("response-key", 1.second, keyResponseProbe.ref)
      middlewareKeyProbe.reply(ForwardRequestCmd("request.key", requestWithKey, Some(keyResponseMiddleware)))

      middlewareRateProbe.expectMsg(DownstreamRequest(
        "request.rate",
        destination,
        requestWithKey
      ))
      val rateResponseProbe = TestProbe()
      val rateResponseMiddleware = Middleware("response-rate", 1.second, rateResponseProbe.ref)
      val details = ResponseDetails(upstreamServiceLocation, "upstream", getFoobar, Some(Set.empty), HttpResponse())
      middlewareRateProbe.reply(ForwardRequestCmd("request.rate", requestWithRate, Some(rateResponseMiddleware)))

      rateResponseProbe.expectMsg(UpstreamResponse(
        "response.response-rate",
        requestWithRate,
        details
      ))

      virtualTime.advance(1.second)

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.msgAvailable mustBe false
      rateResponseProbe.msgAvailable mustBe false
      keyResponseProbe.expectMsg(UpstreamResponse(
        "response.response-key",
        requestWithKey,
        details
      ))
      connection.msgAvailable mustBe false
    }

    "ignores a stale forwardresponsecmd from response middleware" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, new StaticBalancer(HttpResponse(), upstreamServiceLocation, "upstream", Set.empty))

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))
      val requestWithKey = request.copy(headers = RawHeader("x-api-key", "foobar") :: request.headers)
      val requestWithRate = requestWithKey.copy(headers = RawHeader("x-ratelimit", "good to go") :: requestWithKey.headers)

      ref ! request

      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        preprocessRequest(request)
      ))
      val keyResponseProbe = TestProbe()
      val keyResponseMiddleware = Middleware("response-key", 1.second, keyResponseProbe.ref)
      middlewareKeyProbe.reply(ForwardRequestCmd("request.key", requestWithKey, Some(keyResponseMiddleware)))

      middlewareRateProbe.expectMsg(DownstreamRequest(
        "request.rate",
        destination,
        requestWithKey
      ))
      val rateResponseProbe = TestProbe()
      val rateResponseMiddleware = Middleware("response-rate", 1.second, rateResponseProbe.ref)
      val details = ResponseDetails(upstreamServiceLocation, "upstream", getFoobar, Some(Set.empty), HttpResponse())
      middlewareRateProbe.reply(ForwardRequestCmd("request.rate", requestWithRate, Some(rateResponseMiddleware)))

      rateResponseProbe.expectMsg(UpstreamResponse(
        "response.response-rate",
        requestWithRate,
        details
      ))

      virtualTime.advance(1.second)

      val changedDetails = details.copy(response = HttpResponse(StatusCodes.NotFound))
      rateResponseProbe.reply(ForwardResponseCmd("response.response-rate", changedDetails))

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.msgAvailable mustBe false
      rateResponseProbe.msgAvailable mustBe false
      keyResponseProbe.expectMsg(UpstreamResponse(
        "response.response-key",
        requestWithKey,
        details //not changedDetails!
      ))
      keyResponseProbe.msgAvailable mustBe false //and not another one after that
      connection.msgAvailable mustBe false
    }

    "ignores a forwardrequestcmd from response middleware" in {
      val connection = TestProbe()
      val middlewareKeyProbe = TestProbe()
      val middlewareRateProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref),
        Middleware("rate", 2.seconds, middlewareRateProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, new StaticBalancer(HttpResponse(), upstreamServiceLocation, "upstream", Set.empty))

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        Map(),
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))
      val requestWithKey = request.copy(headers = RawHeader("x-api-key", "foobar") :: request.headers)
      val requestWithRate = requestWithKey.copy(headers = RawHeader("x-ratelimit", "good to go") :: requestWithKey.headers)

      ref ! request

      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        preprocessRequest(request)
      ))
      val keyResponseProbe = TestProbe()
      val keyResponseMiddleware = Middleware("response-key", 1.second, keyResponseProbe.ref)
      middlewareKeyProbe.reply(ForwardRequestCmd("request.key", requestWithKey, Some(keyResponseMiddleware)))

      middlewareRateProbe.expectMsg(DownstreamRequest(
        "request.rate",
        destination,
        requestWithKey
      ))
      val rateResponseProbe = TestProbe()
      val rateResponseMiddleware = Middleware("response-rate", 1.second, rateResponseProbe.ref)
      val details = ResponseDetails(upstreamServiceLocation, "upstream", getFoobar, Some(Set.empty), HttpResponse())
      middlewareRateProbe.reply(ForwardRequestCmd("request.rate", requestWithRate, Some(rateResponseMiddleware)))

      rateResponseProbe.expectMsg(UpstreamResponse(
        "response.response-rate",
        requestWithRate,
        details
      ))

      rateResponseProbe.reply(ForwardRequestCmd("response.response-rate", HttpRequest(HttpMethods.DELETE), None))

      middlewareKeyProbe.msgAvailable mustBe false
      middlewareRateProbe.msgAvailable mustBe false
      rateResponseProbe.msgAvailable mustBe false
      keyResponseProbe.msgAvailable mustBe false
      connection.msgAvailable mustBe false
    }

    "sends a scrubbed response to the listeners" in {
      val connection = TestProbe()
      val balancer = new StaticBalancer(HttpResponse(
        StatusCodes.OK,
        HttpEntity(ContentTypes.`application/json`, "{\"foo\": \"bar\"}"),
        List(
          HttpHeaders.Date(DateTime.now),
          HttpHeaders.Server(ProductVersion("foobar", "1.2.3")),
          HttpHeaders.`Content-Length`(1234),
          HttpHeaders.`Content-Type`(ContentTypes.`application/json`),
          HttpHeaders.`Transfer-Encoding`("identity"),
          HttpHeaders.RawHeader("Via", "HTTP/1.1 Foobar"),
          HttpHeaders.Connection("close"),
          HttpHeaders.RawHeader("x-keep-me", "keep me")
        )
      ), upstreamServiceLocation, "upstream", Set.empty)
      val listenerProbes = (1 to 3).map(_ => TestProbe()).toList
      val listenerProbesMap = listenerProbes.map(probe => listenerProbes.indexOf(probe).toString -> probe.ref).toMap
      val middlewareKeyProbe = TestProbe()
      val middleware = List(
        Middleware("key", 1.second, middlewareKeyProbe.ref)
      )
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), middleware, balancer)

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        listenerProbesMap,
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val deathProbe = TestProbe()
      deathProbe.watch(ref)
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))
      val requestWithKey = request.copy(headers = RawHeader("x-api-key", "foobar") :: request.headers)

      ref ! request
      middlewareKeyProbe.expectMsg(DownstreamRequest(
        "request.key",
        destination,
        preprocessRequest(request)
      ))

      val currentTime = System.currentTimeMillis()

      while (System.currentTimeMillis() - currentTime <= 10) {
        // doot doot
      }

      middlewareKeyProbe.reply(ForwardRequestCmd("request.key", requestWithKey, None))

      val expectedResponse = HttpResponse(
        StatusCodes.OK,
        HttpEntity(ContentTypes.`application/json`, "{\"foo\": \"bar\"}"),
        List(HttpHeaders.RawHeader("Via", "HTTP/1.1 Foobar, HTTP/1.1 Shield"), HttpHeaders.RawHeader("x-keep-me", "keep me"))
      )
      connection.expectMsg(expectedResponse)

      val completionMessage = listenerProbes.head.receiveOne(100.millis).asInstanceOf[RequestProcessorCompleted]
      // broadcast original request, not one modified by middleware
      completionMessage.completion mustEqual ResponseCompleted(preprocessRequest(request), ResponseDetails(upstreamServiceLocation, "upstream", getFoobar, Some(Set.empty), expectedResponse))
      completionMessage.overallTiming must be >= 10l
      completionMessage.overallTiming must be < 100l
      completionMessage.middlewareTiming.contains("request.key") mustBe true
      completionMessage.middlewareTiming("request.key") must be >= 10l
      completionMessage.middlewareTiming("request.key") must be < 100l

      for (listener <- listenerProbes.tail) {
        listener.expectMsg(completionMessage)
      }

      deathProbe.expectTerminated(ref)
    }

    "not forward completed requests to disabled listeners" in {
      val connection = TestProbe()
      val balancer = new StaticBalancer(HttpResponse(
        StatusCodes.OK,
        HttpEntity(ContentTypes.`application/json`, "{\"foo\": \"bar\"}"),
        List(
          HttpHeaders.Date(DateTime.now),
          HttpHeaders.Server(ProductVersion("foobar", "1.2.3")),
          HttpHeaders.`Content-Length`(1234),
          HttpHeaders.`Content-Type`(ContentTypes.`application/json`),
          HttpHeaders.`Transfer-Encoding`("identity"),
          HttpHeaders.RawHeader("Via", "HTTP/1.1 Foobar"),
          HttpHeaders.Connection("close"),
          HttpHeaders.RawHeader("x-keep-me", "keep me")
        )
      ), upstreamServiceLocation, "upstream", Set.empty)
      val listenerProbes = (1 to 3).map(_ => TestProbe()).toList
      val listenerProbesMap = listenerProbes.map(probe => listenerProbes.indexOf(probe).toString -> probe.ref).toMap
      val possibleDetails= List(EndpointDetails(Set.empty, Set.empty, Set.empty, Set.empty, Set("1","2")))
      val destination = RoutingDestination(getFoobar, possibleDetails, List[Middleware](), balancer)

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        listenerProbesMap,
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val deathProbe = TestProbe()
      deathProbe.watch(ref)
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")))

      ref ! request

      val expectedResponse = HttpResponse(
        StatusCodes.OK,
        HttpEntity(ContentTypes.`application/json`, "{\"foo\": \"bar\"}"),
        List(HttpHeaders.RawHeader("Via", "HTTP/1.1 Foobar, HTTP/1.1 Shield"), HttpHeaders.RawHeader("x-keep-me", "keep me"))
      )
      connection.expectMsg(expectedResponse)

      val completionMessage = listenerProbes.head.receiveOne(100.millis).asInstanceOf[RequestProcessorCompleted]
      completionMessage.completion mustEqual ResponseCompleted(preprocessRequest(request), ResponseDetails(upstreamServiceLocation, "upstream", getFoobar, Some(Set.empty), expectedResponse))

      //Disabled listeners dont receive message
      listenerProbes(1).msgAvailable mustBe false
      listenerProbes(2).msgAvailable mustBe false

      deathProbe.expectTerminated(ref)
    }

    "forwards an uncompressed response from upstream if the client does not support gzip" in {
      val connection = TestProbe()
      val entity = HttpEntity("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.")
      val balancer = new StaticBalancer(HttpResponse(StatusCodes.OK, entity), upstreamServiceLocation, "upstream", Set.empty)
      val listenerProbes = (1 to 3).map(_ => TestProbe()).toList
      val listenerProbesMap = listenerProbes.map(probe => listenerProbes.indexOf(probe).toString -> probe.ref).toMap
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), Nil, balancer)

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        listenerProbesMap,
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val deathProbe = TestProbe()
      deathProbe.watch(ref)
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(
        HttpHeaders.`Accept-Encoding`(HttpEncodings.identity),
        HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")
      ))

      ref ! request

      val expectedResponse = HttpResponse(
        StatusCodes.OK,
        entity,
        List(HttpHeaders.RawHeader("Via", "HTTP/1.1 Shield"))
      )
      connection.expectMsg(expectedResponse)

      val completionMessage = listenerProbes.head.receiveOne(100.millis).asInstanceOf[RequestProcessorCompleted]
      completionMessage.completion mustEqual ResponseCompleted(preprocessRequest(request), ResponseDetails(upstreamServiceLocation, "upstream", getFoobar, Some(Set.empty), expectedResponse))

      for (listener <- listenerProbes.tail) {
        listener.expectMsg(completionMessage)
      }

      deathProbe.expectTerminated(ref)
    }

    "compresses an uncompressed response from upstream if the client supports gzip" in {
      val connection = TestProbe()
      val entity = HttpEntity("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.")
      val balancer = new StaticBalancer(HttpResponse(StatusCodes.OK, entity), upstreamServiceLocation, "upstream", Set.empty)
      val listenerProbes = (1 to 3).map(_ => TestProbe()).toList
      val listenerProbesMap = listenerProbes.map(probe => listenerProbes.indexOf(probe).toString -> probe.ref).toMap
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), Nil, balancer)

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        listenerProbesMap,
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val deathProbe = TestProbe()
      deathProbe.watch(ref)
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(
        HttpHeaders.`Accept-Encoding`(HttpEncodings.identity, HttpEncodings.gzip),
        HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")
      ))

      ref ! request

      val expectedResponse = Gzip.encode(HttpResponse(
        StatusCodes.OK,
        entity,
        List(HttpHeaders.RawHeader("Via", "HTTP/1.1 Shield"))
      ))
      connection.expectMsg(expectedResponse)

      val completionMessage = listenerProbes.head.receiveOne(100.millis).asInstanceOf[RequestProcessorCompleted]
      completionMessage.completion mustEqual ResponseCompleted(preprocessRequest(request), ResponseDetails(upstreamServiceLocation, "upstream", getFoobar, Some(Set.empty), expectedResponse))

      for (listener <- listenerProbes.tail) {
        listener.expectMsg(completionMessage)
      }

      deathProbe.expectTerminated(ref)
    }

    "forwards a compressed response from upstream if the client supports gzip" in {
      val connection = TestProbe()
      val entity = HttpEntity("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.")
      val defaultResponse = Gzip.encode(HttpResponse(StatusCodes.OK, entity))
      val balancer = new StaticBalancer(defaultResponse, upstreamServiceLocation, "upstream", Set.empty)
      val listenerProbes = (1 to 3).map(_ => TestProbe()).toList
      val listenerProbesMap = listenerProbes.map(probe => listenerProbes.indexOf(probe).toString -> probe.ref).toMap
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), Nil, balancer)

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        listenerProbesMap,
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val deathProbe = TestProbe()
      deathProbe.watch(ref)
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(
        HttpHeaders.`Accept-Encoding`(HttpEncodings.identity, HttpEncodings.gzip),
        HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")
      ))

      ref ! request

      val expectedResponse = defaultResponse.copy(headers = HttpHeaders.RawHeader("Via", "HTTP/1.1 Shield") :: defaultResponse.headers)
      connection.expectMsg(expectedResponse)

      val completionMessage = listenerProbes.head.receiveOne(100.millis).asInstanceOf[RequestProcessorCompleted]
      completionMessage.completion mustEqual ResponseCompleted(preprocessRequest(request), ResponseDetails(upstreamServiceLocation, "upstream", getFoobar, Some(Set.empty), expectedResponse))

      for (listener <- listenerProbes.tail) {
        listener.expectMsg(completionMessage)
      }

      deathProbe.expectTerminated(ref)
    }

    "uncompresses a compressed response from upstream if the client does not support gzip" in {
      val connection = TestProbe()
      val entity = HttpEntity("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.")
      val defaultResponse = Gzip.encode(HttpResponse(StatusCodes.OK, entity))
      val balancer = new StaticBalancer(defaultResponse, upstreamServiceLocation, "upstream", Set.empty)
      val listenerProbes = (1 to 3).map(_ => TestProbe()).toList
      val listenerProbesMap = listenerProbes.map(probe => listenerProbes.indexOf(probe).toString -> probe.ref).toMap
      val destination = RoutingDestination(getFoobar, List(EndpointDetails.empty), Nil, balancer)

      val ref = TestActorRef(new RequestProcessor(
        new StaticRouter(Right(destination)),
        listenerProbesMap,
        connection.ref,
        settings.DefaultServiceLocation,
        Set[String]()
      ))
      val deathProbe = TestProbe()
      deathProbe.watch(ref)
      val request = HttpRequest(HttpMethods.GET, Uri("/foobar"), List(
        HttpHeaders.`Accept-Encoding`(HttpEncodings.identity),
        HttpHeaders.RawHeader("Remote-Address", "1.2.3.4")
      ))

      ref ! request

      val expectedResponse = HttpResponse(StatusCodes.OK, entity, List(HttpHeaders.RawHeader("Via", "HTTP/1.1 Shield")))
      connection.expectMsg(expectedResponse)

      val completionMessage = listenerProbes.head.receiveOne(100.millis).asInstanceOf[RequestProcessorCompleted]
      completionMessage.completion mustEqual ResponseCompleted(preprocessRequest(request), ResponseDetails(upstreamServiceLocation, "upstream", getFoobar, Some(Set.empty), expectedResponse))

      for (listener <- listenerProbes.tail) {
        listener.expectMsg(completionMessage)
      }

      deathProbe.expectTerminated(ref)
    }
  }

  def preprocessRequest(r: HttpRequest) = {
    import shield.implicits.HttpImplicits._
    r
      .withTrustXForwardedProto(settings.trustXForwardedProto)
      .withTrustXForwardedFor(settings.trustProxies)
  }
}
