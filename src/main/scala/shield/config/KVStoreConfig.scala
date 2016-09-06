package shield.config

import scala.concurrent.duration.FiniteDuration

sealed trait KVStoreConfig

case class BreakerStoreConfig(backer: String, maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration) extends KVStoreConfig
case class MemoryStoreConfig(hashCapacity: Int, keyCapacity: Int, limitCapacity: Int) extends KVStoreConfig
case class RedisStoreConfig(url: String) extends KVStoreConfig
