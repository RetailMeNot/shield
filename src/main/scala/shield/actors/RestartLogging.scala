package shield.actors

import akka.actor.{Actor, ActorLogging}

trait RestartLogging extends Actor with ActorLogging {
  abstract override def preRestart(reason: Throwable, message: Option[Any]) {
    log.error(reason, "Restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }
}
