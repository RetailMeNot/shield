package shield.actors

import akka.actor.{ActorSystem, Props}
import akka.pattern.AskTimeoutException
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, MustMatchers, WordSpecLike}
import shield.actors.HostProxyMsgs.ShutdownProxy
import shield.actors.config.{ProxyState, UpstreamAggregatorMsgs}
import shield.akka.helpers.VirtualScheduler
import shield.config._
import shield.proxying.{ProxiedResponse, ProxyRequest}
import shield.routing.{EndpointDetails, EndpointTemplate, Path}
import shield.swagger.{SwaggerDetails, SwaggerFetcher}
import shield.transports.HttpTransport
import spray.http.HttpHeaders.RawHeader
import spray.http._

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

class FakeFetcher(details: SwaggerDetails) extends SwaggerFetcher {
  var fetchCount = 0

  override def fetch(host: ServiceLocation): Future[SwaggerDetails] = {
    fetchCount += 1
    Future.successful(details)
  }
}

class FailFetcher extends SwaggerFetcher {
  var fetchCount = 0
  override def fetch(host: ServiceLocation): Future[SwaggerDetails] = {
    fetchCount += 1
    Future.failed(new NotImplementedError())
  }
}

class NeverFetcher extends SwaggerFetcher {
  var fetchCount = 0

  override def fetch(host: ServiceLocation): Future[SwaggerDetails] = {
    fetchCount += 1
    Promise().future
  }
}


