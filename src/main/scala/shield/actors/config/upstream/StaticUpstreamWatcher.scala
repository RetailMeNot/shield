package shield.actors.config.upstream

import akka.actor.{Actor, ActorLogging, Props}
import com.typesafe.config.Config
import shield.actors.RestartLogging
import shield.actors.config.{ServiceDetails, UpstreamAggregatorMsgs}
import shield.config.ServiceLocation

import scala.collection.JavaConversions._
import scala.util.Try

object StaticUpstreamWatcher {
  def props(domainConfig: Config): Props = Props(new StaticUpstreamWatcher(domainConfig))
}

class StaticUpstreamWatcher(domainConfig: Config) extends Actor with ActorLogging with UpstreamWatcher with RestartLogging with UpstreamParser{
  val rawServices = if (domainConfig.hasPath("upstreams"))
    domainConfig.getConfigList("upstreams").map(c => Try {parseUpstreamEntry(c.getString("serviceType"), c.getString("serviceLocation"), if (c.hasPath("weight")) c.getInt("weight") else 1) } ).toList
  else List[Try[(ServiceLocation, ServiceDetails)]]()

  for (attempt <- rawServices.filter(_.isFailure)) {
    log.warning(s"Bad upstream host in the config (${attempt.failed.get.getMessage})")
  }

  context.parent ! UpstreamAggregatorMsgs.DiscoveredUpstreams(rawServices.flatMap(_.toOption).toMap)

  def receive = {
    case _ =>
  }
}
