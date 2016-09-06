package shield.actors.listeners

import akka.actor.{Actor, ActorLogging}
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import shield.actors.RestartLogging
import shield.aws.AWSImplicits._
import shield.aws.AWSSigningConfig
import shield.metrics.Instrumented
import spray.client.pipelining._
import spray.json.DefaultJsonProtocol._
import spray.json._

class ConsoleLogger(id: String) extends Actor with ActorLogging with RestartLogging with Instrumented {
  implicit val ctx = context.dispatcher

  def receive = {
    case AccessLogs(buffer) =>
      for (accessLog <- buffer) {
        log.info(accessLog.toString())
      }
  }
}
