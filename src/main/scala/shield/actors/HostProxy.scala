package shield.actors

import java.util.concurrent.TimeoutException

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import akka.pattern.{AskTimeoutException, CircuitBreaker}
import shield.actors.config.WeightWatcherMsgs.{SetTargetWeights, SetWeights}
import shield.actors.config.{ProxyState, UpstreamAggregatorMsgs, WeightWatcher}
import shield.config._
import shield.implicits.FutureUtil
import shield.metrics.Instrumented
import shield.proxying.{HttpProxyLogic, ProxiedResponse, ProxyRequest}
import shield.routing._
import shield.swagger.{SwaggerDetails, SwaggerFetcher}
import spray.client.pipelining.SendReceive
import spray.http.{HttpResponse, StatusCodes}
import spray.http.StatusCodes.ServerError

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object HostProxyMsgs {
  case object ShutdownProxy
  case class FetchSucceeded(details: SwaggerDetails)
  case class FetchFailed(error: Throwable)
  case object RetryFetch

  case class EndpointBreakerOpen(endpoint: EndpointTemplate)
  case class EndpointBreakerHalfOpen(endpoint: EndpointTemplate)
  case class EndpointBreakerClosed(endpoint: EndpointTemplate)

  case object HostBreakerOpen
  case object HostBreakerHalfOpen
  case object HostBreakerClosed
}

object HostProxy {
  case class UpstreamFailedResponse(response: HttpResponse) extends RuntimeException

  def props(serviceLocation: ServiceLocation, proxyType: ServiceType, localServiceLocation: ServiceLocation, pipeline: SendReceive) : Props = Props(new HostProxy(serviceLocation, proxyType, localServiceLocation, pipeline))
}
// todo: pull out the common bits of logic that will be reused by future host proxy types
class HostProxy(val serviceLocation: ServiceLocation, proxyType: ServiceType, localServiceLocation: ServiceLocation, pipeline: SendReceive) extends Actor with ActorLogging with RestartLogging with Instrumented {
  import context.dispatcher
  import context.become
  import HostProxy._
  import HostProxyMsgs._

  val settings = Settings(context.system)
  def buildFetcher() = proxyType.fetcher(pipeline, settings)
  val fetcher = buildFetcher
  var serviceName = "(unknown)"
  var serviceVersion = "(unknown)"
  def broadcastState(endpoints: Map[EndpointTemplate, EndpointDetails], healthy: Boolean, status: String, unhealthyEndpoints: Set[EndpointTemplate]) =
    context.parent ! UpstreamAggregatorMsgs.StateUpdated(serviceLocation, ProxyState(serviceName, serviceVersion, proxyType, self, endpoints, healthy, status, unhealthyEndpoints))

  def shutdown(endpoints: Map[EndpointTemplate, EndpointDetails]) : Actor.Receive = {
    {
      // we should have broadcasted that this host doesn't support any endpoints, so we should only get a few of these trickling in until the router gets updated.
      case ProxyRequest(template, request) =>
        if (!endpoints.contains(template)) {
          log.error(s"HostProxy received request for an unsupported endpoint $serviceLocation $template")
        } else {
          val _sender = sender()
          pipeline(HttpProxyLogic.scrubRequest(request))
            .map(r => _sender ! ProxiedResponse(serviceLocation, serviceName, template, endpoints(template).params, r))
            .andThen(FutureUtil.logFailure("HostProxy::drainingUpstream"))
        }

      case e => log.warning(s"Unexpected message during HostProxy.shutdown: $e")
    }
  }

