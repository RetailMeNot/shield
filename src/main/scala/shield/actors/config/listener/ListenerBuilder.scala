package shield.actors.config.listener

import akka.actor.Actor
import shield.config.Settings

trait ListenerBuilder extends Actor {
  val settings = Settings(context.system)
}