class Swagger1HostProxySpec extends TestKit(ActorSystem("testSystem", ConfigFactory.parseString(
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

  "HostProxy" must {
    val settings = Settings(system)
    val serviceLocation = HttpServiceLocation("http://example.com")
    val getFoobar = EndpointTemplate(HttpMethods.GET, Path("/foobar"))
    val postFoobar = EndpointTemplate(HttpMethods.POST, Path("/foobar"))
    val getFizzbuzz = EndpointTemplate(HttpMethods.GET, Path("/fizzbuzz"))

    val endpointDetails = SwaggerDetails("upstream", "1.2.3", Map(
       getFoobar -> EndpointDetails.empty,
       postFoobar -> EndpointDetails.empty,
      getFizzbuzz -> EndpointDetails.empty
    ))

    "auto fetch swagger docs on start" in {
      val testFetcher = new FakeFetcher(endpointDetails)

      val ref = TestActorRef(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => Future.successful(HttpResponse(StatusCodes.OK))) {
        override def buildFetcher() = testFetcher
      })

      testFetcher.fetchCount must equal(1)
    }


    "retry swagger fetch if it fails" in {
      val testFetcher = new FailFetcher
      val ref = TestActorRef(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => Future.successful(HttpResponse(StatusCodes.OK))) {
        override def buildFetcher() = testFetcher
      })

      testFetcher.fetchCount must be(1)

      virtualTime.advance(29.seconds)

      testFetcher.fetchCount must be(1)

      virtualTime.advance(1.seconds)

      testFetcher.fetchCount must be(2)

      virtualTime.advance(29.seconds)

      testFetcher.fetchCount must be(2)

      virtualTime.advance(1.seconds)

      testFetcher.fetchCount must be(3)
    }

    "retry swagger fetch if it times out" in {
      val testFetcher = new NeverFetcher
      val ref = TestActorRef(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => Future.successful(HttpResponse(StatusCodes.OK))) {
        override def buildFetcher() = testFetcher
      })

      testFetcher.fetchCount must be(1)

      virtualTime.advance(29.seconds)

      testFetcher.fetchCount must be(1)

      virtualTime.advance(1.seconds)

      // 30 second for the future to time out
      testFetcher.fetchCount must be(1)

      virtualTime.advance(29.seconds)

      testFetcher.fetchCount must be(1)

      virtualTime.advance(1.seconds)

      // 30 seconds for the retry wait
      testFetcher.fetchCount must be(2)
    }

    "serve requests upon fetching swagger docs" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      val ref = TestActorRef(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => Future.successful(HttpResponse(StatusCodes.OK))) {
        override def buildFetcher() = testFetcher
      })

      testFetcher.fetchCount must be(1)

      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))

      expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.OK)))
    }

    "shed requests for a consistently failing endpoint" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      var upstreamRequests = 0
      val upstreamResponse = Future.successful(HttpResponse(StatusCodes.InternalServerError))
      val ref = TestActorRef(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => {
        upstreamRequests += 1
        upstreamResponse
      }) {
        override def buildFetcher() = testFetcher
      })

      // takes 25 back to back calls to trip an endpoint breaker
      for (i <- 1 to 26) {
        ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      }

      upstreamRequests must be(25)

      for (i <- 1 to 25) {
        expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.InternalServerError)))
      }

      expectMsg(ProxiedResponse(settings.DefaultServiceLocation, settings.LocalServiceName, getFoobar, Set.empty, HttpResponse(StatusCodes.ServiceUnavailable)))

    }

    "shed requests for a consistent timeout endpoint" in {
      // this doesn't actually test the circuitbreaker timeout, since the cb relies on duration.fromNow, which uses the wall clock instead of our fake scheduler
      // oh well, at least this tests the timeout method of the spray pipeline (which fails with AskTimeoutException)
      val testFetcher = new FakeFetcher(endpointDetails)
      var upstreamRequests = 0
      val ref = TestActorRef(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => {
        upstreamRequests += 1
        val p = Promise[HttpResponse]()
        scheduler.scheduleOnce(10.seconds)(p.failure(new AskTimeoutException("Fake timeout")))(system.dispatcher)
        p.future
      }) {
        override def buildFetcher() = testFetcher
      })

      // takes 25 back to back calls to trip an endpoint breaker
      for (i <- 1 to 25) {
        ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      }
      virtualTime.advance(10.seconds)
      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))

      upstreamRequests must be(25)

      for (i <- 1 to 25) {
        expectMsg(ProxiedResponse(settings.DefaultServiceLocation, settings.LocalServiceName, getFoobar, Set.empty, HttpResponse(StatusCodes.GatewayTimeout)))
      }

      expectMsg(ProxiedResponse(settings.DefaultServiceLocation, settings.LocalServiceName, getFoobar, Set.empty, HttpResponse(StatusCodes.ServiceUnavailable)))

    }

    "not trip the host breaker due to one tripped endpoint breaker" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      var upstreamRequests = 0
      var upstreamResponse = Future.successful(HttpResponse(StatusCodes.InternalServerError))
      val ref = TestActorRef(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => {
        upstreamRequests += 1
        upstreamResponse
      }) {
        override def buildFetcher() = testFetcher
      })

      // takes 25 back to back calls to trip an endpoint breaker, and 50 to trip the host breaker
      for (i <- 1 to 51) {
        ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      }

      upstreamRequests must be(25)

      for (i <- 1 to 25) {
        expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.InternalServerError)))
      }
      for (i <- 1 to 26) {
        expectMsg(ProxiedResponse(settings.DefaultServiceLocation, settings.LocalServiceName, getFoobar, Set.empty, HttpResponse(StatusCodes.ServiceUnavailable)))
      }

      upstreamResponse = Future.successful(HttpResponse(StatusCodes.OK))
      ref ! ProxyRequest(getFizzbuzz, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFizzbuzz, Set.empty, HttpResponse(StatusCodes.OK)))

      upstreamRequests must be(26)
    }

    "allows one request through in the endpoint half-open state" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      var upstreamRequests = 0
      var upstreamResponse = Future.successful(HttpResponse(StatusCodes.InternalServerError))
      val ref = TestActorRef(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => {
        upstreamRequests += 1
        upstreamResponse
      }) {
        override def buildFetcher() = testFetcher
      })

      // takes 25 back to back calls to trip an endpoint breaker
      for (i <- 1 to 25) {
        ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
        expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.InternalServerError)))
      }

      upstreamRequests must be(25)

      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(settings.DefaultServiceLocation, settings.LocalServiceName, getFoobar, Set.empty, HttpResponse(StatusCodes.ServiceUnavailable)))

      upstreamRequests must be(25)

      // enough time for the cb to move to half-open
      virtualTime.advance(10.seconds)

      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.InternalServerError)))

      upstreamRequests must be(26)

      // re-opens upon failure from half open
      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(settings.DefaultServiceLocation, settings.LocalServiceName, getFoobar, Set.empty, HttpResponse(StatusCodes.ServiceUnavailable)))

      upstreamRequests must be(26)

      // enough time for the cb to move to half-open
      virtualTime.advance(10.seconds)
      upstreamResponse = Future.successful(HttpResponse(StatusCodes.OK))

      // moves to closed state upon success
      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.OK)))

      upstreamRequests must be(27)

      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.OK)))

      upstreamRequests must be(28)

    }

    "shed requests when the host circuit breaker is open" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      var upstreamRequests = 0
      var upstreamResponse = Future.successful(HttpResponse(StatusCodes.InternalServerError))
      val ref = TestActorRef(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => {
        upstreamRequests += 1
        upstreamResponse
      }) {
        override def buildFetcher() = testFetcher
      })

      // takes 25 back to back calls to trip an endpoint breaker, and 50 to trip the host breaker
      for (i <- 1 to 25) {
        ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      }
      for (i <- 1 to 25) {
        ref ! ProxyRequest(postFoobar, HttpRequest(HttpMethods.POST))
      }

      upstreamRequests must be(50)

      for (i <- 1 to 25) {
        expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.InternalServerError)))
      }
      for (i <- 1 to 25) {
        expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, postFoobar, Set.empty, HttpResponse(StatusCodes.InternalServerError)))
      }

      upstreamResponse = Future.successful(HttpResponse(StatusCodes.OK))

      ref ! ProxyRequest(getFizzbuzz, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(settings.DefaultServiceLocation, settings.LocalServiceName, getFizzbuzz, Set.empty, HttpResponse(StatusCodes.ServiceUnavailable)))
      upstreamRequests must be(50)

      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(settings.DefaultServiceLocation, settings.LocalServiceName, getFoobar, Set.empty, HttpResponse(StatusCodes.ServiceUnavailable)))
      upstreamRequests must be(50)

      ref ! ProxyRequest(postFoobar, HttpRequest(HttpMethods.POST))
      expectMsg(ProxiedResponse(settings.DefaultServiceLocation, settings.LocalServiceName, postFoobar, Set.empty, HttpResponse(StatusCodes.ServiceUnavailable)))
      upstreamRequests must be(50)
    }

    "allows one request through in the host half-open state" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      var upstreamRequests = 0
      var upstreamResponse = Future.successful(HttpResponse(StatusCodes.InternalServerError))
      val ref = TestActorRef(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => {
        upstreamRequests += 1
        upstreamResponse
      }) {
        override def buildFetcher() = testFetcher
      })

      // takes 25 back to back calls to trip an endpoint breaker, and 50 to trip the host breaker
      for (i <- 1 to 25) {
        ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      }
      for (i <- 1 to 25) {
        ref ! ProxyRequest(postFoobar, HttpRequest(HttpMethods.POST))
      }

      upstreamRequests must be(50)

      for (i <- 1 to 25) {
        expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.InternalServerError)))
      }
      for (i <- 1 to 25) {
        expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, postFoobar, Set.empty, HttpResponse(StatusCodes.InternalServerError)))
      }

      // host breaker is open
      ref ! ProxyRequest(getFizzbuzz, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(settings.DefaultServiceLocation, settings.LocalServiceName, getFizzbuzz, Set.empty, HttpResponse(StatusCodes.ServiceUnavailable)))
      upstreamRequests must be(50)

      // enough time for the cb to move to half-open
      virtualTime.advance(10.seconds)

      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.InternalServerError)))

      upstreamRequests must be(51)

      // re-opens upon failure from half open
      ref ! ProxyRequest(getFizzbuzz, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(settings.DefaultServiceLocation, settings.LocalServiceName, getFizzbuzz, Set.empty, HttpResponse(StatusCodes.ServiceUnavailable)))
      upstreamRequests must be(51)

      // enough time for the cb to move to half-open
      virtualTime.advance(10.seconds)
      upstreamResponse = Future.successful(HttpResponse(StatusCodes.OK))

      // moves to closed state upon success
      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.OK)))

      upstreamRequests must be(52)

      ref ! ProxyRequest(getFizzbuzz, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFizzbuzz, Set.empty, HttpResponse(StatusCodes.OK)))
      upstreamRequests must be(53)
    }

    "continues to serve requests after receiving shutdown message" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      val ref = TestActorRef(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => Future.successful(HttpResponse(StatusCodes.OK))) {
        override def buildFetcher() = testFetcher
      })

      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.OK)))

      ref ! ShutdownProxy

      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.OK)))
    }

    "eventually shuts down after receiving shutdown message" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      val ref = TestActorRef(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => Future.successful(HttpResponse(StatusCodes.OK))) {
        override def buildFetcher() = testFetcher
      })

      val probe = TestProbe()
      probe.watch(ref)

      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.OK)))

      ref ! ShutdownProxy

      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.OK)))

      virtualTime.advance(60.seconds)

      probe.expectTerminated(ref)
    }

    "scrubs requests before forwarding them upstream" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      var latestRequest = HttpRequest()
      val ref = TestActorRef(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, r => {
        latestRequest = r
        Future.successful(HttpResponse(StatusCodes.OK))
      }) {
        override def buildFetcher() = testFetcher
      })

      val request = HttpRequest(
        HttpMethods.GET,
        Uri("/foobar"),
        List(
          HttpHeaders.`Content-Type`(ContentTypes.`text/plain`),
          HttpHeaders.`Content-Length`(6),
          HttpHeaders.`Transfer-Encoding`("chunked"),
          RawHeader("Via", "1.1 ricky"),
          HttpHeaders.`X-Forwarded-For`("1.2.3.4"),
          HttpHeaders.`Remote-Address`("5.6.7.8"),
          HttpHeaders.`Accept-Encoding`(HttpEncodings.identity),
          HttpHeaders.Connection("close")
        ),
        "abc123"
      )
      ref ! ProxyRequest(getFoobar, request)
      expectMsg(ProxiedResponse(serviceLocation, endpointDetails.service, getFoobar, Set.empty, HttpResponse(StatusCodes.OK)))

      latestRequest mustEqual HttpRequest(
        HttpMethods.GET,
        Uri("/foobar"),
        List(
          RawHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8"),
          HttpHeaders.`Accept-Encoding`(HttpEncodings.gzip),
          HttpHeaders.Connection("Keep-Alive")
        ),
        "abc123"
      )
    }

    "broadcast state upon swagger fetch attempt" in {
      val parent = TestProbe()
      val testFetcher = new NeverFetcher
      val ref = TestActorRef(Props(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => Future.successful(HttpResponse(StatusCodes.OK))) {
        override def buildFetcher() = testFetcher
      }), parent.ref, "hostProxy")

      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            "(unknown)",
            "(unknown)",
            Swagger1ServiceType,
            ref,
            Map.empty,
            healthy=false,
            "fetching swagger docs",
            Set.empty
          )
        )
      )
    }

    "broadcast state upon swagger fetch failure" in {
      val parent = TestProbe()
      val testFetcher = new FailFetcher
      val ref = TestActorRef(Props(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => Future.successful(HttpResponse(StatusCodes.OK))) {
        override def buildFetcher() = testFetcher
      }), parent.ref, "hostProxy")

      // fetching message
      parent.receiveN(1)

      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            "(unknown)",
            "(unknown)",
            Swagger1ServiceType,
            ref,
            Map.empty,
            healthy=false,
            "failed to fetch swagger docs, retrying in 30 seconds",
            Set.empty
          )
        )
      )
    }

    "broadcast state upon swagger fetch success" in {
      val parent = TestProbe()
      val testFetcher = new FakeFetcher(endpointDetails)
      val ref = TestActorRef(Props(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => Future.successful(HttpResponse(StatusCodes.OK))) {
        override def buildFetcher() = testFetcher
      }), parent.ref, "hostProxy")

      // fetching message
      parent.receiveN(1)

      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=true,
            "serving",
            Set.empty
          )
        )
      )
    }

    "broadcast state upon endpoint breaker open" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      val parent = TestProbe()
      val upstreamResponse = Future.successful(HttpResponse(StatusCodes.InternalServerError))
      val ref = TestActorRef(Props(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => upstreamResponse) {
        override def buildFetcher() = testFetcher
      }), parent.ref, "hostProxy")

      // fetching, serving
      parent.receiveN(2)

      // takes 25 back to back calls to trip an endpoint breaker
      for (i <- 1 to 26) {
        ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      }

      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=true,
            "serving - 1 endpoint breakers tripped open",
            Set(getFoobar)
          )
        )
      )
    }

    "broadcast state upon endpoint breaker half-open -> open" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      val parent = TestProbe()
      val upstreamResponse = Future.successful(HttpResponse(StatusCodes.InternalServerError))
      val ref = TestActorRef(Props(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => upstreamResponse) {
        override def buildFetcher() = testFetcher
      }), parent.ref, "hostProxy")

      // takes 25 back to back calls to trip an endpoint breaker
      for (i <- 1 to 26) {
        ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      }

      // enough time to half open
      virtualTime.advance(10.seconds)

      // fetching, serving, open
      parent.receiveN(3)

      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=true,
            "serving",
            Set.empty
          )
        )
      )

      // transition back to open
      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))

      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=true,
            "serving - 1 endpoint breakers tripped open",
            Set(getFoobar)
          )
        )
      )
    }

    "broadcast state upon endpoint breaker half-open -> close" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      val parent = TestProbe()
      var upstreamResponse = Future.successful(HttpResponse(StatusCodes.InternalServerError))
      val ref = TestActorRef(Props(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => upstreamResponse) {
        override def buildFetcher() = testFetcher
      }), parent.ref, "hostProxy")

      // takes 25 back to back calls to trip an endpoint breaker
      for (i <- 1 to 26) {
        ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      }

      // enough time to half open
      virtualTime.advance(10.seconds)

      // fetching, serving, open
      parent.receiveN(3)

      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=true,
            "serving",
            Set.empty
          )
        )
      )

      upstreamResponse = Future.successful(HttpResponse(StatusCodes.OK))

      // transition back to closed
      ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))

      // actually... this seems redundant.  The state didn't technically change.
      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=true,
            "serving",
            Set.empty
          )
        )
      )
    }

    "broadcast state upon host breaker open" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      val parent = TestProbe()
      val upstreamResponse = Future.successful(HttpResponse(StatusCodes.InternalServerError))
      val ref = TestActorRef(Props(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => upstreamResponse) {
        override def buildFetcher() = testFetcher
      }), parent.ref, "hostProxy")

      // fetching, serving
      parent.receiveN(2)

      // takes 25 back to back calls to trip an endpoint breaker
      for (i <- 1 to 25) {
        ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      }
      // takes 50 back to back calls to trip the host breaker
      for (i <- 1 to 25) {
        ref ! ProxyRequest(postFoobar, HttpRequest(HttpMethods.POST))
      }

      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=true,
            "serving - 1 endpoint breakers tripped open",
            Set(getFoobar)
          )
        )
      )

      // apparently the inner host breaker trips first
      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=false,
            "serving - host breaker tripped open",
            Set(getFoobar, postFoobar, getFizzbuzz)
          )
        )
      )
      // and this is caused by the outer endpoint breaker tripping, even though the state doesn't change
      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=false,
            "serving - host breaker tripped open",
            Set(getFoobar, postFoobar, getFizzbuzz)
          )
        )
      )
    }

    "broadcast state upon host breaker half-open -> open" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      val parent = TestProbe()
      val upstreamResponse = Future.successful(HttpResponse(StatusCodes.InternalServerError))
      val ref = TestActorRef(Props(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => upstreamResponse) {
        override def buildFetcher() = testFetcher
      }), parent.ref, "hostProxy")

      // takes 25 back to back calls to trip an endpoint breaker
      for (i <- 1 to 25) {
        ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      }
      // takes 50 back to back calls to trip the host breaker
      for (i <- 1 to 25) {
        ref ! ProxyRequest(postFoobar, HttpRequest(HttpMethods.POST))
      }

      // fetching, serving, getFoobar open, host open, postFoobar open
      parent.receiveN(5)

      virtualTime.advance(10.seconds)

      // getFoobar halfOpen
      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=false,
            "serving - host breaker tripped open",
            Set(getFoobar, postFoobar, getFizzbuzz)
          )
        )
      )

      // host halfOpen
      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=true,
            "serving - 1 endpoint breakers tripped open",
            Set(postFoobar)
          )
        )
      )

      // postFoobar halfOpen
      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=true,
            "serving",
            Set.empty
          )
        )
      )

      ref ! ProxyRequest(getFizzbuzz, HttpRequest(HttpMethods.GET))

      // host open
      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=false,
            "serving - host breaker tripped open",
            Set(getFoobar, postFoobar, getFizzbuzz)
          )
        )
      )
    }

    "broadcast state upon host breaker half-open -> close" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      val parent = TestProbe()
      var upstreamResponse = Future.successful(HttpResponse(StatusCodes.InternalServerError))
      val ref = TestActorRef(Props(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => upstreamResponse) {
        override def buildFetcher() = testFetcher
      }), parent.ref, "hostProxy")

      // takes 25 back to back calls to trip an endpoint breaker
      for (i <- 1 to 25) {
        ref ! ProxyRequest(getFoobar, HttpRequest(HttpMethods.GET))
      }
      // takes 50 back to back calls to trip the host breaker
      for (i <- 1 to 25) {
        ref ! ProxyRequest(postFoobar, HttpRequest(HttpMethods.POST))
      }

      // fetching, serving, getFoobar open, host open, postFoobar open
      parent.receiveN(5)

      virtualTime.advance(10.seconds)

      // getFoobar halfOpen
      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=false,
            "serving - host breaker tripped open",
            Set(getFoobar, postFoobar, getFizzbuzz)
          )
        )
      )

      // host halfOpen
      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=true,
            "serving - 1 endpoint breakers tripped open",
            Set(postFoobar)
          )
        )
      )

      // postFoobar halfOpen
      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=true,
            "serving",
            Set.empty
          )
        )
      )

      upstreamResponse = Future.successful(HttpResponse(StatusCodes.OK))
      ref ! ProxyRequest(getFizzbuzz, HttpRequest(HttpMethods.GET))

      // host closed
      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            endpointDetails.endpoints,
            healthy=true,
            "serving",
            Set.empty
          )
        )
      )
    }

    "broadcast state upon proxy shutdown" in {
      val testFetcher = new FakeFetcher(endpointDetails)
      val parent = TestProbe()
      val upstreamResponse = Future.successful(HttpResponse(StatusCodes.InternalServerError))
      val ref = TestActorRef(Props(new HostProxy(serviceLocation, Swagger1ServiceType, settings.DefaultServiceLocation, _ => upstreamResponse) {
        override def buildFetcher() = testFetcher
      }), parent.ref, "hostProxy")

      // fetching, serving
      parent.receiveN(2)

      ref ! ShutdownProxy

      // host closed
      parent.expectMsg(
        UpstreamAggregatorMsgs.StateUpdated(
          serviceLocation,
          ProxyState(
            endpointDetails.service,
            endpointDetails.version,
            Swagger1ServiceType,
            ref,
            Map.empty,
            healthy=true,
            "draining",
            Set.empty
          )
        )
      )
    }
  }
}
