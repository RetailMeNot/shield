package shield

import akka.actor.ActorSystem
import shield.actors.ShieldActor

object Boot extends App {

  // we need an ActorSystem to host our application in
  implicit val system = ActorSystem("on-spray-can")

  // todo: sweep for unhandled/unlogged future failures
  system.actorOf(ShieldActor.props(), "shield")
}
