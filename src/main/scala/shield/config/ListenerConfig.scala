package shield.config

import shield.actors.config.listener.ListenerBuilder

case class ListenerConfig(id: String, builder: Class[_ <: ListenerBuilder])

