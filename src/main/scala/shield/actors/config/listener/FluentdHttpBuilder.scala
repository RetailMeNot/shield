package shield.actors.config.listener

import akka.actor.{Actor, ActorLogging, Props}
import shield.actors.RestartLogging
import shield.actors.config.ConfigWatcherMsgs
import shield.actors.listeners.{FluentdHttpForwarder, LogCollector}
import shield.config.DomainSettings

class FluentdHttpBuilder(id: String, domain: DomainSettings) extends Actor with ActorLogging with ListenerBuilder with RestartLogging {

  val c = domain.ConfigForListener(id)
  val forwarder = context.actorOf(Props(new FluentdHttpForwarder(id, c.getString("host"), c.getInt("max-outstanding"))))
  val collector = context.actorOf(Props(new LogCollector(id, domain, List(forwarder), c.getInt("buffer-size"))))

  context.parent ! ConfigWatcherMsgs.ListenerUpdated(id, collector)

  log.info(s"Built FluentD listener $id")

  def receive = {
    case _ =>
  }
}
