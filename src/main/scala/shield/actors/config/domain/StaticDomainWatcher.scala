package shield.actors.config.domain

import akka.actor.{ActorLogging, ActorRef}
import shield.actors.config.ConfigWatcher
import shield.actors.{RestartLogging, ShieldActorMsgs}
import shield.config.DomainSettings
import shield.metrics.Instrumented

import scala.collection.JavaConversions._


class StaticDomainWatcher extends DomainWatcher with ActorLogging with RestartLogging with Instrumented {
  val domains = settings.config.getConfigList("shield.domains").map(c => c.getString("domain-name") -> new DomainSettings(c, context.system)).toMap

  var configWatchers = Map[String, ActorRef]()
  for ((hostname, domain) <- domains) {
    configWatchers += hostname -> context.actorOf(ConfigWatcher.props(domain, context.parent), "config-watcher-" + hostname)
  }

  context.parent ! ShieldActorMsgs.DomainsUpdated(domains)

  def receive = {
    case _ =>
  }
}
