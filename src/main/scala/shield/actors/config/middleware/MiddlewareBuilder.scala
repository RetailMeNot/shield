package shield.actors.config.middleware

import akka.actor.Actor
import shield.config.Settings

trait MiddlewareBuilder extends Actor {
  val settings = Settings(context.system)
}

