package shield.actors.config.domain

import akka.actor.{Actor, ActorRef}
import shield.config.{DomainSettings, Settings}


trait DomainWatcher extends Actor {
  val settings = Settings(context.system)
}
