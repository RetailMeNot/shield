package shield.actors

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, Props}
import com.codahale.metrics.{Timer => DropwizardTimer}
import nl.grons.metrics.scala.{Meter, Timer}
import shield.config.{ServiceLocation, Settings}
import shield.metrics.Instrumented
import shield.proxying.HttpProxyLogic
import shield.routing._
import spray.http._

import scala.collection.mutable
import scala.concurrent.duration._

case class Middleware(name: String, sla: FiniteDuration, actor: ActorRef)

case class DownstreamRequest(
  stage: String,
  destination: RoutingDestination,
  request: HttpRequest
)
case class DownstreamRequestTimeout(cmd: ForwardRequestCmd)

case class ResponseDetails(
  serviceLocation: ServiceLocation,
  serviceName: String,
  template: EndpointTemplate,
  explicitCacheParams: Option[Set[Param]],
  response: HttpResponse
)

case class UpstreamResponse(
  stage: String,
  request: HttpRequest,
  details: ResponseDetails
)
case class UpstreamResponseTimeout(cmd: ForwardResponseCmd)

sealed trait MiddlewareResponse

case class ForwardRequestCmd(
  stage: String,
  request: HttpRequest,
  responseMiddleware: Option[Middleware] = None
) extends MiddlewareResponse

case class ForwardResponseCmd(
  stage: String,
  details: ResponseDetails
) extends MiddlewareResponse

case class ResponseCompleted(
  request: HttpRequest,
  details: ResponseDetails
)
case class RequestProcessorCompleted(
  completion: ResponseCompleted,
  overallTiming: Long,
  middlewareTiming: Map[String, Long]
)

trait ProcessingStage {
  def stageName : String
  def complete(metricsSoFar: Map[String, Long]) : Map[String, Long]
  def behavior(requestListener: Receive, responseListener: Receive, completeResponse: Receive) : Receive
}

abstract class RequestProcessingStage extends ProcessingStage {
  var timer : Option[DropwizardTimer.Context] = None
  def start(latestRequest: HttpRequest) : Unit = {
    timer = Some(RequestProcessor.timer(stageName).timerContext())
    RequestProcessor.meter(stageName).mark()
    execute(latestRequest)
  }
  protected def execute(latestRequest: HttpRequest) : Unit
  def complete(metricsSoFar: Map[String, Long]): Map[String, Long] = timer.map(c => metricsSoFar + (stageName -> (c.stop() / 1000000))).getOrElse(metricsSoFar)
}

abstract class ResponseProcessingStage extends ProcessingStage {
  var timer: Option[DropwizardTimer.Context] = None
  def start(details: ResponseDetails): Unit = {
    timer = Some(RequestProcessor.timer(stageName).timerContext())
    RequestProcessor.meter(stageName).mark()
    execute(details)
  }
  protected def execute(details: ResponseDetails): Unit
  def complete(metricsSoFar: Map[String, Long]): Map[String, Long] = timer.map(c => metricsSoFar + (stageName -> (c.stop() / 1000000))).getOrElse(metricsSoFar)
}

class PreProcessStage extends RequestProcessingStage {
  def stageName = "pre_process"
  def execute(latestRequest: HttpRequest) = {}
  def behavior(requestListener: Receive, responseListener: Receive, completeResponse: Receive): Receive = requestListener orElse responseListener
}

class CompletionStage(originalRequest: HttpRequest)(implicit self: ActorRef) extends ResponseProcessingStage {
  def stageName = "complete"

  def execute(details: ResponseDetails): Unit = {
    self ! ResponseCompleted(originalRequest, details)
  }

  def behavior(requestListener: Receive, responseListener: Receive, completeResponse: Receive): Receive = completeResponse
}

class RequestMiddlewareStage(destination: RoutingDestination, middleware: Middleware, context: ActorContext)(implicit self: ActorRef) extends RequestProcessingStage {
  import context.dispatcher
  def stageName = s"request.${middleware.name}"
  def execute(latestRequest: HttpRequest) = {
    middleware.actor ! DownstreamRequest(stageName, destination, latestRequest)
    context.system.scheduler.scheduleOnce(middleware.sla, self, DownstreamRequestTimeout(ForwardRequestCmd(stageName, latestRequest, None)))
  }
  def behavior(requestListener: Receive, responseListener: Receive, completeResponse: Receive): Receive = requestListener orElse responseListener
}

class ProxyStage(localService: ServiceLocation, localServiceName: String, destination: RoutingDestination, context: ActorContext)(implicit self: ActorRef) extends RequestProcessingStage {
  import context.dispatcher
  def stageName = "proxy"
  def execute(latestRequest: HttpRequest): Unit = {
    destination.upstreamBalancer.proxy(destination.template, latestRequest).foreach(pr => self ! ForwardResponseCmd(stageName, ResponseDetails(pr.upstreamService, pr.serviceName, pr.template, Some(pr.cacheParams), pr.response)))
    //todo: config drive this sla (reuse global timeout?)
    context.system.scheduler.scheduleOnce(5.seconds, self, UpstreamResponseTimeout(ForwardResponseCmd(stageName, ResponseDetails(localService, localServiceName, destination.template, None, HttpResponse(StatusCodes.GatewayTimeout)))))
  }

  def behavior(requestListener: Receive, responseListener: Receive, completeResponse: Receive): Receive = responseListener
}

class ResponseMiddlewareStage(matchingRequest: HttpRequest, middleware: Middleware, context: ActorContext)(implicit self: ActorRef) extends ResponseProcessingStage {
  import context.dispatcher
  def stageName = s"response.${middleware.name}"

