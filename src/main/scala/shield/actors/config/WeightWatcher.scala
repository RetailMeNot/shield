package shield.actors.config

import akka.actor.{Actor, ActorLogging, Cancellable, Props}
import shield.config.ServiceLocation

import scala.concurrent.duration._

object WeightWatcherMsgs {
  case class SetTargetWeights(services: Map[ServiceLocation, ServiceDetails])
  case object Tick
  case class SetWeights(weights: Map[ServiceLocation, Int]) {
    require(weights.values.forall(_ >= 0), "Negative weight not allowed")
  }
}

object TransitionDetails {
  def default(details: ServiceDetails): TransitionDetails = {
    TransitionDetails(details.weight, 0, details.weight)
  }
}
case class TransitionDetails(targetWeight: Int, delta: Double, currentWeight: Double) {
  require(targetWeight >= 0, "target weight can't be negative")
  require(currentWeight >= 0, "current weight can't be negative")

  def setTarget(newTarget: Int, stepCount: Int) : TransitionDetails =
    if (newTarget != targetWeight) {
      copy(
        targetWeight = newTarget,
        delta = (newTarget - currentWeight) / stepCount
      )
    } else {
      this
    }

  def advanceStep() : TransitionDetails = {
    val next = currentWeight + delta
    if (delta == 0) {
      this
    } else if ((delta < 0 && next <= targetWeight) || (delta > 0 && next >= targetWeight)) {
      copy(delta=0, currentWeight=targetWeight)
    } else {
      copy(currentWeight=next)
    }
  }
}

// Why do we have one weight watcher for all hosts instead of one weight watcher for each host?
// Having a watcher for each host would cause multiple hosts to update their state per step.
// Having one watcher for all hosts will cause one state update per step.
// The one-for-all approach significantly lowers the number of times that ConfigWatcher will have to rebuild the router
object WeightWatcher {
  def props(stepTime: FiniteDuration, stepCount: Int) : Props = Props(new WeightWatcher(stepTime, stepCount))
}
class WeightWatcher(stepTime: FiniteDuration, stepCount: Int) extends Actor with ActorLogging {
  require(stepCount > 0, "Must have at least one step")
  import WeightWatcherMsgs._
  import context.dispatcher

  var state : Map[ServiceLocation, TransitionDetails] = Map.empty

  var ticker : Cancellable = context.system.scheduler.schedule(stepTime, stepTime, self, Tick)

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    self ! SetWeights(state.map { case (loc, transition) => loc -> transition.targetWeight })
    super.preRestart(reason, message)
  }

  override def postStop() = {
    ticker.cancel()
  }

  def receive = {
    case SetTargetWeights(proxyDetails) =>
      state = proxyDetails.map { case (location, proxyDetail) =>
          location -> state.get(location).map(_.setTarget(proxyDetail.weight, stepCount)).getOrElse(TransitionDetails.default(proxyDetail))
      }

    case Tick =>
      val oldWeights = state.map { case (loc, transition) => loc -> transition.currentWeight.toInt }
      state = state.map { case (loc, transition) => loc -> transition.advanceStep() }
      val newWeights = state.map { case (loc, transition) => loc -> transition.currentWeight.toInt }
      if (oldWeights != newWeights) {
        context.parent ! SetWeights(newWeights)
      }

    case SetWeights(weights) =>
      state = weights.map { case (loc, weight) => loc -> TransitionDetails(weight, 0 , weight) }
      context.parent ! SetWeights(weights)
  }
}
