package shield.actors.config.listener

import akka.actor.{Actor, ActorLogging, Props}
import shield.actors.RestartLogging
import shield.actors.config.ConfigWatcherMsgs
import shield.actors.listeners.{KibanaForwarder, LogCollector}
import shield.aws.AWSSigningConfig
import shield.config.DomainSettings

class KibanaBuilder(id: String, domain: DomainSettings) extends Actor with ActorLogging with ListenerBuilder with RestartLogging {

  val c = domain.ConfigForListener(id)
  val forwarder = context.actorOf(Props(new KibanaForwarder(id, c.getString("host"), c.getString("index-prefix"), c.getString("type"), c.getInt("max-outstanding"), AWSSigningConfig(c))))
  val collector = context.actorOf(Props(new LogCollector(id, domain, List(forwarder), c.getInt("buffer-size"))))

  context.parent ! ConfigWatcherMsgs.ListenerUpdated(id, collector)

  log.info(s"Built kibana listener $id")

  def receive = {
    case _ =>
  }
}
