package shield.proxying

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.{MustMatchers, WordSpecLike}
import shield.actors.config.{ProxyState, WeightedProxyState}
import shield.config.{ServiceLocation, Swagger2ServiceType}
import shield.routing.{EndpointDetails, EndpointTemplate, Path}
import spray.http.{Uri, HttpMethods}

class RoundRobinBalancerBuilderSpec extends TestKit(ActorSystem("testSystem", ConfigFactory.parseString(
  """
    |akka.scheduler.implementation = shield.akka.helpers.VirtualScheduler
  """.stripMargin).withFallback(ConfigFactory.load())))
  with WordSpecLike
  with MustMatchers {
  import system.dispatcher

  def defaultProxyState(actor: ActorRef) = ProxyState("service", "version", Swagger2ServiceType, actor, Map(EndpointTemplate(HttpMethods.GET, Path("/healthcheck")) -> EndpointDetails.empty), healthy=true, "serving", Set.empty)

  "RoundRobinBalancerBuilder" should {
    "pretend all upstreams have weight of 1 when they all have weight 0" in {
      val actor1 = TestProbe()
      val actor2 = TestProbe()
      val actor3 = TestProbe()

      val builder = new RoundRobinBalancerBuilder(Nil, system, Map(
        actor1.ref -> WeightedProxyState(0, defaultProxyState(actor1.ref)),
        actor2.ref -> WeightedProxyState(0, defaultProxyState(actor2.ref)),
        actor3.ref -> WeightedProxyState(1, defaultProxyState(actor3.ref))
      ))

      val balancer = builder.build(Set(actor1.ref, actor2.ref)).balancer

      for (i <- 1 to 6) {
        balancer ! "foo"
      }
      for (i <- 1 to 3) {
        actor1.expectMsg("foo")
      }
      actor1.msgAvailable mustBe false

      for (i <- 1 to 3) {
        actor2.expectMsg("foo")
      }
      actor2.msgAvailable mustBe false

      actor3.msgAvailable mustBe false
    }

    "reduce the number of repeated upstreams as much as possible" in {
      val actor1 = TestProbe()
      val actor2 = TestProbe()
      val actor3 = TestProbe()
      val actor4 = TestProbe()

      val builder = new RoundRobinBalancerBuilder(Nil, system, Map(
        actor1.ref -> WeightedProxyState(150, defaultProxyState(actor1.ref)),
        actor2.ref -> WeightedProxyState(100, defaultProxyState(actor2.ref)),
        actor3.ref -> WeightedProxyState(50, defaultProxyState(actor3.ref)),
        actor4.ref -> WeightedProxyState(1, defaultProxyState(actor4.ref))
      ))

      val balancer = builder.build(Set(actor1.ref, actor2.ref, actor3.ref)).balancer

      for (i <- 1 to 12) {
        balancer ! "foo"
      }
      for (i <- 1 to 6) {
        actor1.expectMsg("foo")
      }
      actor1.msgAvailable mustBe false

      for (i <- 1 to 4) {
        actor2.expectMsg("foo")
      }
      actor2.msgAvailable mustBe false

      for (i <- 1 to 2) {
        actor3.expectMsg("foo")
      }
      actor3.msgAvailable mustBe false

      actor4.msgAvailable mustBe false
    }

    "handle 0 weight active proxies" in {
      val actor1 = TestProbe()
      val actor2 = TestProbe()
      val actor3 = TestProbe()
      val actor4 = TestProbe()

      val builder = new RoundRobinBalancerBuilder(Nil, system, Map(
        actor1.ref -> WeightedProxyState(0, defaultProxyState(actor1.ref)),
        actor2.ref -> WeightedProxyState(100, defaultProxyState(actor2.ref)),
        actor3.ref -> WeightedProxyState(50, defaultProxyState(actor3.ref)),
        actor4.ref -> WeightedProxyState(1, defaultProxyState(actor4.ref))
      ))

      val balancer = builder.build(Set(actor1.ref, actor2.ref, actor3.ref)).balancer

      for (i <- 1 to 6) {
        balancer ! "foo"
      }
      actor1.msgAvailable mustBe false

      for (i <- 1 to 4) {
        actor2.expectMsg("foo")
      }
      actor2.msgAvailable mustBe false

      for (i <- 1 to 2) {
        actor3.expectMsg("foo")
      }
      actor3.msgAvailable mustBe false

      actor4.msgAvailable mustBe false
    }
  }
}
