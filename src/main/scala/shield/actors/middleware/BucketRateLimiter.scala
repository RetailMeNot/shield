package shield.actors.middleware

import akka.actor.{Actor, ActorLogging, Props}
import nl.grons.metrics.scala.{Meter, Timer}
import shield.actors._
import shield.config.{ServiceLocation, Settings}
import shield.implicits.FutureUtil
import shield.kvstore.KVStore
import shield.metrics.Instrumented
import spray.http.{HttpResponse, StatusCodes}

object BucketRateLimiter {
  def props(id: String, bypassHeader: String, callsPer: Int, perSeconds: Int, store: KVStore, location: ServiceLocation) : Props = Props(new BucketRateLimiter(id, bypassHeader, callsPer, perSeconds, store, location))
}
class BucketRateLimiter(id: String, bypassHeader: String, callsPer: Int, perSeconds: Int, store: KVStore, location: ServiceLocation) extends Actor with ActorLogging with RestartLogging with Instrumented{
  import context._

  val settings = Settings(context.system)
  val localWork: Timer = metrics.timer("localWork", id)
  val bypassMeter: Meter = metrics.meter("bypass", id)
  val blockMeter: Meter = metrics.meter("block", id)
  val passMeter: Meter = metrics.meter("pass", id)
  val kvWorkTimer = timing("kvWork", id)

  def receive = {
    // todo: x-ratelimit response headers?
    case ur : DownstreamRequest => localWork.time {
      val _sender = sender()
      if (ur.request.headers.exists(_.lowercaseName == bypassHeader)) {
        bypassMeter.mark()
        _sender ! ForwardRequestCmd(ur.stage, ur.request, None)
      } else kvWorkTimer {
        // todo: profiling optimization - can we get this from the connection instead of the header?
        // similarly we spend a fair amount of time stringifying request.uri.  Let's do this once per request and cache it in the request context
        val ip = ur.request.headers.find(_.lowercaseName == "client-address").get.value
        // todo: reasonable timeout
        store.tokenRateLimit(ip, callsPer, perSeconds).andThen(FutureUtil.logFailure("BucketRateLimiter::checkLimit")).recover {
          case _ => true
        }.map {
          if (_) {
            passMeter.mark()
            _sender ! ForwardRequestCmd(ur.stage, ur.request)
          } else {
            blockMeter.mark()
            _sender ! ForwardResponseCmd(ur.stage, ResponseDetails(location, settings.LocalServiceName, ur.destination.template, None, HttpResponse(StatusCodes.TooManyRequests)))
          }
        }
      }
    }
  }
}