  def execute(details: ResponseDetails) = {
    middleware.actor ! UpstreamResponse(stageName, matchingRequest, details)
    context.system.scheduler.scheduleOnce(middleware.sla, self, UpstreamResponseTimeout(ForwardResponseCmd(stageName, details)))
  }

  def behavior(requestListener: Receive, responseListener: Receive, completeResponse: Receive): Receive = responseListener
}


object RequestProcessor extends Instrumented {
  def props(router: RequestRouter, responseListeners: collection.Map[String, ActorRef], tcpListener: ActorRef, localService: ServiceLocation, ignoredExtensions: Set[String]) : Props = Props(new RequestProcessor(router, responseListeners, tcpListener, localService, ignoredExtensions))

  private val stageTimers = mutable.Map[String, Timer]()
  def timer(stage: String) = stageTimers.getOrElseUpdate(stage, metrics.timer(s"timing-$stage"))

  private val stageMeters = mutable.Map[String, Meter]()
  def meter(stage: String) = stageMeters.getOrElseUpdate(stage, metrics.meter(s"stage-$stage"))
}
// todo: if safe, retry on other hosts if 5XX (needs config on whether downstream gets are safe)
// todo: handle chunked/streaming requests and responses
// todo: cancel request if we've passed Sprays timeout setting
class RequestProcessor(router: RequestRouter, responseListeners: collection.Map[String, ActorRef], tcpListener: ActorRef, localService: ServiceLocation, ignoredExtensions: Set[String]) extends Actor with ActorLogging with RestartLogging with Instrumented {
  import context._

  val settings = Settings(context.system)

  var currentStage: ProcessingStage = new PreProcessStage()
  def becomeStage[T <: ProcessingStage](stage: T) = {
    currentStage = stage
    become(stage.behavior(listenRequest, listenResponse, completeResponse))
    stage
  }

  val overallTimer = RequestProcessor.timer("overall").timerContext()
  var stageMetrics = Map[String, Long]()

  val pendingRequestStages: mutable.Queue[RequestProcessingStage] = mutable.Queue()
  var disabledListeners: Set[String] = Set[String]()
  val pendingResponseStages: mutable.Stack[ResponseProcessingStage] = mutable.Stack()

  def applyRequestCmd(cmd: ForwardRequestCmd) = {
    cmd.responseMiddleware.foreach(m => pendingResponseStages.push(new ResponseMiddlewareStage(cmd.request, m, context)))
    stageMetrics = currentStage.complete(stageMetrics)
    becomeStage(pendingRequestStages.dequeue()).start(cmd.request)
  }

  def listenRequest : Receive = {
    case cmd: ForwardRequestCmd if cmd.stage == currentStage.stageName =>
      applyRequestCmd(cmd)
    case timeout: DownstreamRequestTimeout if timeout.cmd.stage == currentStage.stageName =>
      RequestProcessor.meter(s"${currentStage.stageName}-timeout").mark()
      applyRequestCmd(timeout.cmd)
  }

  def applyResponseCmd(cmd: ForwardResponseCmd) = {
    stageMetrics = currentStage.complete(stageMetrics)
    becomeStage(pendingResponseStages.pop()).start(cmd.details)
  }

  def listenResponse : Receive = {
    case cmd: ForwardResponseCmd if cmd.stage == currentStage.stageName =>
      applyResponseCmd(cmd)
    case timeout: UpstreamResponseTimeout if timeout.cmd.stage == currentStage.stageName =>
      RequestProcessor.meter(s"${currentStage.stageName}-timeout").mark()
      applyResponseCmd(timeout.cmd)
  }

  def completeResponse : Receive = {
    case completion: ResponseCompleted =>
      val event = RequestProcessorCompleted(
        completion.copy(details=completion.details.copy(response=HttpProxyLogic.scrubResponse(completion.details.response))),
        overallTimer.stop() / 1000000,
        stageMetrics
      )
      val negotiatedEvent = HttpProxyLogic.negotiateEncoding(event)
      tcpListener ! negotiatedEvent.completion.details.response

      //filter out listeners that are disabled
      for (listener <- responseListeners.filterNot(l => disabledListeners.contains(l._1))) {
        // todo: this doesn't capture spray's auto responses (400s from a bad character in the uri, 501 from unrecognized method, etc))
          listener._2 ! negotiatedEvent
      }
      context.stop(self)
  }


  // the reason we take the request as a message instead of a constructor parameter is in the case of actor restart:  The message only comes once,
  // but the parameter would restart the process every time.  We don't want multiple submissions of a POST request
  import shield.implicits.HttpImplicits._
  def receive = {
    case request: HttpRequest => RequestProcessor.timer("routing").time {
      val modifiedRequest = request
        .withTrustXForwardedProto(settings.trustXForwardedProto)
        .withTrustXForwardedFor(settings.trustProxies)

      pendingResponseStages.push(new CompletionStage(modifiedRequest))
      becomeStage(currentStage)
      //todo create a more structured preprocessor
      val processedRequest = modifiedRequest
        .withStrippedExtensions(ignoredExtensions)

      router.route(processedRequest).fold(
        details => {
          self ! ForwardResponseCmd(currentStage.stageName, details)
        },
        destination => {
          pendingRequestStages.enqueue(destination.activeMiddleware.map(m => new RequestMiddlewareStage(destination, m, context)): _*)
          pendingRequestStages.enqueue(new ProxyStage(localService, settings.LocalServiceName, destination, context))
          disabledListeners = destination.disabledListeners //update disabled listeners for this destination
          self ! ForwardRequestCmd(
            currentStage.stageName,
            processedRequest,
            None
          )
        }
      )
    }
  }
}
