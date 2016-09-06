package shield.actors.listeners

import akka.actor.{ActorRef, Actor, ActorLogging}
import shield.actors.RestartLogging
import shield.metrics.Instrumented
import spray.client.pipelining._
import spray.http.{HttpResponse, FormData}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.duration._
import scala.util.{Failure, Success}


class FluentdHttpForwarder(id: String, host: String, maxOutstanding: Int) extends Actor with ActorLogging with RestartLogging with Instrumented {
  implicit val ctx = context.dispatcher

  // todo: timeout?
  val pipeline = sendReceive
  var outstanding = metrics.counter("outstandingPosts", id)
  val droppedMeter = metrics.meter("droppedAccessLogs", id)
  val postTimer = timing("postToFluentd", id)

  def receive = {
    case LogsFlushed =>
      outstanding -= 1

    case AccessLogs(buffer) =>
      if (buffer.nonEmpty) {
        if (outstanding.count >= maxOutstanding) {
          droppedMeter.mark(buffer.length)
        } else postTimer {
          outstanding += 1

          val json = buffer.toJson.compactPrint
          val data = FormData(Map(("json", json)))
          val req = Post(host, data)
          pipeline(req) andThen LogCollector.handleResults(self, droppedMeter, log, buffer.length)
        }
      }
  }
}

