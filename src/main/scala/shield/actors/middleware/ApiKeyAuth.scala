package shield.actors.middleware

import akka.actor.{Actor, ActorLogging, Props}
import shield.actors._
import shield.config.{ServiceLocation, Settings}
import shield.metrics.Instrumented
import spray.http.{HttpResponse, StatusCodes}

object ApiKeyAuth {
  def props(header: String, allowed: Set[String], caseSensitive: Boolean, location: ServiceLocation) : Props = Props(new ApiKeyAuth(header, allowed, caseSensitive, location))
}
class ApiKeyAuth(headerName: String, allowedKeys: Set[String], caseSensitive: Boolean, location: ServiceLocation) extends Actor with ActorLogging with RestartLogging with Instrumented {

  val settings = Settings(context.system)
  val headerNameLower = headerName.toLowerCase
  val allowedValues : Set[String] = if (caseSensitive) allowedKeys else allowedKeys.map(_.toLowerCase)

  val timer = metrics.timer("api-key-auth")
  def receive = {
    case r: DownstreamRequest => timer.time {
      val header = r.request.headers.find(_.lowercaseName == headerNameLower)
      val allowed = header.exists(h => if (caseSensitive) allowedValues.contains(h.value) else allowedValues.contains(h.value.toLowerCase))

      if (allowed) {
        sender ! ForwardRequestCmd(r.stage, r.request, None)
      } else {
        sender ! ForwardResponseCmd(
          r.stage,
          ResponseDetails(
            location,
            settings.LocalServiceName,
            r.destination.template,
            None,
            HttpResponse(if (header.isDefined) StatusCodes.Unauthorized else StatusCodes.Forbidden)
          )
        )
      }
    }
  }
}
