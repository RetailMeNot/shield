package shield.actors.config.upstream

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import org.scalatest.{MustMatchers, WordSpecLike}
import shield.actors.config.{ServiceDetails, UpstreamAggregatorMsgs}
import shield.config.{HttpServiceLocation, ServiceLocation, Swagger1ServiceType, Swagger2ServiceType}
import spray.http.Uri
import shield.actors.config.{ChangedContents, ServiceDetails, UpstreamAggregatorMsgs}
import shield.config.{ServiceLocation, Swagger1ServiceType, Swagger2ServiceType}

class S3UpstreamUpdaterSpec extends TestKit(ActorSystem("testSystem"))
  with WordSpecLike
  with MustMatchers {

  "S3UpstreamUpdater" must {


    "update parent when parsing expected json" in {
      val parent = TestProbe()
      val actorRef = TestActorRef(Props(new Actor with JsonUpstreamUpdater), parent.ref, "UnderTestActor")
      actorRef ! ChangedContents(
        """[
        |  {"serviceType": "swagger1", "serviceLocation": "http://localhost:5000"},
        |  {"serviceType": "swagger1", "serviceLocation": "http://localhost:5001"},
        |  {"serviceType": "swagger2", "serviceLocation": "http://localhost:5002"},
        |  {"serviceType": "swagger2", "serviceLocation": "https://test.org"}
        |]
        |""".stripMargin)

      parent.expectMsg(UpstreamAggregatorMsgs.DiscoveredUpstreams(
        Map(
          HttpServiceLocation("http://localhost:5000") -> ServiceDetails(Swagger1ServiceType, 1),
          HttpServiceLocation("http://localhost:5001") -> ServiceDetails(Swagger1ServiceType, 1),
          HttpServiceLocation("http://localhost:5002") -> ServiceDetails(Swagger2ServiceType, 1),
          HttpServiceLocation("https://test.org") -> ServiceDetails(Swagger2ServiceType, 1)
        )
      ))
    }

    "parse weights from json" in {
      val parent = TestProbe()
      val actorRef = TestActorRef(Props(new Actor with JsonUpstreamUpdater), parent.ref, "UnderTestActor")
      actorRef ! ChangedContents(
        """[
          |  {"serviceType": "swagger1", "serviceLocation": "http://localhost:5000", "weight": 0},
          |  {"serviceType": "swagger1", "serviceLocation": "http://localhost:5001", "weight": 1},
          |  {"serviceType": "swagger2", "serviceLocation": "http://localhost:5002", "weight": 2},
          |  {"serviceType": "swagger2", "serviceLocation": "https://test.org"}
          |]
          |""".stripMargin)

      parent.expectMsg(UpstreamAggregatorMsgs.DiscoveredUpstreams(
        Map(
          HttpServiceLocation("http://localhost:5000") -> ServiceDetails(Swagger1ServiceType, 0),
          HttpServiceLocation("http://localhost:5001") -> ServiceDetails(Swagger1ServiceType, 1),
          HttpServiceLocation("http://localhost:5002") -> ServiceDetails(Swagger2ServiceType, 2),
          HttpServiceLocation("https://test.org") -> ServiceDetails(Swagger2ServiceType, 1)
        )
      ))
    }

    "not update parent when parsing unexpected json" in {
      val parent = TestProbe()
      val actorRef = TestActorRef(Props(new Actor with JsonUpstreamUpdater), parent.ref, "UnderTestActor")
      actorRef ! ChangedContents(
        """{
        | "swagger1": {"service": "pyapi", "baseUrl": "http://localhost:5001"},
        | "swagger2": [
        |  {"service": "javaapi", "baseUrl": "http://localhost:5000"},
        |  {"service": "javaapi", "baseUrl": "http://test.org"}
        | ]
        |}""".stripMargin)

      parent.msgAvailable mustBe false
    }

    "not update parent when parsing malformed weights" in {
      val parent = TestProbe()
      val actorRef = TestActorRef(Props(new Actor with JsonUpstreamUpdater), parent.ref, "UnderTestActor")
      actorRef ! ChangedContents(
        """[
          |  {"serviceType": "swagger1", "serviceLocation": "http://localhost:5000"},
          |  {"serviceType": "swagger1", "serviceLocation": "http://localhost:5001"},
          |  {"serviceType": "swagger2", "serviceLocation": "http://localhost:5002", "weight": "0.1"},
          |  {"serviceType": "swagger2", "serviceLocation": "https://test.org"}
          |]
          |""".stripMargin)

      parent.msgAvailable mustBe false
    }

    "not update parent when parsing negative weights" in {
      val parent = TestProbe()
      val actorRef = TestActorRef(Props(new Actor with JsonUpstreamUpdater), parent.ref, "UnderTestActor")
      actorRef ! ChangedContents(
        """[
          |  {"serviceType": "swagger1", "serviceLocation": "http://localhost:5000"},
          |  {"serviceType": "swagger1", "serviceLocation": "http://localhost:5001"},
          |  {"serviceType": "swagger2", "serviceLocation": "http://localhost:5002", "weight": -10},
          |  {"serviceType": "swagger2", "serviceLocation": "https://test.org"}
          |]
          |""".stripMargin)

      parent.msgAvailable mustBe false
    }

    "not update parent when there's an unrecognized serviceType" in {
      val parent = TestProbe()
      val actorRef = TestActorRef(Props(new Actor with JsonUpstreamUpdater), parent.ref, "UnderTestActor")
      actorRef ! ChangedContents(
        """[
          |  {"serviceType": "swagger1", "serviceLocation": "http://localhost:5000"},
          |  {"serviceType": "swagger2", "serviceLocation": "http://localhost:5002"},
          |  {"serviceType": "foobar", "serviceLocation": "http://localhost:5002"}
          |]
          |""".stripMargin)

      parent.msgAvailable mustBe false
    }
  }
}
