package shield.actors.config

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import io.swagger.parser.SwaggerParser
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, MustMatchers, WordSpecLike}
import shield.actors.{Middleware, ShieldActorMsgs}
import shield.akka.helpers.VirtualScheduler
import shield.config.{HttpServiceLocation, ServiceLocation, Swagger1ServiceType, Swagger2ServiceType}
import shield.proxying.{AkkaBalancer, RoundRobinBalancerBuilder}
import shield.config._
import shield.proxying.AkkaBalancer
import shield.routing._
import spray.http._
import spray.json._

import scala.concurrent.duration._

class ConfigWatcherSpec  extends TestKit(ActorSystem("testSystem", ConfigFactory.parseString(
  """
    |akka.scheduler.implementation = shield.akka.helpers.VirtualScheduler
  """.stripMargin).withFallback(ConfigFactory.load())))
  with WordSpecLike
  with MustMatchers
  with BeforeAndAfterEach
  with BeforeAndAfterAll {
  import HealthcheckProtocol._

  val scheduler = system.scheduler.asInstanceOf[VirtualScheduler]
  def virtualTime = scheduler.virtualTime
  val settings = Settings(system)
  val domainSettings = new DomainSettings(settings.config.getConfigList("shield.domains").get(0), system)


  override def beforeEach: Unit = {
    scheduler.reset()
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  def getHealthcheck(router: RequestRouter) : (StatusCode, HealthcheckInfo) = {
    val details = router.route(HttpRequest(uri = Uri("/healthcheck"))).left.get
    (details.response.status, details.response.entity.asString.parseJson.convertTo[HealthcheckInfo])
  }

  "ConfigWatcher" should {
    val serviceLocation = settings.DefaultServiceLocation
    val getFoobar = EndpointTemplate(HttpMethods.GET, Path("/foobar"))
    val getFoobarRequest = HttpRequest(HttpMethods.GET, uri = Uri("/foobar"))
    val postFoobar = EndpointTemplate(HttpMethods.POST, Path("/foobar"))
    val getFizzbuzz = EndpointTemplate(HttpMethods.GET, Path("/fizzbuzz"))

    val endpointMap = Map(
      getFoobar -> EndpointDetails.empty,
      postFoobar -> EndpointDetails.empty,
      getFizzbuzz -> EndpointDetails.empty
    )

    "spawns child config helpers on startup" in {
      var upstreamSpawned = false
      var middlewareSpawned = false
      var listenersSpawned = false

      val watcher = TestActorRef(new ConfigWatcher(domainSettings, TestProbe().ref) {
        override def spawnUpstreamWatcher() = {
          upstreamSpawned = true
          TestProbe().ref
        }
        override def spawnMiddlewareBuilders() = {
          middlewareSpawned = true
          List.empty
        }
        override def spawnListenerBuilders() = {
          listenersSpawned = true
          Set.empty
        }
      })

      upstreamSpawned mustBe true
      middlewareSpawned mustBe true
      listenersSpawned mustBe true
    }

    "rebuild the router when the middleware changes" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = List("test-middleware", "other-middleware")
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val middle = TestProbe()
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))))
      shieldActor.msgAvailable mustBe true
      shieldActor.receiveOne(100.millis)

      watcher ! ConfigWatcherMsgs.MiddlewareUpdated(Middleware("other-middleware", 1.second, middle.ref))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination1 = msg1.router.route(getFoobarRequest)
      destination1.isRight mustBe true
      destination1.right.get.allMiddleware must equal (List(Middleware("other-middleware", 1.second, middle.ref)))

      watcher ! ConfigWatcherMsgs.MiddlewareUpdated(Middleware("test-middleware", 1.second, middle.ref))

      shieldActor.msgAvailable mustBe true
      val msg2 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination2 = msg2.router.route(getFoobarRequest)
      destination2.isRight mustBe true
      destination2.right.get.allMiddleware must equal (List(Middleware("test-middleware", 1.second, middle.ref), Middleware("other-middleware", 1.second, middle.ref)))

    }

    "destroy old middleware actors upon update" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = List("test-middleware")
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val middle = TestProbe()
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))))
      shieldActor.msgAvailable mustBe true
      shieldActor.receiveOne(100.millis)

      watcher ! ConfigWatcherMsgs.MiddlewareUpdated(Middleware("test-middleware", 1.second, middle.ref))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]

      val newMiddle = TestProbe()
      val deathWatcher = TestProbe()
      deathWatcher.watch(middle.ref)

      watcher ! ConfigWatcherMsgs.MiddlewareUpdated(Middleware("test-middleware", 1.second, newMiddle.ref))

      middle.ref ! "foo"
      middle.expectMsg("foo")

      virtualTime.advance(10.seconds)

      middle.ref ! "foo"
      middle.msgAvailable mustBe false
      deathWatcher.expectTerminated(middle.ref)

      shieldActor.msgAvailable mustBe true
      val msg2 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination2 = msg2.router.route(getFoobarRequest)
      destination2.isRight mustBe true
      destination2.right.get.allMiddleware must equal (List(Middleware("test-middleware", 1.second, newMiddle.ref)))
    }

    "does not destroy old middleware actor upon update if it's the same actor" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = List("test-middleware")
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val middle = TestProbe()
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))))
      shieldActor.msgAvailable mustBe true
      shieldActor.receiveOne(100.millis)

      watcher ! ConfigWatcherMsgs.MiddlewareUpdated(Middleware("test-middleware", 1.second, middle.ref))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]

      watcher ! ConfigWatcherMsgs.MiddlewareUpdated(Middleware("test-middleware", 1.second, middle.ref))

      middle.ref ! "foo"
      middle.expectMsg("foo")

      virtualTime.advance(10.seconds)

      middle.ref ! "foo"
      middle.expectMsg("foo")

      shieldActor.msgAvailable mustBe true
      val msg2 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination2 = msg2.router.route(getFoobarRequest)
      destination2.isRight mustBe true
      destination2.right.get.allMiddleware must equal (List(Middleware("test-middleware", 1.second, middle.ref)))
    }

    "ignores unrecognized middleware" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = List("test-middleware")
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val middle = TestProbe()

      watcher ! ConfigWatcherMsgs.MiddlewareUpdated(Middleware("foobar", 1.second, middle.ref))

      shieldActor.msgAvailable mustBe false
    }

    "notify shield when the listener changes" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set("test-listener", "other-listener")
      }), shieldActor.ref, "config-watcher")
      val listener = TestProbe()

      watcher ! ConfigWatcherMsgs.ListenerUpdated("test-listener", listener.ref)

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.ListenersUpdated]

      msg1.listeners must equal (Map("test-listener" -> listener.ref))

      watcher ! ConfigWatcherMsgs.ListenerUpdated("other-listener", listener.ref)

      shieldActor.msgAvailable mustBe true
      val msg2 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.ListenersUpdated]

      msg2.listeners must equal (Map("other-listener" -> listener.ref, "test-listener" -> listener.ref))
    }

    "destroy old listener actors upon update" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set("test-listener")
      }), shieldActor.ref, "config-watcher")
      val listener = TestProbe()

      watcher ! ConfigWatcherMsgs.ListenerUpdated("test-listener", listener.ref)

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.ListenersUpdated]
      msg1.listeners must equal (Map("test-listener" -> listener.ref))

      val newListener = TestProbe()
      val deathWatcher = TestProbe()
      deathWatcher.watch(listener.ref)

      watcher ! ConfigWatcherMsgs.ListenerUpdated("test-listener", newListener.ref)

      listener.ref ! "foo"
      listener.expectMsg("foo")

      virtualTime.advance(10.seconds)

      listener.ref ! "foo"
      listener.msgAvailable mustBe false
      deathWatcher.expectTerminated(listener.ref)

      shieldActor.msgAvailable mustBe true
      val msg2 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.ListenersUpdated]

      msg2.listeners must equal (Map("test-listener" -> newListener.ref))
    }

    "does not destroy old listener actor upon update if it's the same actor" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set("test-listener")
      }), shieldActor.ref, "config-watcher")
      val listener = TestProbe()

      watcher ! ConfigWatcherMsgs.ListenerUpdated("test-listener", listener.ref)

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.ListenersUpdated]
      msg1.listeners must equal (Map("test-listener" -> listener.ref))

      watcher ! ConfigWatcherMsgs.ListenerUpdated("test-listener", listener.ref)

      listener.ref ! "foo"
      listener.expectMsg("foo")

      virtualTime.advance(10.seconds)

      listener.ref ! "foo"
      listener.expectMsg("foo")

      shieldActor.msgAvailable mustBe true
      val msg2 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.ListenersUpdated]
      msg2.listeners must equal (Map("test-listener" -> listener.ref))
    }

    "ignores unrecognized listeners" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set("test-listener")
      }), shieldActor.ref, "config-watcher")
      val listener = TestProbe()

      watcher ! ConfigWatcherMsgs.ListenerUpdated("foobar", listener.ref)

      shieldActor.msgAvailable mustBe false
    }

    "rebuild the router when the upstream proxy state changes" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination1 = msg1.router.route(getFoobarRequest)
      destination1.isRight mustBe true
      destination1.right.get.template must equal (getFoobar)
      destination1.right.get.possibleDetails must equal (List(EndpointDetails.empty))

      val newDetails = EndpointDetails(
        Set(QueryParam("foo"), HeaderParam("Bar")),
        Set.empty,
        Set(MediaTypes.`application/json`),
        Set.empty,
        Set.empty
      )
      val updatedMap = endpointMap + (getFoobar -> newDetails)
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, updatedMap , healthy=true, "healthy", Set.empty))))

      shieldActor.msgAvailable mustBe true
      val msg2 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination2 = msg2.router.route(getFoobarRequest)
      destination2.isRight mustBe true
      destination2.right.get.template must equal (getFoobar)
      destination2.right.get.possibleDetails must equal (List(newDetails))
    }

    "rebuild the router when the upstream weights change" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination1 = msg1.router.route(getFoobarRequest)
      destination1.isRight mustBe true
      destination1.right.get.template must equal (getFoobar)
      destination1.right.get.possibleDetails must equal (List(EndpointDetails.empty))

      shieldActor.msgAvailable mustBe false
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(10, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap , healthy=true, "healthy", Set.empty))))
      shieldActor.msgAvailable mustBe true

      val msg2 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination2 = msg2.router.route(getFoobarRequest)
      destination2.isRight mustBe true
      destination2.right.get.template must equal (getFoobar)
      destination2.right.get.possibleDetails must equal (List(EndpointDetails.empty))
    }

    "build the router to not 404 when all upstreams for an endpoint are unhealthy" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(
        serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty)),
        HttpServiceLocation("http://example.org") -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))
      ))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination1 = msg1.router.route(getFoobarRequest)
      destination1.isRight mustBe true
      destination1.right.get.template must equal (getFoobar)
      destination1.right.get.possibleDetails must equal (List(EndpointDetails.empty, EndpointDetails.empty))

      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(
        serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "somewhat healthy", Set(getFoobar))),
        HttpServiceLocation("http://example.org") -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))
      ))

      shieldActor.msgAvailable mustBe true
      val msg2 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination2 = msg2.router.route(getFoobarRequest)
      destination2.isRight mustBe true
      destination2.right.get.template must equal (getFoobar)
      destination2.right.get.possibleDetails must equal (List(EndpointDetails.empty))

      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(
        serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "somewhat healthy", Set(getFoobar))),
        HttpServiceLocation("http://example.org") -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "somewhat healthy", Set(getFoobar)))
      ))

      shieldActor.msgAvailable mustBe true
      val msg3 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination3 = msg3.router.route(getFoobarRequest)
      destination3.isRight mustBe true
      destination3.right.get.template must equal (getFoobar)
      destination3.right.get.possibleDetails must equal (List(EndpointDetails.empty, EndpointDetails.empty))
    }

    "not rebuild the router when the upstream proxy state does not change" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination1 = msg1.router.route(getFoobarRequest)
      destination1.isRight mustBe true
      destination1.right.get.template must equal (getFoobar)
      destination1.right.get.possibleDetails must equal (List(EndpointDetails.empty))

      val newDetails = EndpointDetails(
        Set(QueryParam("foo"), HeaderParam("Bar")),
        Set.empty,
        Set(MediaTypes.`application/json`),
        Set.empty,
        Set.empty
      )
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap , healthy=true, "healthy", Set.empty))))

      shieldActor.msgAvailable mustBe false
    }

    "clean up the ProxyBuilder when rebuilding the router" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination1 = msg1.router.route(getFoobarRequest)
      destination1.isRight mustBe true
      destination1.right.get.template must equal (getFoobar)
      val originalRouter = destination1.right.get.upstreamBalancer.asInstanceOf[AkkaBalancer].balancer

      val deathProbe = TestProbe()
      deathProbe.watch(originalRouter)

      // same request gets the same router
      val destination2 = msg1.router.route(getFoobarRequest)
      destination2.isRight mustBe true
      destination2.right.get.template must equal (getFoobar)
      destination2.right.get.upstreamBalancer.asInstanceOf[AkkaBalancer].balancer must equal (originalRouter)

      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap , healthy=true, "healthier", Set.empty))))

      shieldActor.msgAvailable mustBe true
      val msg2 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination3 = msg2.router.route(getFoobarRequest)
      destination3.isRight mustBe true
      destination3.right.get.template must equal (getFoobar)
      destination3.right.get.upstreamBalancer.asInstanceOf[AkkaBalancer].balancer must not equal originalRouter

      virtualTime.advance(60.seconds)

      deathProbe.expectTerminated(originalRouter)

      // proxy still wasn't killed
      proxy.ref ! "foo"
      proxy.expectMsg("foo")
    }

    "include swagger docs in the router for both health and unhealthy endpoints" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set(getFizzbuzz)))))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination1 = msg1.router.route(HttpRequest(uri = Uri("/spec")))
      destination1.isLeft mustBe true
      destination1.left.get.response.status must equal (StatusCodes.OK)
      val docString = destination1.left.get.response.entity.asString
      val parsed = new SwaggerParser().parse(docString)
      parsed must not be null
      parsed.getPaths.containsKey("/foobar") mustBe true
      parsed.getPaths.containsKey("/fizzbuzz") mustBe true
    }

    "include a metrics endpoint in the router" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val destination1 = msg1.router.route(HttpRequest(uri = Uri("/metrics")))
      destination1.isLeft mustBe true
      destination1.left.get.response.status must equal (StatusCodes.OK)
      destination1.left.get.response.entity.asString.parseJson.asJsObject must not be null
    }

    "include a healthcheck endpoint in the router" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val (status, healthcheck) = getHealthcheck(msg1.router)

      status must equal (StatusCodes.OK)
      healthcheck.listeners.ready must have length 0
      healthcheck.listeners.pending must have length 0
      healthcheck.middleware.ready must have length 0
      healthcheck.middleware.pending must have length 0
      healthcheck.upstreams must have length 1
      healthcheck.upstreams.head.service must equal ("upstream")
      healthcheck.upstreams.head.version must equal ("1.2.3")
      healthcheck.upstreams.head.proxyType must equal ("swagger1")
      healthcheck.upstreams.head.baseUrl must equal (serviceLocation.baseUrl.toString())
      healthcheck.upstreams.head.endpoints must equal (endpointMap.size)
      healthcheck.upstreams.head.healthy mustBe true
      healthcheck.upstreams.head.status must equal ("healthy")
      healthcheck.upstreams.head.unhealthyEndpoints must have length 0
    }

    "include a healthcheck that reports middleware status correctly" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = List("test-middleware", "other-middleware")
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val middle = TestProbe()

      watcher ! ConfigWatcherMsgs.MiddlewareUpdated(Middleware("other-middleware", 1.second, middle.ref))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val (_, healthcheck1) = getHealthcheck(msg1.router)
      healthcheck1.middleware.ready must equal (List("other-middleware"))
      healthcheck1.middleware.pending must equal (List("test-middleware"))

      watcher ! ConfigWatcherMsgs.MiddlewareUpdated(Middleware("test-middleware", 1.second, middle.ref))

      shieldActor.msgAvailable mustBe true
      val msg2 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val (_, healthcheck2) = getHealthcheck(msg2.router)
      healthcheck2.middleware.ready must equal (List("other-middleware", "test-middleware"))
      healthcheck2.middleware.pending must equal (Nil)
    }

    "include a healthcheck that reports listener status correctly" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        // here to trigger router rebuilds
        override def spawnMiddlewareBuilders() = List("test-middleware")
        override def spawnListenerBuilders() = Set("test-listener", "other-listener")
      }), shieldActor.ref, "config-watcher")
      val middle = TestProbe()
      val listener = TestProbe()

      watcher ! ConfigWatcherMsgs.MiddlewareUpdated(Middleware("test-middleware", 1.second, middle.ref))
      shieldActor.msgAvailable mustBe true
      val router = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated].router

      val (_, healthcheck1) = getHealthcheck(router)
      healthcheck1.listeners.ready must equal (Nil)
      healthcheck1.listeners.pending.toSet must equal (Set("test-listener", "other-listener"))

      watcher ! ConfigWatcherMsgs.ListenerUpdated("test-listener", listener.ref)

      val (_, healthcheck2) = getHealthcheck(router)
      healthcheck2.listeners.ready must equal (List("test-listener"))
      healthcheck2.listeners.pending must equal (List("other-listener"))

      watcher ! ConfigWatcherMsgs.ListenerUpdated("other-listener", listener.ref)

      val (_, healthcheck3) = getHealthcheck(router)
      healthcheck3.listeners.ready.toSet must equal (Set("test-listener", "other-listener"))
      healthcheck3.listeners.pending must equal (Nil)
    }

    "include a healthcheck that reports upstream status correctly" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val (_, healthcheck1) = getHealthcheck(msg1.router)

      healthcheck1.upstreams must equal (List(UpstreamHealthInfo(
        "upstream",
        "1.2.3",
        "swagger1",
        serviceLocation.baseUrl.toString(),
        "healthy",
        weight=1,
        healthy=true,
        endpointMap.size,
        Nil
      )))

      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy~ish", Set(getFoobar)))))

      shieldActor.msgAvailable mustBe true
      val msg2 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val (_, healthcheck2) = getHealthcheck(msg2.router)

      healthcheck2.upstreams must equal (List(UpstreamHealthInfo(
        "upstream",
        "1.2.3",
        "swagger1",
        serviceLocation.baseUrl.toString(),
        "healthy~ish",
        weight=1,
        healthy=true,
        endpointMap.size,
        List(EndpointTemplateInfo(getFoobar.method.toString(), getFoobar.path.toString))
      )))

      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=false, "borked", Set(getFoobar, postFoobar, getFizzbuzz)))))

      shieldActor.msgAvailable mustBe true
      val msg3 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val (_, healthcheck3) = getHealthcheck(msg3.router)

      healthcheck3.upstreams must equal (List(UpstreamHealthInfo(
        "upstream",
        "1.2.3",
        "swagger1",
        serviceLocation.baseUrl.toString(),
        "borked",
        weight=1,
        healthy=false,
        endpointMap.size,
        List(
          EndpointTemplateInfo(getFoobar.method.toString(), getFoobar.path.toString),
          EndpointTemplateInfo(postFoobar.method.toString(), postFoobar.path.toString),
          EndpointTemplateInfo(getFizzbuzz.method.toString(), getFizzbuzz.path.toString)
        )
      )))
    }

    "include a healthcheck that reports upstream weight correctly" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = Nil
        override def spawnListenerBuilders() = Set.empty
      }), shieldActor.ref, "config-watcher")
      val proxy = TestProbe()
      val otherServiceLocation = HttpServiceLocation("http://example.org")
      val swagger1State = ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty)
      val swagger2State = ProxyState("upstream", "1.2.3", Swagger2ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty)

      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(
        serviceLocation -> WeightedProxyState(1, swagger1State)
      ))

      shieldActor.msgAvailable mustBe true
      val msg1 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val (_, healthcheck1) = getHealthcheck(msg1.router)

      healthcheck1.upstreams must equal (List(UpstreamHealthInfo(
        "upstream",
        "1.2.3",
        "swagger1",
        serviceLocation.baseUrl.toString(),
        "healthy",
        weight=1,
        healthy=true,
        endpointMap.size,
        Nil
      )))

      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(
        serviceLocation -> WeightedProxyState(1, swagger1State),
        otherServiceLocation -> WeightedProxyState(1, swagger2State)
      ))

      shieldActor.msgAvailable mustBe true
      val msg2 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val (_, healthcheck2) = getHealthcheck(msg2.router)

      healthcheck2.upstreams.toSet must equal (Set(
        UpstreamHealthInfo(
          "upstream",
          "1.2.3",
          "swagger1",
          serviceLocation.baseUrl.toString(),
          "healthy",
          weight=1,
          healthy=true,
          endpointMap.size,
          Nil
        ),
        UpstreamHealthInfo(
          "upstream",
          "1.2.3",
          "swagger2",
          otherServiceLocation.baseUrl.toString(),
          "healthy",
          weight=1,
          healthy=true,
          endpointMap.size,
          Nil
        )
      ))

      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(
        serviceLocation -> WeightedProxyState(100, swagger1State),
        otherServiceLocation -> WeightedProxyState(50, swagger2State)
      ))

      shieldActor.msgAvailable mustBe true
      val msg3 = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated]
      val (_, healthcheck3) = getHealthcheck(msg3.router)


      healthcheck3.upstreams.toSet must equal (Set(
        UpstreamHealthInfo(
          "upstream",
          "1.2.3",
          "swagger1",
          serviceLocation.baseUrl.toString(),
          "healthy",
          weight=100,
          healthy=true,
          endpointMap.size,
          Nil
        ),
        UpstreamHealthInfo(
          "upstream",
          "1.2.3",
          "swagger2",
          otherServiceLocation.baseUrl.toString(),
          "healthy",
          weight=50,
          healthy=true,
          endpointMap.size,
          Nil
        )
      ))
    }

    "wait until all middleware are ready before returning a 200 OK healthcheck" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = List("test-middleware")
        override def spawnListenerBuilders() = Set("test-listener")
      }), shieldActor.ref, "config-watcher")
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy = true, "healthy", Set.empty))))
      val listener = TestProbe()
      watcher ! ConfigWatcherMsgs.ListenerUpdated("test-listener", listener.ref)

      shieldActor.msgAvailable mustBe true
      val router = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated].router
      val (status, _) = getHealthcheck(router)
      status must equal(StatusCodes.ServiceUnavailable)

      val middle = TestProbe()
      watcher ! ConfigWatcherMsgs.MiddlewareUpdated(Middleware("test-middleware", 1.second, middle.ref))

      val (status2, _) = getHealthcheck(router)
      status2 must equal(StatusCodes.OK)
    }

    "wait until all listeners are ready before returning a 200 OK healthcheck" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = List("test-middleware")
        override def spawnListenerBuilders() = Set("test-listener")
      }), shieldActor.ref, "config-watcher")
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy = true, "healthy", Set.empty))))
      val middle = TestProbe()
      watcher ! ConfigWatcherMsgs.MiddlewareUpdated(Middleware("test-middleware", 1.second, middle.ref))

      shieldActor.msgAvailable mustBe true
      val router = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated].router
      val (status, _) = getHealthcheck(router)
      status must equal(StatusCodes.ServiceUnavailable)

      val listener = TestProbe()
      watcher ! ConfigWatcherMsgs.ListenerUpdated("test-listener", listener.ref)

      val (status2, _) = getHealthcheck(router)
      status2 must equal(StatusCodes.OK)
    }

    "wait until all listeners are healthy before returning a 200 OK healthcheck and then stays healthy" in {
      val shieldActor = TestProbe()
      val watcher = TestActorRef(Props(new ConfigWatcher(domainSettings, shieldActor.ref) {
        override def spawnUpstreamWatcher() = TestProbe().ref
        override def spawnMiddlewareBuilders() = List("test-middleware")
        override def spawnListenerBuilders() = Set("test-listener")
      }), shieldActor.ref, "config-watcher")
      val proxy = TestProbe()
      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(
        serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, Map.empty, healthy = false, "starting", Set.empty)),
        HttpServiceLocation("https://example.com") -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty)),
        HttpServiceLocation("http://example.org") -> WeightedProxyState(1, ProxyState("upstream2", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=false, "starting", Set.empty))
      ))
      val middle = TestProbe()
      watcher ! ConfigWatcherMsgs.MiddlewareUpdated(Middleware("test-middleware", 1.second, middle.ref))
      val listener = TestProbe()
      watcher ! ConfigWatcherMsgs.ListenerUpdated("test-listener", listener.ref)

      shieldActor.msgAvailable mustBe true
      val router = shieldActor.receiveOne(100.millis).asInstanceOf[ShieldActorMsgs.RouterUpdated].router
      val (status, _) = getHealthcheck(router)
      status must equal(StatusCodes.ServiceUnavailable)

      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(
        serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy = false, "starting", Set.empty)),
        HttpServiceLocation("https://example.com") -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty)),
        HttpServiceLocation("http://example.org") -> WeightedProxyState(1, ProxyState("upstream2", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))
      ))

      val (status2, _) = getHealthcheck(router)
      status2 must equal(StatusCodes.ServiceUnavailable)

      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(
        serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy = true, "healthy", Set.empty)),
        HttpServiceLocation("https://example.com") -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty)),
        HttpServiceLocation("http://example.org") -> WeightedProxyState(1, ProxyState("upstream2", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=true, "healthy", Set.empty))
      ))

      val (status3, _) = getHealthcheck(router)
      status3 must equal(StatusCodes.OK)

      watcher ! ConfigWatcherMsgs.UpstreamsUpdated(Map(
        serviceLocation -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy = false, "borked", Set.empty)),
        HttpServiceLocation("http://example.com") -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=false, "borked", Set.empty)),
        HttpServiceLocation("http://example.org") -> WeightedProxyState(1, ProxyState("upstream2", "1.2.3", Swagger1ServiceType, proxy.ref, endpointMap, healthy=false, "borked", Set.empty))
      ))

      val (status4, _) = getHealthcheck(router)
      status4 must equal(StatusCodes.OK)
    }
  }
}
