package shield.actors.config

import akka.actor._
import shield.actors.config.WeightWatcherMsgs.{SetTargetWeights, SetWeights}
import shield.actors.{HostProxy, HostProxyMsgs, RestartLogging}
import shield.config._
import shield.routing.{EndpointDetails, EndpointTemplate}

import scala.concurrent.duration._

case class ProxyState(service: String, version: String, proxyType: ServiceType, proxy: ActorRef, allEndpoints: Map[EndpointTemplate, EndpointDetails], healthy: Boolean, status: String, unhealthyEndpoints: Set[EndpointTemplate])
case class WeightedProxyState(weight: Int, state: ProxyState) {
  require(weight >= 0, "negative weight not allowed")

  def setWeight(newWeight: Option[Int]) : WeightedProxyState =
    newWeight.map(w => copy(weight=w)).getOrElse(this)
}

case class ServiceDetails(serviceType: ServiceType, weight: Int) {
  require(weight >= 0, "negative weight not allowed")
}
object UpstreamAggregatorMsgs {

  case class DiscoveredUpstreams(services: Map[ServiceLocation, ServiceDetails])
  case class StateUpdated(serviceInstance: ServiceLocation, state: ProxyState)

}

object UpstreamAggregator {

  def props(domain: DomainSettings): Props = Props(new UpstreamAggregator(domain))

  def defaultState(serviceDetails: ServiceDetails, proxy: ActorRef) = WeightedProxyState(serviceDetails.weight, ProxyState("(unknown)", "(unknown)", serviceDetails.serviceType, proxy, Map.empty, healthy=false, "initializing", Set.empty))

  def spawnAndWatchProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext): Map[ServiceLocation, WeightedProxyState] = {
    var map = Map[ServiceLocation, WeightedProxyState]()

    for ((location, details) <- services) {
      val pipeline = TransportFactory(location)(context.system, context.dispatcher)
      val proxy = context.actorOf(HostProxy.props(location, details.serviceType, localServiceLocation, pipeline))
      context.watch(proxy)
      map += location -> defaultState(details, proxy)
    }
    map
  }

}

class UpstreamAggregator(domain: DomainSettings) extends Actor with ActorLogging with RestartLogging {
  import UpstreamAggregatorMsgs._
  import context.dispatcher
  val settings = Settings(context.system)

  // todo: simplify
  def spawnProxies(services: Map[ServiceLocation, ServiceDetails], localServiceLocation: ServiceLocation, context: ActorContext): Map[ServiceLocation, WeightedProxyState]  = {
    UpstreamAggregator.spawnAndWatchProxies(services, localServiceLocation, context)
  }

  def spawnUpstreamWatcher() : ActorRef = {
    context.actorOf(Props(domain.UpstreamWatcher, domain.domainConfig), "upstream-watcher")
  }
  val upstreamWatcher = spawnUpstreamWatcher()

  def spawnWeightWatcher() : ActorRef = {
    context.actorOf(WeightWatcher.props(domain.WeightWatcherStepDuration, domain.WeightWatcherStepCount))
  }
  val weightWatcher = spawnWeightWatcher()

  var firstReport = true
  var upstreamState = Map[ServiceLocation, WeightedProxyState]()

  // todo: if we get a dns name, spin up an actor to watch the dns entry and list all the relevant ip addresses
  def receive = {
    case DiscoveredUpstreams(latestUpstreams) =>
      weightWatcher ! SetTargetWeights(latestUpstreams)

      val newLocations = latestUpstreams.keySet.diff(upstreamState.keySet)
      upstreamState ++= spawnProxies(newLocations.map(l => l -> latestUpstreams(l)).toMap, settings.DefaultServiceLocation, context)

      // BUG: if we get another DiscoveredUpstream msg while the host is still unregistering/draining, the HostProxy
      // will ignore the 2nd ShutdownProxy msg, and the only effect should be the status msg goes from 'draining' back
      // to 'unregistering' incorrectly
      val removedLocations = upstreamState.keySet.diff(latestUpstreams.keySet)
      for (host <- removedLocations) {
        val status = upstreamState(host)
        upstreamState += host -> status.copy(state=status.state.copy(status="unregistering"))
        status.state.proxy ! HostProxyMsgs.ShutdownProxy
        // todo: use global request timeout
        // redundant, but better safe than sorry
        context.system.scheduler.scheduleOnce(60.seconds, status.state.proxy, PoisonPill)
      }

      if (newLocations.nonEmpty || removedLocations.nonEmpty || firstReport) {
        firstReport = false
        val msg = ConfigWatcherMsgs.UpstreamsUpdated(upstreamState)
        context.parent ! msg
      }

    case SetWeights(weights) =>
      val newState = upstreamState.map { case (location, status) =>
        location -> status.setWeight(weights.get(location))
      }
      if (newState != upstreamState) {
        upstreamState = newState
        val msg = ConfigWatcherMsgs.UpstreamsUpdated(upstreamState)
        context.parent ! msg
      }

    case t: Terminated =>
      val toRemove = upstreamState.filter(kvp => kvp._2.state.proxy == t.actor).keySet
      assert(toRemove.size == 1, s"Terminated HostProxy actor was used by ${toRemove.size} upstream hosts, shouldn't be possible")
      upstreamState = upstreamState - toRemove.head
      context.parent ! ConfigWatcherMsgs.UpstreamsUpdated(upstreamState)
      // unwatch to remove and ignore future terminated messages for that actor that may be in the mailbox right now
      context.unwatch(t.actor)

    case StateUpdated(serviceInstance, status) if upstreamState.contains(serviceInstance) =>
      upstreamState += serviceInstance -> upstreamState(serviceInstance).copy(state=status)
      val msg = ConfigWatcherMsgs.UpstreamsUpdated(upstreamState)
      context.parent ! msg
  }
}