  def active(endpoints: Map[EndpointTemplate, EndpointDetails]) : Actor.Receive = {
    // todo: make the breakers adaptive.  maxfailures = constrain(lowconstant, .05*throughput_rate, highconstant), callTimeout=constrain(lowconstant, latencyPercentile[95], highconstant)  nb- stats are per endpoint
    // todo: config drive the constants
    // todo: gauges per CircuitBreaker
    val hostBreaker = new CircuitBreaker(context.system.scheduler, maxFailures=50, callTimeout=10.seconds, resetTimeout=10.seconds)
      .onOpen(self ! HostBreakerOpen)
      .onHalfOpen(self ! HostBreakerHalfOpen)
      .onClose(self ! HostBreakerClosed)
    val endpointBreakers = endpoints.keys.map(endpointTemplate => endpointTemplate -> new CircuitBreaker(context.system.scheduler, maxFailures=25, callTimeout=10.seconds, resetTimeout=10.seconds)
      .onOpen(self ! EndpointBreakerOpen(endpointTemplate))
      .onHalfOpen(self ! EndpointBreakerHalfOpen(endpointTemplate))
      .onClose(self ! EndpointBreakerClosed(endpointTemplate))
    ).toMap
    var hostOpen = false
    var openEndpoints = Set[EndpointTemplate]()

    def broadcastActiveState() = {
      val status = if (hostOpen) {
        "serving - host breaker tripped open"
      } else if (openEndpoints.nonEmpty) {
        s"serving - ${openEndpoints.size} endpoint breakers tripped open"
      } else {
        "serving"
      }
      // we're healthy if at least one endpoint on this host is still serving
      val healthy = !hostOpen && !(openEndpoints.size == endpoints.size)
      broadcastState(endpoints, healthy, status, if (hostOpen) endpoints.keySet else openEndpoints)
    }

    {
      case ProxyRequest(template, request) =>
        if (!endpoints.contains(template)) {
          log.error(s"HostProxy received request for an unsupported endpoint $serviceLocation $template")
        } else {
          val _sender = sender()
          val scrubbed = HttpProxyLogic.scrubRequest(request)
          // endpoint breaker on the outside, we don't want an open endpoint to open the entire host
          val responseFuture = endpointBreakers(template).withCircuitBreaker {
            hostBreaker.withCircuitBreaker {
              pipeline(scrubbed).flatMap { res =>
                // convert 5XX to failed future here so it counts as a strike against the circuit breakers
                if (res.status.isInstanceOf[ServerError])
                  Future.failed(UpstreamFailedResponse(res))
                else
                  Future.successful(res)
              }
            }
          }
          val logged = responseFuture.andThen(FutureUtil.logFailure("HostProxy::rawUpstream", {
            case _ : UpstreamFailedResponse =>
            case _ : AskTimeoutException =>
          }))
          val recovered : Future[ProxiedResponse] = logged.map(r => ProxiedResponse(serviceLocation, serviceName, template, endpoints(template).params, r))
            .recover {
              case UpstreamFailedResponse(resp) => ProxiedResponse(serviceLocation, serviceName, template, endpoints(template).params, resp)
              case _ : AskTimeoutException => ProxiedResponse(localServiceLocation, settings.LocalServiceName, template, endpoints(template).params, HttpResponse(StatusCodes.GatewayTimeout))
              case _ => ProxiedResponse(localServiceLocation, settings.LocalServiceName, template, endpoints(template).params, HttpResponse(StatusCodes.ServiceUnavailable))
            }
          recovered
            .map(pr => _sender ! pr)
            .andThen(FutureUtil.logFailure("HostProxy::finalUpstream"))
        }

      case EndpointBreakerClosed(endpoint) =>
        log.warning(s"Breaker closed for endpoint $endpoint on host $serviceLocation")
        openEndpoints -= endpoint
        broadcastActiveState()
      case EndpointBreakerHalfOpen(endpoint) =>
        log.warning(s"Breaker half opened for endpoint $endpoint on host $serviceLocation")
        openEndpoints -= endpoint
        broadcastActiveState()
      case EndpointBreakerOpen(endpoint) =>
        log.warning(s"Breaker opened for endpoint $endpoint on host $serviceLocation")
        openEndpoints += endpoint
        broadcastActiveState()

      case HostBreakerClosed =>
        log.warning(s"Breaker closed for host $serviceLocation")
        hostOpen = false
        broadcastActiveState()
      case HostBreakerHalfOpen =>
        log.warning(s"Breaker half opened for host $serviceLocation")
        hostOpen = false
        broadcastActiveState()
      case HostBreakerOpen =>
        log.warning(s"Breaker opened for host $serviceLocation")
        hostOpen = true
        broadcastActiveState()

      case ShutdownProxy =>
        // stop advertising support for all endpoints so they re-route or shed
        broadcastState(Map.empty, healthy=true, "draining", Set.empty)
        // todo: use max request config time
        context.system.scheduler.scheduleOnce(60.seconds, self, PoisonPill)
        // keep internal support for the endpoints for any in-flight messages to this actor
        become(shutdown(endpoints))

      case e => log.warning(s"Unexpected message during HostProxy.active: $e")
    }
  }

  def fetchDocs() = {
    val fetchPromise = Promise[SwaggerDetails]()
    fetchPromise.completeWith(fetcher.fetch(serviceLocation))
    context.system.scheduler.scheduleOnce(30.seconds) {
      fetchPromise.tryFailure(new TimeoutException("Failed to retrieve docs in time"))
    }

    fetchPromise.future.onComplete {
      case Success(details) => self ! FetchSucceeded(details)
      case Failure(e)  => self ! FetchFailed(e)
    }
    broadcastState(Map.empty, healthy=false, "fetching swagger docs", Set.empty)
  }
  fetchDocs()

  def initializing : Actor.Receive = {
    case FetchSucceeded(details) =>
      serviceName = details.service
      serviceVersion = details.version
      become(active(details.endpoints))
      broadcastState(details.endpoints, healthy=true, "serving", Set.empty)

    case FetchFailed(err) =>
      log.error(err, s"Failed to retrieve the swagger documentation from $serviceLocation")
      broadcastState(Map.empty, healthy=false, "failed to fetch swagger docs, retrying in 30 seconds", Set.empty)
      context.system.scheduler.scheduleOnce(30.seconds, self, RetryFetch)

    case RetryFetch =>
      fetchDocs()

    case e => log.warning(s"Unexpected message during HostProxy.initializing: $e")
  }
  def receive = initializing
}