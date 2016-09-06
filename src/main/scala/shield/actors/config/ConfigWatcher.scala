package shield.actors.config

import akka.actor._
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.ObjectMapper
import shield.actors.{Middleware, RestartLogging, ShieldActorMsgs}
import shield.build.BuildInfo
import shield.config.{DomainSettings, ServiceLocation, Settings}
import shield.metrics.{Instrumented, Registry}
import shield.proxying.{EmptyBalancerBuilder, ProxyBalancerBuilder, RoundRobinBalancerBuilder}
import shield.routing._
import shield.swagger.SwaggerFetcher
import spray.http._
import spray.json._

import scala.collection.mutable
import scala.concurrent.duration._


object EndpointTemplateInfo {
  def fromTemplate(template: EndpointTemplate) : EndpointTemplateInfo = {
    EndpointTemplateInfo(template.method.name, template.path.toString)
  }
}
case class EndpointTemplateInfo(method: String, path: String)
object UpstreamHealthInfo {
  def fromStatus(status: (ServiceLocation, WeightedProxyState)) : UpstreamHealthInfo = {
    val (service, ws) = status
    UpstreamHealthInfo(
      ws.state.service,
      ws.state.version,
      ws.state.proxyType.typeName,
      service.locationName,
      ws.state.status,
      ws.weight,
      ws.state.healthy,
      ws.state.allEndpoints.size,
      ws.state.unhealthyEndpoints.map(EndpointTemplateInfo.fromTemplate).toList
    )
  }
}
case class UpstreamHealthInfo(service: String, version: String, proxyType: String, baseUrl: String, status: String, weight: Int, healthy: Boolean, endpoints: Int, unhealthyEndpoints: List[EndpointTemplateInfo])

object ConfigHealthInfo {
  def fromStatus(all: Iterable[String], ready: collection.Set[String]) : ConfigHealthInfo = {
    ConfigHealthInfo(
      all.filterNot(ready).toList,
      ready.toList
    )
  }
}
case class ConfigHealthInfo(pending: List[String], ready: List[String])

object HealthcheckInfo {
  def fromStatus(host: String, status: Map[ServiceLocation, WeightedProxyState], allMiddleware: List[String], readyMiddleware: collection.Set[String], allListeners: Set[String], readyListeners: collection.Set[String]) : HealthcheckInfo = {
    HealthcheckInfo(
      host,
      status.toList.map(UpstreamHealthInfo.fromStatus),
      ConfigHealthInfo.fromStatus(allMiddleware, readyMiddleware),
      ConfigHealthInfo.fromStatus(allListeners, readyListeners),
      JsonParser(BuildInfo.toJson).asJsObject
    )
  }
}
case class HealthcheckInfo(host: String, upstreams: List[UpstreamHealthInfo], middleware: ConfigHealthInfo, listeners: ConfigHealthInfo, buildInfo: JsObject)

object HealthcheckProtocol extends DefaultJsonProtocol {
  implicit val endpointTemplateFormat = jsonFormat2(EndpointTemplateInfo.apply)
  implicit val upstreamHealthInfoFormat = jsonFormat9(UpstreamHealthInfo.apply)
  implicit val configHealthInfoFormat = jsonFormat2(ConfigHealthInfo.apply)
  implicit val healthcheckInfoFormat = jsonFormat5(HealthcheckInfo.apply)
}


object ConfigWatcherMsgs {
  case class MiddlewareUpdated(middleware: Middleware)
  case class ListenerUpdated(id: String, listener: ActorRef)
  case class UpstreamsUpdated(upstreams: Map[ServiceLocation, WeightedProxyState])
}

object ConfigWatcher {
  def props(domain: DomainSettings, shield: ActorRef): Props = Props(new ConfigWatcher(domain, shield))
}

class ConfigWatcher(domain: DomainSettings, shield: ActorRef) extends Actor with ActorLogging with RestartLogging with Instrumented {
  // todo: listen for sigusr1 interrupt and reload/parse the config file for new hosts
  import ConfigWatcherMsgs._
  import HealthcheckProtocol._
  import context._

  val settings = Settings(context.system)
  val mapper = new ObjectMapper().setSerializationInclusion(Include.NON_NULL)

  def spawnUpstreamWatcher() : ActorRef = {
    context.actorOf(UpstreamAggregator.props(domain), "upstream-aggregator-" + domain.DomainName)
  }
  val upstreamWatcher = spawnUpstreamWatcher()
  var upstreamStatus : Option[Map[ServiceLocation, WeightedProxyState]] = None
  var allUpstreamsHealthy = false

  def spawnMiddlewareBuilders() : List[String] = {
    domain.MiddlewareChain.foreach(m => (m.id, context.actorOf(Props(m.builder, m.id, domain), s"${m.id}-mwbuilder")))
    domain.MiddlewareChain.map(_.id)
  }
  val allMiddleware = spawnMiddlewareBuilders().distinct
  val middleware = mutable.Map[String, Middleware]()

  def spawnListenerBuilders() : Set[String] = {
    domain.Listeners.foreach(l => (l.id, context.actorOf(Props(l.builder, l.id, domain), s"${l.id}-lbuilder")))
    domain.Listeners.map(_.id).toSet
  }
  val allListeners = spawnListenerBuilders()
  val listeners = mutable.Map[String, ActorRef]()

  var balancerBuilder : ProxyBalancerBuilder[_] = EmptyBalancerBuilder
  var healthcheckResponse : HttpResponse = HttpResponse(StatusCodes.ServiceUnavailable)

