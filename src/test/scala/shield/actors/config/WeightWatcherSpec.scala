package shield.actors.config

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest._
import shield.actors.config.WeightWatcherMsgs.SetWeights
import shield.akka.helpers.VirtualScheduler
import shield.config.{HttpServiceLocation, ServiceLocation, Swagger1ServiceType, Swagger2ServiceType}

import scala.concurrent.duration._

class WeightWatcherSpec extends TestKit(ActorSystem("testSystem", ConfigFactory.parseString(
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

  "SetWeights" must {
    "not allow negative weights" in {
      SetWeights(Map(
        HttpServiceLocation("http://example.com") -> 1,
        HttpServiceLocation("http://example.org") -> 1
      ))
      intercept[IllegalArgumentException] {
        SetWeights(Map(
          HttpServiceLocation("http://example.com") -> -1,
          HttpServiceLocation("http://example.org") -> 1
        ))
      }
    }
  }

  "TransitionDetails" must {
    "not allow negative target weight" in {
      TransitionDetails(1, 0, 1)
      intercept[IllegalArgumentException] {
        TransitionDetails(-1, 0, 1)
      }
    }

    "not allow negative current weight" in {
      TransitionDetails(1, 0, 1)
      intercept[IllegalArgumentException] {
        TransitionDetails(1, 0, -1)
      }
    }

    "not create a new object when setTarget receives the current target" in {
      val stoppedTd = TransitionDetails(10, 0, 10)
      val newStoppedTd = stoppedTd.setTarget(10, 5)

      newStoppedTd must be theSameInstanceAs stoppedTd

      val movingTd = TransitionDetails(20, 2, 16)
      val newMovingTd = movingTd.setTarget(20, 4)
      newMovingTd must be theSameInstanceAs movingTd
    }

    "calculate delta correctly for setTarget" in {
      val td = TransitionDetails(10, 0, 10)
      val positiveTd = td.setTarget(20, 5)

      positiveTd.delta mustEqual 2.0

      val negativeTd = td.setTarget(0, 5)

      negativeTd.delta mustEqual -2.0
    }

    "not create a new target when advanceStep has a delta of 0" in {
      val td = TransitionDetails(10, 0, 10)
      val newTd = td.advanceStep()

      newTd must be theSameInstanceAs td
    }

    "cap the delta applied by advanceStep to targetWeight" in {
      val positiveTd = TransitionDetails(10, 2, 9)
      positiveTd.currentWeight must not equal 10

      val nextPositiveTd = positiveTd.advanceStep()
      nextPositiveTd.currentWeight mustEqual 10

      val negativeTd = TransitionDetails(10, -2, 11)
      negativeTd.currentWeight must not equal 10

      val nextNegativeTd = negativeTd.advanceStep()
      nextNegativeTd.currentWeight mustEqual 10
    }

    "calculate currentWeight correctly for advanceStep" in {
      TransitionDetails(20, 2, 9).advanceStep().currentWeight mustEqual 11

      TransitionDetails(0, -2, 11).advanceStep().currentWeight mustEqual 9
    }
  }

  "WeightWatcher" must {
    "not send a SetWeights message the first time a host is seen" in {
      // a new host will start at it's declared weight, so there's no need to redeclare it (send a SetWeights msg) as the same value
      val parent = TestProbe()
      val watcher = TestActorRef(Props(new WeightWatcher(30.seconds, 10)), parent.ref, "weight-watcher")
      val service1Location = HttpServiceLocation("http://example.com")
      val service1Details = ServiceDetails(Swagger1ServiceType, 0)

      parent.msgAvailable mustBe false

      watcher ! WeightWatcherMsgs.SetTargetWeights(Map(service1Location -> service1Details))

      parent.msgAvailable mustBe false
    }

    "start a new host without any transition" in {
      val parent = TestProbe()
      val watcher = TestActorRef(Props(new WeightWatcher(30.seconds, 10)), parent.ref, "weight-watcher")
      val service1Location = HttpServiceLocation("http://example.com")
      val service1Details = ServiceDetails(Swagger1ServiceType, 100)

      parent.msgAvailable mustBe false

      watcher ! WeightWatcherMsgs.SetTargetWeights(Map(service1Location -> service1Details))

      parent.msgAvailable mustBe false

      virtualTime.advance(30.seconds)

      parent.msgAvailable mustBe false
    }

    "send a SetWeights message when an upstream advances to/beyond the next integer" in {
      val parent = TestProbe()
      val watcher = TestActorRef(Props(new WeightWatcher(30.seconds, 10)), parent.ref, "weight-watcher")
      val service1Location = HttpServiceLocation("http://example.com")
      def service1Details(weight: Int) = ServiceDetails(Swagger1ServiceType, weight)

      parent.msgAvailable mustBe false

      watcher ! WeightWatcherMsgs.SetTargetWeights(Map(service1Location -> service1Details(0)))

      parent.msgAvailable mustBe false

      watcher ! WeightWatcherMsgs.SetTargetWeights(Map(service1Location -> service1Details(10)))

      for (i <- 1 to 10) {
        virtualTime.advance(30.seconds)
        parent.expectMsg(SetWeights(Map(service1Location -> i)))
      }

      virtualTime.advance(30.seconds)
      parent.msgAvailable mustBe false
    }

    "not send a SetWeights message when an upstream does not advance to the next integer" in {
      val parent = TestProbe()
      val watcher = TestActorRef(Props(new WeightWatcher(30.seconds, 10)), parent.ref, "weight-watcher")
      val service1Location = HttpServiceLocation("http://example.com")
      def service1Details(weight: Int) = ServiceDetails(Swagger1ServiceType, weight)

      parent.msgAvailable mustBe false

      watcher ! WeightWatcherMsgs.SetTargetWeights(Map(service1Location -> service1Details(0)))

      parent.msgAvailable mustBe false

      watcher ! WeightWatcherMsgs.SetTargetWeights(Map(service1Location -> service1Details(5)))

      for (i <- 1 to 10) {
        virtualTime.advance(30.seconds)
        if (i % 2 == 0) {
          parent.expectMsg(SetWeights(Map(service1Location -> i/2)))
        } else {
          parent.msgAvailable mustBe false
        }
      }

      virtualTime.advance(30.seconds)
      parent.msgAvailable mustBe false
    }

    "handle multiple parallel transitions" in {
      val parent = TestProbe()
      val watcher = TestActorRef(Props(new WeightWatcher(30.seconds, 10)), parent.ref, "weight-watcher")
      val service1Location = HttpServiceLocation("http://example.com")
      val service2Location = HttpServiceLocation("http://example.org")
      def service1Details(weight: Int) = ServiceDetails(Swagger1ServiceType, weight)
      def service2Details(weight: Int) = ServiceDetails(Swagger2ServiceType, weight)

      parent.msgAvailable mustBe false

      watcher ! WeightWatcherMsgs.SetTargetWeights(Map(
        service1Location -> service1Details(0),
        service2Location -> service2Details(100)
      ))

      parent.msgAvailable mustBe false

      watcher ! WeightWatcherMsgs.SetTargetWeights(Map(
        service1Location -> service1Details(20),
        service2Location -> service2Details(100)
      ))

      for (i <- 1 to 3) {
        virtualTime.advance(30.seconds)
        parent.expectMsg(SetWeights(Map(
          service1Location -> 2 * i,
          service2Location -> 100
        )))
      }

      watcher ! WeightWatcherMsgs.SetTargetWeights(Map(
        service1Location -> service1Details(20),
        service2Location -> service2Details(20)
      ))

      for (i <- 1 to 7) {
        virtualTime.advance(30.seconds)
        parent.expectMsg(SetWeights(Map(
          service1Location -> 2 * (i + 3),
          service2Location -> (100 - (8 * i))
        )))
      }

      for (i <- 1 to 3) {
        virtualTime.advance(30.seconds)
        parent.expectMsg(SetWeights(Map(
          service1Location -> 20,
          service2Location -> (100 - (8 * (i + 7)))
        )))
      }

      virtualTime.advance(30.seconds)
      parent.msgAvailable mustBe false

      watcher ! WeightWatcherMsgs.SetTargetWeights(Map(
        service1Location -> service1Details(100),
        service2Location -> service2Details(0)
      ))

      for (i <- 1 to 10) {
        virtualTime.advance(30.seconds)
        parent.expectMsg(SetWeights(Map(
          service1Location -> (20 + (8 * i)),
          service2Location -> (20 - (2 * i))
        )))
      }

      virtualTime.advance(30.seconds)
      parent.msgAvailable mustBe false
    }

    "gracefully handle restarting" in {
      case object TickTock
      val parent = TestProbe()
      val watcher = TestActorRef(Props(new WeightWatcher(30.seconds, 10) {
        override def receive = super.receive orElse {
          case TickTock => throw new IllegalArgumentException("Tick Tock Crock")
        }
      }), parent.ref, "weight-watcher")
      val service1Location = HttpServiceLocation("http://example.com")
      val service2Location = HttpServiceLocation("http://example.org")
      def service1Details(weight: Int) = ServiceDetails(Swagger1ServiceType, weight)
      def service2Details(weight: Int) = ServiceDetails(Swagger2ServiceType, weight)

      parent.msgAvailable mustBe false

      watcher ! WeightWatcherMsgs.SetTargetWeights(Map(
        service1Location -> service1Details(0),
        service2Location -> service2Details(100)
      ))

      parent.msgAvailable mustBe false

      watcher ! WeightWatcherMsgs.SetTargetWeights(Map(
        service1Location -> service1Details(20),
        service2Location -> service2Details(100)
      ))

      for (i <- 1 to 3) {
        virtualTime.advance(30.seconds)
        parent.expectMsg(SetWeights(Map(
          service1Location -> 2 * i,
          service2Location -> 100
        )))
      }

      watcher ! TickTock

      // restart with the target weights
      parent.expectMsg(SetWeights(Map(
        service1Location -> 20,
        service2Location -> 100
      )))

      watcher.underlyingActor.asInstanceOf[WeightWatcher].state mustEqual Map(
        service1Location -> TransitionDetails(20, 0, 20),
        service2Location -> TransitionDetails(100, 0, 100)
      )
    }

    "not cause a system crash once enough time has passed" in {
      val parent = TestProbe()
      val watcher = TestActorRef(Props(new WeightWatcher(10.seconds, 10)), parent.ref, "weight-watcher")
      val service1Location = HttpServiceLocation("http://example.com")
      val service2Location = HttpServiceLocation("http://example.org")
      def service1Details(weight: Int) = ServiceDetails(Swagger1ServiceType, weight)
      def service2Details(weight: Int) = ServiceDetails(Swagger2ServiceType, weight)

      parent.msgAvailable mustBe false

      watcher ! WeightWatcherMsgs.SetTargetWeights(Map(
        service1Location -> service1Details(100),
        service2Location -> service2Details(100)
      ))

      parent.msgAvailable mustBe false

      var i = 0
      while (i < 1000000) {
        i += 1
        virtualTime.advance(10.seconds)
        parent.msgAvailable mustBe false
      }

      true mustBe true
    }
  }
}
