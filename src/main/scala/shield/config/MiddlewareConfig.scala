package shield.config

import shield.actors.config.middleware.MiddlewareBuilder

import scala.concurrent.duration.FiniteDuration

case class MiddlewareConfig(id: String, sla: FiniteDuration, builder: Class[_ <: MiddlewareBuilder])

