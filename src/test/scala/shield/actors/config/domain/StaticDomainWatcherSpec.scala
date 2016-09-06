package shield.actors.config.domain

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpecLike}
import shield.actors.ShieldActorMsgs
import shield.config.Settings

class StaticDomainWatcherSpec extends TestKit(ActorSystem("testSystem"))
  with WordSpecLike
  with MustMatchers
  with BeforeAndAfterAll {

  val settings = Settings(system)

  "StaticDomainWatcher" should {

    "notify shield about domains found" in {
      val parent = TestProbe()
      TestActorRef(Props(new StaticDomainWatcher()), parent.ref, "static-domain-watcher")

      val msg: ShieldActorMsgs.DomainsUpdated = parent.expectMsgClass(classOf[ShieldActorMsgs.DomainsUpdated])
      msg.domains.size must equal (settings.config.getConfigList("shield.domains").size)
    }
  }

}
