package shield.actors.listeners

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.event.LoggingAdapter
import nl.grons.metrics.scala.{Meter, Timer}
import shield.actors.{RequestProcessorCompleted, RestartLogging}
import org.joda.time.format.ISODateTimeFormat
import shield.config.{HttpServiceLocation, Settings}
import shield.config.{DomainSettings, Settings}
import shield.metrics.Instrumented
import spray.http.{HttpHeader, HttpResponse}
import spray.json._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}


case object FlushLogs
case object LogsFlushed
case class AccessLogs(buffer: Seq[JsObject])

object LogCollector {
  def handleResults(self: ActorRef, droppedMeter: Meter, log: LoggingAdapter, logCount: Int) : PartialFunction[Try[HttpResponse], Unit] = {
    case Success(r) =>
      self ! LogsFlushed
      if (r.status.isFailure) {
        droppedMeter.mark(logCount)
        log.warning(s"Error forwarding access logs: ${r.entity.asString}")
      }
    case Failure(f) =>
      self ! LogsFlushed
      droppedMeter.mark(logCount)
      log.warning(s"Error forwarding access logs: $f")
  }
}

class LogCollector(id: String, domain: DomainSettings, forwarders: Seq[ActorRef], maxBufferSize: Int) extends Actor with ActorLogging with RestartLogging with Instrumented {
  import context.dispatcher

  val settings = Settings(context.system)
  val shieldHost = JsString(settings.DefaultServiceLocation.baseUrl.toString)

  var buffer = ArrayBuffer[JsObject]()

  val dateTimeFormat = ISODateTimeFormat.dateTime()
  val logSerializationTimer: Timer = metrics.timer("log-serialization")

  // todo: profiling optimization - 1% of CPU time is spent here while under load
  def logJson(r: RequestProcessorCompleted): JsObject = logSerializationTimer.time {
    JsObject(Map(
      // todo: profiling optimization: use seconds, and cache it per second
      "@timestamp" -> JsString(dateTimeFormat.print(System.currentTimeMillis() - r.overallTiming)),
      "method" -> JsString(r.completion.request.method.toString()),
      // todo: profiling optimization: uri.toString is used in several places - can we cache it?
      "request_headers" -> JsObject(extractHeaders(r.completion.request.headers, domain.loggedRequestHeaders)),
      "response_headers" -> JsObject(extractHeaders(r.completion.details.response.headers, domain.loggedResponseHeaders)),
      "path" -> JsString(r.completion.request.uri.toString()),
      "template" -> JsString(r.completion.details.template.path.toString),
      "responding_service" -> JsString(r.completion.details.serviceName),
      "responding_host" -> JsString(r.completion.details.serviceLocation.locationName),
      "shield_host" -> shieldHost,
      "overall_time" -> JsNumber(r.overallTiming),
      "middleware_time" -> JsObject(r.middlewareTiming.map { case (attr, timing) => attr -> JsNumber(timing) }),
      // todo: cache header name should be config driven
      "cache_status" -> JsString(r.completion.details.response.headers.find(_.lowercaseName == "x-cache").map(_.value).getOrElse("nocache")),
      "response_size" -> JsNumber(r.completion.details.response.entity.data.length),
      "response_status" -> JsNumber(r.completion.details.response.status.intValue)
    ))
  }

  val bufferSizeHistogram = metrics.histogram("bufferSizeOnFlush", id)
  var flushTimer = context.system.scheduler.scheduleOnce(100.millis, self, FlushLogs)
  def flushLogs() = {
    flushTimer.cancel()
    bufferSizeHistogram += buffer.length
    if (buffer.nonEmpty) {
      val msg = AccessLogs(buffer)
      forwarders.foreach {
        _ ! msg
      }
      buffer = ArrayBuffer()
    }
    flushTimer = context.system.scheduler.scheduleOnce(100.millis, self, FlushLogs)
  }

  def receive: Receive = {
    case r: RequestProcessorCompleted =>
      buffer += logJson(r)
      if (buffer.length >= maxBufferSize) {
        flushLogs()
      }

    case FlushLogs =>
      flushLogs()
  }

  def extractHeaders(headers: List[HttpHeader], toExtract: Set[String]): Map[String, JsString] = {
    headers.filter(h => toExtract.contains(h.lowercaseName)).map(h => h.name -> JsString(h.value)).toMap
  }
}