  def buildHealthcheck() = {
    // healthcheck checklist:
    // 1) All middleware actors are built
    // 2) All listener actors are built
    // 3) At some point in time, all registered services were healthy
    val allMiddlewarePresent = allMiddleware.forall(middleware.contains)
    val allListenersPresent = listeners.keySet == allListeners
    allUpstreamsHealthy = allUpstreamsHealthy || upstreamStatus.exists(_.values.forall(_.state.healthy))
    healthcheckResponse = HttpResponse(
      if (allMiddlewarePresent && allListenersPresent && allUpstreamsHealthy) StatusCodes.OK else StatusCodes.ServiceUnavailable,
      HttpEntity(
        ContentTypes.`application/json`,
        HealthcheckInfo.fromStatus(settings.Host, upstreamStatus.getOrElse(Map.empty), allMiddleware, middleware.keySet, allListeners, listeners.keySet).toJson.prettyPrint
      )
    )
  }

  val buildTimer = metrics.timer("buildRouter")
  def buildRouter() = buildTimer.time {
    buildHealthcheck()
    val upstreams = upstreamStatus.getOrElse(Map.empty)
    val orderedMiddleware = allMiddleware.flatMap(middleware.get)
    val combinedEndpoints = upstreams.values.flatMap(_.state.allEndpoints.toList)
    val swaggerResponse = HttpResponse(
      StatusCodes.OK,
      HttpEntity(ContentTypes.`application/json`, mapper.writeValueAsBytes(SwaggerFetcher.swaggerSpec(combinedEndpoints)))
    )

    val allEndpointTemplates = upstreams.flatMap(_._2.state.allEndpoints.keys).toSet
    val healthyEndpoints = upstreams.flatMap(kvp => kvp._2.state.allEndpoints.keys.filterNot(kvp._2.state.unhealthyEndpoints)).toSet
    val unhealthyEndpoints = allEndpointTemplates.diff(healthyEndpoints)

    // todo: what happens if an upstream defines these paths?
    val localEndpoints = List(
      RoutableEndpoint(
        settings.HealthcheckTemplate,
        EndpointDetails.empty,
        Left((_ : HttpRequest) => healthcheckResponse)
      ),
      RoutableEndpoint(
        settings.MetricsTemplate,
        EndpointDetails.empty,
        Left((_ : HttpRequest) => Registry.metricsResponse)
      ),
      RoutableEndpoint(
        settings.SwaggerTemplate,
        EndpointDetails.empty,
        Left((_ : HttpRequest) => swaggerResponse)
      )
    )
    // If a given upstream is unhealthy for an endpoint, don't route to it.
    // However, if all upstreams are unhealthy for the endpoint, route to it anyways - we want it to go through the
    // middleware and possibly get the cache to handle it, rather than just fast failing via the routing table
    val remoteEndpoints = upstreams.values.flatMap { upstreamState =>
      val routableEndpoints = upstreamState.state.allEndpoints.filter { case (endpoint, _) =>
        !upstreamState.state.unhealthyEndpoints.contains(endpoint) ||
        unhealthyEndpoints.contains(endpoint)
      }
      routableEndpoints.map(kvp => RoutableEndpoint(kvp._1, kvp._2, Right(upstreamState.state.proxy)))
    }

    // todo: Config drive this (random vs roundRobin vs leastactive)
    val builder = new RoundRobinBalancerBuilder(orderedMiddleware, context, upstreams.values.map(ps => ps.state.proxy -> ps).toMap)
    val router = new RoutingTable(localEndpoints ++ remoteEndpoints, settings.DefaultServiceLocation, settings.LocalServiceName, builder)
    shield ! ShieldActorMsgs.RouterUpdated(domain.DomainName, router)
    val toTearDown = balancerBuilder
    // todo: use global timeout config
    context.system.scheduler.scheduleOnce(60.seconds) {
      toTearDown.teardown()
    }
    balancerBuilder = builder
  }

  def receive = {
    case MiddlewareUpdated(updatedMiddleware) =>
      if (!allMiddleware.contains(updatedMiddleware.name)) {
        log.warning(s"Config watcher received unrecognized middleware: ${updatedMiddleware.name}")
      } else {
        log.info(s"Received middleware actor for ${updatedMiddleware.name}")
        val old = middleware.put(updatedMiddleware.name, updatedMiddleware)
        if (old.isDefined && old.get.actor != updatedMiddleware.actor) {
          // todo: use the global "max request time" here
          log.info(s"Scheduling old ${updatedMiddleware.name} middleware actor for termination in 10 seconds")
          context.system.scheduler.scheduleOnce(10.seconds, old.get.actor, PoisonPill)
        }
        buildRouter()
      }

    case UpstreamsUpdated(upstreams) =>
      log.info(s"Rebuilding router for ${upstreams.size} upstream hosts")
      if (upstreamStatus != Some(upstreams)) {
        upstreamStatus = Some(upstreams)
        buildRouter()
      }

    case ListenerUpdated(id, listener) =>
      if (!allListeners.contains(id)) {
        log.warning(s"Config watcher received unrecognized listener: $id")
      } else {
        val old = listeners.put(id, listener)
        if (old.isDefined && old.get != listener) {
          // todo: use the global "max request time" here
          log.info(s"Scheduling old $id listener actor for termination in 10 seconds")
          context.system.scheduler.scheduleOnce(10.seconds, old.get, PoisonPill)
        }
        buildHealthcheck()

        shield ! ShieldActorMsgs.ListenersUpdated(domain.DomainName, listeners)
      }
  }
}
