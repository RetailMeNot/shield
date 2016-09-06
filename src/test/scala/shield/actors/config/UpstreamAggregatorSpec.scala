package shield.actors.config

import akka.actor.{ActorContext, ActorSystem, Props, Terminated}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, MustMatchers, WordSpecLike}
import shield.actors.HostProxyMsgs
import shield.akka.helpers.VirtualScheduler
import shield.config._

import scala.concurrent.duration._

class UpstreamAggregatorSpec  extends TestKit(ActorSystem("testSystem", ConfigFactory.parseString(
  """
    |akka.scheduler.implementation = shield.akka.helpers.VirtualScheduler
  """.stripMargin).withFallback(ConfigFactory.load())))
  with WordSpecLike
  with MustMatchers
  with BeforeAndAfterEach
  with BeforeAndAfterAll {

  val scheduler = system.scheduler.asInstanceOf[VirtualScheduler]
  val settings = Settings(system)
  val domainSettings = new DomainSettings(settings.config.getConfigList("shield.domains").get(0), system)

  def virtualTime = scheduler.virtualTime

  override def beforeEach: Unit = {
    scheduler.reset()
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "WeightedProxyState" must {
    "not allow negative weights" in {
      intercept[IllegalArgumentException] {
        WeightedProxyState(-1, ProxyState("svc", "ver", Swagger1ServiceType, TestProbe().ref, Map.empty, healthy=true, "serving", Set.empty))
      }
    }
  }
  "ServiceDetails" must {
    "not allow negative weights" in {
      intercept[IllegalArgumentException] {
        ServiceDetails(Swagger1ServiceType, -1)
      }
    }
  }

  "UpstreamAggregator" must {

    "spawn upstream watcher on start" in {
      val parent = TestProbe()
      val proxy = TestProbe()
      var spawned = false
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = {
          spawned = true
          TestProbe().ref
        }

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          services.map(service => service._1 -> (UpstreamAggregator.defaultState(service._2, proxy.ref)))
        }
      }), parent.ref, "upstream-aggregator")

      spawned mustBe true
    }
  }

    "spawn weight watcher on start" in {
      var spawned = false
      TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = TestProbe().ref

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          services.map(service => service._1 -> (UpstreamAggregator.defaultState(service._2, TestProbe().ref)))
        }

        override def spawnWeightWatcher() = {
          spawned = true
          TestProbe().ref
        }
      }))

      spawned mustBe true
    }

    "create a hostproxy actor when a new service instance is encountered" in {
      val parent = TestProbe()
      val proxy = TestProbe()
      var latestType: ServiceType = null
      var latestLocation: ServiceLocation = null
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = {
          TestProbe().ref
        }

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()
          for (location <- services) {
            latestType = location._2.serviceType
            latestLocation = location._1
            context.watch(proxy.ref)
            map += location._1 -> (UpstreamAggregator.defaultState(location._2, proxy.ref))
          }
          map
        }
      }), parent.ref, "upstream-aggregator")

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        HttpServiceLocation("http://example.com") -> ServiceDetails(Swagger1ServiceType, 1)
      ))

      latestType must equal(Swagger1ServiceType)
      latestLocation must equal(HttpServiceLocation("http://example.com"))

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        HttpServiceLocation("http://example.com") -> ServiceDetails(Swagger1ServiceType, 1),
        HttpServiceLocation("https://example.com") -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      latestType must equal(Swagger2ServiceType)
      latestLocation must equal(HttpServiceLocation("https://example.com"))
    }

    "not create a hostproxy actor when a service instance is updated" in {
      val parent = TestProbe()
      val proxy = TestProbe()
      var latestType: ServiceType = null
      var latestLocation: ServiceLocation = null
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = {
          TestProbe().ref
        }

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()
          for (location <- services) {
            latestType = location._2.serviceType
            latestLocation = location._1
            context.watch(proxy.ref)
            map += location._1 -> (UpstreamAggregator.defaultState(location._2, proxy.ref))
          }
          map
        }
      }), parent.ref, "upstream-aggregator")

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        HttpServiceLocation("http://example.com") -> ServiceDetails(Swagger1ServiceType, 1)
      ))

      latestType must equal(Swagger1ServiceType)
      latestLocation must equal(HttpServiceLocation("http://example.com"))

      latestType = null
      latestLocation = null

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        HttpServiceLocation("http://example.com") -> ServiceDetails(Swagger1ServiceType, 1)
      ))

      latestType mustBe null
      latestLocation mustBe null

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        HttpServiceLocation("http://example.com") -> ServiceDetails(Swagger1ServiceType, 1),
        HttpServiceLocation("https://example.com") -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      latestType must equal(Swagger2ServiceType)
      latestLocation must equal(HttpServiceLocation("https://example.com"))

      latestType = null
      latestLocation = null

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        HttpServiceLocation("http://example.com") -> ServiceDetails(Swagger1ServiceType, 1),
        HttpServiceLocation("https://example.com") -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      latestType mustBe null
      latestLocation mustBe null
    }

    "update weight watcher when the set of discovered upstreams changes" in {
      val watcher = TestProbe()
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = TestProbe().ref

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()
          for (location <- services) {
            map += location._1 -> (UpstreamAggregator.defaultState(location._2, TestProbe().ref))
          }
          map
        }
        override def spawnWeightWatcher() = watcher.ref
      }))

      val m1 = Map[ServiceLocation, ServiceDetails](
        HttpServiceLocation("http://example.com") -> ServiceDetails(Swagger1ServiceType, 1)
      )
      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(m1)
      watcher.expectMsg(WeightWatcherMsgs.SetTargetWeights(m1))

      val m2 = Map[ServiceLocation, ServiceDetails](
        HttpServiceLocation("http://example.com") -> ServiceDetails(Swagger1ServiceType, 1),
        HttpServiceLocation("https://example.com") -> ServiceDetails(Swagger2ServiceType, 1)
      )
      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(m2)
      watcher.expectMsg(WeightWatcherMsgs.SetTargetWeights(m2))

      val m3 = Map[ServiceLocation, ServiceDetails](
        HttpServiceLocation("http://example.com") -> ServiceDetails(Swagger1ServiceType, 0),
        HttpServiceLocation("https://example.com") -> ServiceDetails(Swagger2ServiceType, 1)
      )
      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(m3)
      watcher.expectMsg(WeightWatcherMsgs.SetTargetWeights(m3))

      val m4 = Map[ServiceLocation, ServiceDetails](
        HttpServiceLocation("http://example.com") -> ServiceDetails(Swagger1ServiceType, 0)
      )
      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(m4)
      watcher.expectMsg(WeightWatcherMsgs.SetTargetWeights(m4))

    }

    "send a default proxystate for new services" in {
      val parent = TestProbe()
      val proxy = TestProbe()
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = {
          TestProbe().ref
        }

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()
          for (location <- services) {
            context.watch(proxy.ref)
            map += location._1 -> (UpstreamAggregator.defaultState(location._2, proxy.ref))
          }
          map
        }
      }), parent.ref, "upstream-aggregator")

      val swagger1Service = HttpServiceLocation("http://example.com")
      val swagger2Service = HttpServiceLocation("https://example.com")

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        swagger1Service -> ServiceDetails(Swagger1ServiceType, 1)
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy.ref, Map.empty, healthy = false, "initializing", Set.empty)))))

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        swagger1Service -> ServiceDetails(Swagger1ServiceType, 1),
        swagger2Service -> ServiceDetails(Swagger2ServiceType, 2)
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy.ref, Map.empty, healthy = false, "initializing", Set.empty)),
        swagger2Service -> WeightedProxyState(2, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy.ref, Map.empty, healthy = false, "initializing", Set.empty))
      )))
    }

    "combine and forward proxystate updates" in {
      val parent = TestProbe()
      val proxy = TestProbe()
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = {
          TestProbe().ref
        }

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()
          for (location <- services) {
            context.watch(proxy.ref)
            map += location._1 -> (UpstreamAggregator.defaultState(location._2, proxy.ref))
          }
          map
        }
      }), parent.ref, "upstream-aggregator")

      val swagger1Service = HttpServiceLocation("http://example.com")
      val swagger2Service = HttpServiceLocation("https://example.com")

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        swagger1Service -> ServiceDetails(Swagger1ServiceType, 1),
        swagger2Service -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy.ref, Map.empty, healthy = false, "initializing", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy.ref, Map.empty, healthy = false, "initializing", Set.empty))
      )))

      agg ! UpstreamAggregatorMsgs.StateUpdated(swagger1Service, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, Map.empty, healthy = true, "foobar", Set.empty))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, Map.empty, healthy = true, "foobar", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy.ref, Map.empty, healthy = false, "initializing", Set.empty))
      )))

      agg ! UpstreamAggregatorMsgs.StateUpdated(swagger2Service, ProxyState("upstream", "1.2.3", Swagger2ServiceType, proxy.ref, Map.empty, healthy = false, "fizzbuzz", Set.empty))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, Map.empty, healthy = true, "foobar", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger2ServiceType, proxy.ref, Map.empty, healthy = false, "fizzbuzz", Set.empty))
      )))

      agg ! UpstreamAggregatorMsgs.StateUpdated(swagger1Service, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, Map.empty, healthy = false, "borked", Set.empty))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, Map.empty, healthy = false, "borked", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("upstream", "1.2.3", Swagger2ServiceType, proxy.ref, Map.empty, healthy = false, "fizzbuzz", Set.empty))
      )))
    }

    "combine and forward proxy weight updates" in {
      val watcher = TestProbe()
      val proxy = TestProbe()
      val parent = TestProbe()
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = TestProbe().ref

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()
          for (location <- services) {
            context.watch(proxy.ref)
            map += location._1 -> (UpstreamAggregator.defaultState(location._2, proxy.ref))
          }
          map
        }

        override def spawnWeightWatcher() = watcher.ref
      }), parent.ref, "upstream-aggregator")
      val swagger1Service = HttpServiceLocation("http://example.com")
      val swagger1State = ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy.ref, Map.empty, healthy = false, "initializing", Set.empty)
      val swagger2Service = HttpServiceLocation("https://example.com")
      val swagger2State = ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy.ref, Map.empty, healthy = false, "initializing", Set.empty)

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        swagger1Service -> ServiceDetails(Swagger1ServiceType, 1),
        swagger2Service -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, swagger1State),
        swagger2Service -> WeightedProxyState(1, swagger2State)
      )))

      // nb: weightwatcher should always send all the hosts, but upstreamagg should be resilient if it doesn't
      agg ! WeightWatcherMsgs.SetWeights(Map(
        swagger1Service -> 0
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(0, swagger1State),
        swagger2Service -> WeightedProxyState(1, swagger2State)
      )))

      agg ! WeightWatcherMsgs.SetWeights(Map(
        swagger1Service -> 100,
        swagger2Service -> 100
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(100, swagger1State),
        swagger2Service -> WeightedProxyState(100, swagger2State)
      )))
    }

    "persists intermixed proxystate and weight updates" in {
      val watcher = TestProbe()
      val proxy = TestProbe()
      val parent = TestProbe()
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = TestProbe().ref

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()
          for (location <- services) {
            context.watch(proxy.ref)
            map += location._1 -> (UpstreamAggregator.defaultState(location._2, proxy.ref))
          }
          map
        }

        override def spawnWeightWatcher() = watcher.ref
      }), parent.ref, "upstream-aggregator")
      val swagger1Service = HttpServiceLocation("http://example.com")
      val swagger1State = ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy.ref, Map.empty, healthy = false, "initializing", Set.empty)
      val swagger2Service = HttpServiceLocation("https://example.com")
      val swagger2State = ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy.ref, Map.empty, healthy = false, "initializing", Set.empty)

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        swagger1Service -> ServiceDetails(Swagger1ServiceType, 1),
        swagger2Service -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, swagger1State),
        swagger2Service -> WeightedProxyState(1, swagger2State)
      )))

      agg ! WeightWatcherMsgs.SetWeights(Map(
        swagger1Service -> 100,
        swagger2Service -> 100
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(100, swagger1State),
        swagger2Service -> WeightedProxyState(100, swagger2State)
      )))

      agg ! UpstreamAggregatorMsgs.StateUpdated(swagger2Service, swagger2State.copy(status = "serving"))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(100, swagger1State),
        swagger2Service -> WeightedProxyState(100, swagger2State.copy(status = "serving"))
      )))

      agg ! WeightWatcherMsgs.SetWeights(Map(
        swagger1Service -> 3,
        swagger2Service -> 7
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(3, swagger1State),
        swagger2Service -> WeightedProxyState(7, swagger2State.copy(status = "serving"))
      )))
    }

    "ignore proxystate updates for unrecognized services" in {
      val parent = TestProbe()
      val proxy = TestProbe()
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = {
          TestProbe().ref
        }

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()
          for (location <- services) {
            context.watch(proxy.ref)
            map += location._1 -> (UpstreamAggregator.defaultState(location._2, proxy.ref))
          }
          map
        }
      }), parent.ref, "upstream-aggregator")

      val swagger1Service = HttpServiceLocation("http://example.com")
      agg ! UpstreamAggregatorMsgs.StateUpdated(swagger1Service, ProxyState("upstream", "1.2.3", Swagger1ServiceType, proxy.ref, Map.empty, healthy = true, "foobar", Set.empty))

      parent.msgAvailable mustBe false
    }

    "ignore proxy weight updates for unrecognized services" in {
      val watcher = TestProbe()
      val proxy = TestProbe()
      val parent = TestProbe()
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = TestProbe().ref

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()
          for (location <- services) {
            context.watch(proxy.ref)
            map += location._1 -> (UpstreamAggregator.defaultState(location._2, proxy.ref))
          }
          map
        }

        override def spawnWeightWatcher() = watcher.ref
      }), parent.ref, "upstream-aggregator")
      val swagger1Service = HttpServiceLocation("http://example.com")
      val swagger1State = ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy.ref, Map.empty, healthy = false, "initializing", Set.empty)
      val swagger2Service = HttpServiceLocation("https://example.com")
      val swagger2State = ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy.ref, Map.empty, healthy = false, "initializing", Set.empty)

      agg ! WeightWatcherMsgs.SetWeights(Map(
        swagger1Service -> 1,
        swagger2Service -> 1
      ))

      parent.msgAvailable mustBe false

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        swagger1Service -> ServiceDetails(Swagger1ServiceType, 1),
        swagger2Service -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, swagger1State),
        swagger2Service -> WeightedProxyState(1, swagger2State)
      )))

      agg ! WeightWatcherMsgs.SetWeights(Map(
        HttpServiceLocation("http://example.org") -> 1,
        HttpServiceLocation("https://example.org") -> 1
      ))

      parent.msgAvailable mustBe false

      agg ! WeightWatcherMsgs.SetWeights(Map(
        swagger1Service -> 2,
        swagger2Service -> 2
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(2, swagger1State),
        swagger2Service -> WeightedProxyState(2, swagger2State)
      )))
    }

    "send a upstreamstate update even if the first discovery is empty" in {
      val parent = TestProbe()
      val proxy = TestProbe()
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = {
          TestProbe().ref
        }

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()
          for (location <- services) {
            context.watch(proxy.ref)
            map += location._1 -> (UpstreamAggregator.defaultState(location._2, proxy.ref))
          }
          map
        }
      }), parent.ref, "upstream-aggregator")

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map.empty)

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map.empty))

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map.empty)

      parent.msgAvailable mustBe false
    }

    "signals hostproxy actors to shutdown when they're no longer used" in {
      val parent = TestProbe()
      val proxy1 = TestProbe()
      val proxy2 = TestProbe()
      val swagger1Service = HttpServiceLocation("http://example.com")
      val swagger2Service = HttpServiceLocation("https://example.com")
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = {
          TestProbe().ref
        }

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()

          for (service <- services) {
            val proxy = if (service._1 == swagger1Service) proxy1.ref else proxy2.ref
            context.watch(proxy)
            map += service._1 -> (UpstreamAggregator.defaultState(service._2, proxy))
          }
          map
        }
      }), parent.ref, "config-watcher")

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        swagger1Service -> ServiceDetails(Swagger1ServiceType, 1),
        swagger2Service -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy1.ref, Map.empty, healthy = false, "initializing", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "initializing", Set.empty))
      )))

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        swagger2Service -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      proxy1.expectMsg(HostProxyMsgs.ShutdownProxy)
      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy1.ref, Map.empty, healthy = false, "unregistering", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "initializing", Set.empty))
      )))

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map.empty)

      proxy2.expectMsg(HostProxyMsgs.ShutdownProxy)
      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy1.ref, Map.empty, healthy = false, "unregistering", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "unregistering", Set.empty))
      )))
    }

    "force stop hostproxy actors that don't autoterminate" in {
      val parent = TestProbe()
      val proxy1 = TestProbe()
      val proxy2 = TestProbe()
      val deathProbe = TestProbe()
      deathProbe.watch(proxy1.ref)
      deathProbe.watch(proxy2.ref)
      val swagger1Service = HttpServiceLocation("http://example.com")
      val swagger2Service = HttpServiceLocation("https://example.com")
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = {
          TestProbe().ref
        }

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()

          for (service <- services) {
            val proxy = if (service._1 == swagger1Service) proxy1.ref else proxy2.ref
            context.watch(proxy)
            map += service._1 -> (UpstreamAggregator.defaultState(service._2, proxy))
          }
          map
        }
      }), parent.ref, "config-watcher")

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        swagger1Service -> ServiceDetails(Swagger1ServiceType, 1),
        swagger2Service -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy1.ref, Map.empty, healthy = false, "initializing", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "initializing", Set.empty))
      )))

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map.empty)

      proxy1.expectMsg(HostProxyMsgs.ShutdownProxy)
      proxy2.expectMsg(HostProxyMsgs.ShutdownProxy)
      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy1.ref, Map.empty, healthy = false, "unregistering", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "unregistering", Set.empty))
      )))

      virtualTime.advance(60.seconds)

      deathProbe.expectTerminated(proxy1.ref)
      deathProbe.expectTerminated(proxy2.ref)
    }

    "remove hostproxies from upstreamstate when they terminate" in {
      val parent = TestProbe()
      val proxy1 = TestProbe()
      val proxy2 = TestProbe()
      val swagger1Service = HttpServiceLocation("http://example.com")
      val swagger2Service = HttpServiceLocation("https://example.com")
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = {
          TestProbe().ref
        }

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()

          for (service <- services) {
            val proxy = if (service._1 == swagger1Service) proxy1.ref else proxy2.ref
            context.watch(proxy)
            map += service._1 -> (UpstreamAggregator.defaultState(service._2, proxy))
          }
          map
        }
      }), parent.ref, "config-watcher")

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        swagger1Service -> ServiceDetails(Swagger1ServiceType, 1),
        swagger2Service -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy1.ref, Map.empty, healthy = false, "initializing", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "initializing", Set.empty))
      )))

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map.empty)

      proxy1.expectMsg(HostProxyMsgs.ShutdownProxy)
      proxy2.expectMsg(HostProxyMsgs.ShutdownProxy)
      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy1.ref, Map.empty, healthy = false, "unregistering", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "unregistering", Set.empty))
      )))

      system.stop(proxy1.ref)

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "unregistering", Set.empty))
      )))

      system.stop(proxy2.ref)

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map.empty))
    }

    "handle hostproxies terminating before being deregistered" in {
      val parent = TestProbe()
      val proxy1 = TestProbe()
      val proxy2 = TestProbe()
      val swagger1Service = HttpServiceLocation("http://example.com")
      val swagger2Service = HttpServiceLocation("https://example.com")
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = {
          TestProbe().ref
        }

        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()

          for (service <- services) {
            val proxy = if (service._1 == swagger1Service) proxy1.ref else proxy2.ref
            context.watch(proxy)
            map += service._1 -> (UpstreamAggregator.defaultState(service._2, proxy))
          }
          map
        }
      }), parent.ref, "config-watcher")

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        swagger1Service -> ServiceDetails(Swagger1ServiceType, 1),
        swagger2Service -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy1.ref, Map.empty, healthy = false, "initializing", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "initializing", Set.empty))
      )))

      system.stop(proxy1.ref)

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "initializing", Set.empty))
      )))

      system.stop(proxy2.ref)

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map.empty))
    }

    "recreate a hostproxy for a re-introduced service instance that was previously terminated" in {
      val parent = TestProbe()
      var proxy1 = TestProbe()
      var proxy2 = TestProbe()
      val swagger1Service = HttpServiceLocation("http://example.com")
      val swagger2Service = HttpServiceLocation("https://example.com")
      val agg = TestActorRef(Props(new UpstreamAggregator(domainSettings) {
        override def spawnUpstreamWatcher() = {
          TestProbe().ref
        }
        override def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext) = {
          var map = Map[ServiceLocation, WeightedProxyState]()

          for (service <- services) {
            val proxy = if (service._1 == swagger1Service) proxy1.ref else proxy2.ref
            context.watch(proxy)
            map += service._1 -> (UpstreamAggregator.defaultState(service._2, proxy))
          }
          map
        }
      }), parent.ref, "config-watcher")

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        swagger1Service -> ServiceDetails(Swagger1ServiceType, 1),
        swagger2Service -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy1.ref, Map.empty, healthy = false, "initializing", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "initializing", Set.empty))
      )))

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map.empty)

      proxy1.expectMsg(HostProxyMsgs.ShutdownProxy)
      proxy2.expectMsg(HostProxyMsgs.ShutdownProxy)
      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy1.ref, Map.empty, healthy = false, "unregistering", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "unregistering", Set.empty))
      )))

      system.stop(proxy1.ref)

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "unregistering", Set.empty))
      )))

      system.stop(proxy2.ref)

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map.empty))

      // reset, since they were stopped
      proxy1 = TestProbe()
      proxy2 = TestProbe()

      agg ! UpstreamAggregatorMsgs.DiscoveredUpstreams(Map(
        swagger1Service -> ServiceDetails(Swagger1ServiceType, 1),
        swagger2Service -> ServiceDetails(Swagger2ServiceType, 1)
      ))

      parent.expectMsg(ConfigWatcherMsgs.UpstreamsUpdated(Map(
        swagger1Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger1ServiceType, proxy1.ref, Map.empty, healthy = false, "initializing", Set.empty)),
        swagger2Service -> WeightedProxyState(1, ProxyState("(unknown)", "(unknown)", Swagger2ServiceType, proxy2.ref, Map.empty, healthy = false, "initializing", Set.empty))
      )))
    }
  }
