package shield.actors.config.listener

import akka.actor.{Actor, ActorLogging, Props}
import shield.actors.RestartLogging
import shield.actors.config.ConfigWatcherMsgs
import shield.actors.listeners.{ConsoleLogger, LogCollector}
import shield.config.DomainSettings

class ConsoleLogBuilder(id: String, domain: DomainSettings) extends Actor with ActorLogging with ListenerBuilder with RestartLogging {

  val c = domain.ConfigForListener(id)
  val forwarder = context.actorOf(Props(new ConsoleLogger(id)))
  val collector = context.actorOf(Props(new LogCollector(id, domain, List(forwarder), 1)))

  context.parent ! ConfigWatcherMsgs.ListenerUpdated(id, collector)

  log.info(s"Built console logger $id")

  def receive = {
    case _ =>
  }
}
