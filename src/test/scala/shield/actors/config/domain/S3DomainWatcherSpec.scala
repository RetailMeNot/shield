package shield.actors.config.domain

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, MustMatchers, WordSpecLike}
import shield.actors.ShieldActorMsgs
import shield.actors.config.ChangedContents
import shield.akka.helpers.VirtualScheduler

class S3DomainWatcherSpec extends TestKit(ActorSystem("testSystem",ConfigFactory.parseString(
  """
    |akka.scheduler.implementation = shield.akka.helpers.VirtualScheduler
  """.stripMargin).withFallback(ConfigFactory.load())))
  with WordSpecLike
  with MustMatchers
  with BeforeAndAfterEach
  with BeforeAndAfterAll {

  val scheduler = system.scheduler.asInstanceOf[VirtualScheduler]
  def virtualTime = scheduler.virtualTime

  override def beforeEach: Unit = {
    scheduler.reset()
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val validConfig =
    """
      |domains = [
      |    {
      |      domain-name: "domain1"
      |
      |      middleware-chain = [
      |        {id: localratelimit, sla: 20 ms, builder: BucketRateLimitBuilder},
      |        {id: localhttpcache, sla: 20 ms, builder: ResponseCacheBuilder}
      |      ]
      |
      |      listeners = [
      |        {id: consoleLogger, builder: ConsoleLogBuilder}
      |      ]
      |
      |      listener-config {
      |        consoleLogger {
      |        }
      |      }
      |
      |      upstream-watcher: StaticUpstreamWatcher
      |      upstream-weighting: {
      |        step-count: 100
      |        step-duration: 6 seconds
      |      }
      |
      |      upstreams = [
      |        {serviceType: "swagger2", serviceLocation: "https://example.com"}
      |      ]
      |
      |      middleware {
      |        localratelimit {
      |          bypass-header: bypassratelimit
      |          calls-per: 10000
      |          per-seconds: 1
      |          kvstore: memory
      |        }
      |        localhttpcache {
      |          kvstore: memory
      |        }
      |      }
      |
      |      log {
      |        request-headers:  [ "User-Agent", "Remote-Address" ]
      |        response-headers: [ ]
      |      }
      |
      |      kvstores {
      |        memory {
      |          type: memory
      |          maxHashCapacity: 10000
      |          maxKeyCapacity: 10000
      |          maxLimitCapacity: 10000
      |        }
      |      }
      |
      |      ignoreExtensions: [ ]
      |    },
      |    {
      |      domain-name: "domain2"
      |
      |      middleware-chain = [
      |        {id: localhttpcache, sla: 20 ms, builder: ResponseCacheBuilder}
      |      ]
      |
      |      listeners = []
      |
      |      upstream-watcher: StaticUpstreamWatcher
      |      upstream-weighting: {
      |        step-count: 100
      |        step-duration: 6 seconds
      |      }
      |
      |      upstreams = [
      |        {serviceType: "swagger2", serviceLocation: "https://example.com"}
      |      ]
      |
      |      middleware {
      |        localhttpcache {
      |          kvstore: memory
      |        }
      |      }
      |
      |      log {
      |        request-headers:  [ "User-Agent", "Remote-Address" ]
      |        response-headers: [ ]
      |      }
      |
      |      kvstores {
      |        memory {
      |          type: memory
      |          maxHashCapacity: 10000
      |          maxKeyCapacity: 10000
      |          maxLimitCapacity: 10000
      |        }
      |      }
      |
      |      ignoreExtensions: [ ]
      |    }
      |  ]
    """.stripMargin

  val invalidConfig =
    """
      |{"domain-name": "domain1"}
    """.stripMargin

  "S3DomainWatcher" must {

    "update shield with new domains when parsing valid config" in {
      val parent = TestProbe()
      val domainWatcher = TestActorRef(Props(new S3DomainWatcher()), parent.ref, "UnderTestActor")

      domainWatcher ! ChangedContents(validConfig)

      val msg: ShieldActorMsgs.DomainsUpdated = parent.expectMsgClass(classOf[ShieldActorMsgs.DomainsUpdated])
      msg.domains.size mustEqual 2
      msg.domains.contains("domain1") mustBe true
      msg.domains.contains("domain2") mustBe true
    }

    "not update shield when parsing invalid domains config" in {
      val parent = TestProbe()
      val domainWatcher = TestActorRef(Props(new S3DomainWatcher()), parent.ref, "UnderTestActor")

      domainWatcher ! ChangedContents(invalidConfig)

      parent.msgAvailable mustBe false
    }
  }

}
