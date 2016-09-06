package shield.actors.config.upstream

import java.util.concurrent.TimeUnit

import akka.actor._
import com.typesafe.config.Config
import shield.actors.RestartLogging
import shield.actors.config.{ServiceDetails, UpstreamAggregatorMsgs}
import shield.config._
import spray.http.Uri
import shield.actors.config._
import shield.config.{ServiceLocation, ServiceType}
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration._
import scala.language.postfixOps


case class RawUpstreamService(serviceType: String, serviceLocation: String, weight: Option[Int])

object UpstreamServiceProtocol extends DefaultJsonProtocol {
  implicit val upstreamServiceHostFormat = jsonFormat3(RawUpstreamService)
}

class S3UpstreamWatcher(domainConfig: Config) extends Actor
    with ActorLogging with RestartLogging
    with JsonUpstreamUpdater
    with UpstreamWatcher {

  val system = akka.actor.ActorSystem("system")
  import system.dispatcher

  val config = domainConfig.getConfig("s3-upstream-watcher")
  val s3WatcherService = context.actorOf(Props(
    classOf[S3ObjectWatcher],
    config.getString("bucket-name"),
    config.getString("config-filename")))

  val refreshInterval = Duration(config.getDuration("refresh-interval", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  var cancellable = system.scheduler.schedule(
    0 seconds,
    refreshInterval,
    s3WatcherService,
    Refresh)

  override def postStop() = {
    cancellable.cancel()
  }
}

trait JsonUpstreamUpdater extends ActorLogging with UpstreamParser{
  self: Actor =>

  def receive: Receive = {
    case ChangedContents(contents) =>
      import UpstreamServiceProtocol._
      import spray.json._
      try {
        val json = contents.parseJson
        log.debug(s"new parsed config ${json.compactPrint}")
        val upstreams : Map[ServiceLocation, ServiceDetails] = json.convertTo[List[RawUpstreamService]].map(raw => parseUpstreamEntry(raw.serviceType, raw.serviceLocation, raw.weight.getOrElse(1))).toMap
        context.parent ! UpstreamAggregatorMsgs.DiscoveredUpstreams(upstreams)
      } catch {
        case e: Throwable => log.warning(s"Error encountered while parsing json upstream config: $e")
      }
  }
}
