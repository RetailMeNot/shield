package shield.actors.config.domain

import java.util.concurrent.TimeUnit

import akka.actor.{ActorLogging, ActorRef, PoisonPill, Props}
import com.typesafe.config.ConfigFactory
import shield.actors.config._
import shield.actors.{RestartLogging, ShieldActorMsgs}
import shield.config.DomainSettings

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class S3DomainWatcher extends DomainWatcher with ActorLogging with RestartLogging {

  import context.system

  var domains = Map[String, DomainSettings]()
  var configWatchers = Map[String, ActorRef]()

  val config = settings.config.getConfig("shield.s3-domain-watcher")
  val s3WatcherService = context.actorOf(Props(
    classOf[S3ObjectWatcher],
    config.getString("bucket-name"),
    config.getString("config-filename")))

  val refreshInterval = Duration(config.getDuration("refresh-interval", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  var cancellable = system.scheduler.schedule(
    0.seconds,
    refreshInterval,
    s3WatcherService,
    Refresh)

  override def postStop() = {
    cancellable.cancel()
  }

  def teardownConfigWatcher(configWatcher: ActorRef) = {
    context.system.scheduler.scheduleOnce(60.seconds) {
      configWatcher ! PoisonPill
    }
  }

  def receive: Receive = {
    case ChangedContents(contents) =>
      Try { ConfigFactory.parseString(contents) } match {
        case Success(domainsConfig) =>
          log.debug("new parsed domains config")
          val foundDomains = domainsConfig.getConfigList("domains").map(c => c.getString("domain-name") -> new DomainSettings(c, context.system)).toMap
          val newDomains = foundDomains.keySet.diff(domains.keySet)
          for (d <- newDomains) {
            configWatchers += d -> context.actorOf(ConfigWatcher.props(foundDomains(d), context.parent), "config-watcher-" + d)
          }
          val removedDomains = domains.keySet.diff(foundDomains.keySet)
          for (d <- removedDomains) {
            if (configWatchers.contains(d)){
              teardownConfigWatcher(configWatchers(d))
              configWatchers -= d
            }
          }
          domains = foundDomains

          context.parent ! ShieldActorMsgs.DomainsUpdated(foundDomains)
        case Failure(e) => log.warning(s"Error encountered while parsing domain conf: $e")
    }
  }
}

