package shield.config

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.pattern.CircuitBreaker
import com.typesafe.config.Config
import redis.RedisClient
import shield.actors.config.listener.ListenerBuilder
import shield.actors.config.middleware.MiddlewareBuilder
import shield.actors.config.upstream.UpstreamWatcher
import shield.kvstore.{BreakerStore, KVStore, MemoryStore, RedisStore}
import spray.http.Uri

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration


class DomainSettings(val domainConfig: Config, _system: ActorSystem) {
  val DomainName = domainConfig.getString("domain-name")

  val MiddlewareChain = domainConfig.getConfigList("middleware-chain").map { c =>
    MiddlewareConfig(
      c.getString("id").toLowerCase(),
      FiniteDuration(c.getDuration("sla", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS),
      Settings.getClass(c.getString("builder"), "shield.actors.config.middleware", classOf[MiddlewareBuilder])
    )
  }.toList
  require(MiddlewareChain.groupBy(_.id).forall(_._2.size == 1), "Middleware Ids must be unique")

  val UpstreamWatcher = Settings.getClass(domainConfig.getString("upstream-watcher"), "shield.actors.config.upstream", classOf[UpstreamWatcher])
  val WeightWatcherStepCount = domainConfig.getInt("upstream-weighting.step-count")
  val WeightWatcherStepDuration = FiniteDuration(domainConfig.getDuration("upstream-weighting.step-duration", TimeUnit.MILLISECONDS), duration.MILLISECONDS)

  val Listeners = domainConfig.getConfigList("listeners").map { c =>
    ListenerConfig(
      c.getString("id"),
      Settings.getClass(c.getString("builder"), "shield.actors.config.listener", classOf[ListenerBuilder])
    )
  }.toList

  def ConfigForMiddleware(id: String) = domainConfig.getConfig(s"middleware.$id")
  def ConfigForListener(id: String) = domainConfig.getConfig(s"listener-config.$id")

  private val logConfig = domainConfig.getConfig("log")

  val loggedRequestHeaders = logConfig.getStringList("request-headers").asScala.map(t => t.toLowerCase).toSet
  val loggedResponseHeaders = logConfig.getStringList("response-headers").asScala.map(t => t.toLowerCase).toSet

  private val storeIds = domainConfig.getObject("kvstores").keys
  private def parseStoreConfig(c: Config) : KVStoreConfig = {
    c.getString("type") match {
      case "breaker" => BreakerStoreConfig(c.getString("backer"), c.getInt("maxFailures"), FiniteDuration(c.getDuration("callTimeout", TimeUnit.MILLISECONDS), duration.MILLISECONDS), FiniteDuration(c.getDuration("resetTimeout", TimeUnit.MILLISECONDS), duration.MILLISECONDS))
      case "memory" => MemoryStoreConfig(c.getInt("maxHashCapacity"), c.getInt("maxKeyCapacity"), c.getInt("maxLimitCapacity"))
      case "redis" => RedisStoreConfig(c.getString("url"))
    }
  }
  private val storeConfigs = storeIds.map(id => (id, parseStoreConfig(domainConfig.getConfig(s"kvstores.$id")))).toMap
  private val buildingCache = mutable.Map[String, KVStore]()
  private def buildStore(id: String) : KVStore = {
    // todo: prevent circular references
    buildingCache.getOrElseUpdate(id, storeConfigs(id) match {
      case RedisStoreConfig(url) =>
        val uri = Uri(url)
        new RedisStore(id, new RedisClient(uri.authority.host.address, uri.authority.port)(_system))(_system.dispatcher)
      case cfg: MemoryStoreConfig => new MemoryStore(id, cfg.hashCapacity, cfg.keyCapacity, cfg.limitCapacity)(_system.dispatcher)
      case cfg: BreakerStoreConfig => new BreakerStore(id, buildStore(cfg.backer), new CircuitBreaker(_system.scheduler, maxFailures=cfg.maxFailures, callTimeout=cfg.callTimeout, resetTimeout=cfg.resetTimeout)(_system.dispatcher))
    })
  }

  val KVStores = storeIds.map(id => (id, buildStore(id))).toMap

  import shield.implicits.StringImplicits._
  val IgnoreExtensions = domainConfig.getStringList("ignoreExtensions").asScala.map(t => t.mustStartWith(".").toLowerCase).toSet
}
