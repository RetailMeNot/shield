package shield.actors.config.upstream

import akka.actor.Actor
import shield.config.Settings

trait UpstreamWatcher { self: Actor =>
  val settings = Settings(context.system)
}

