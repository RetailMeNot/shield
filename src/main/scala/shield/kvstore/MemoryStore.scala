package shield.kvstore

import java.util.concurrent.atomic.AtomicInteger

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap
import com.typesafe.scalalogging.LazyLogging
import shield.metrics.Instrumented
import spray.http.{MediaType, HttpResponse}
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.concurrent

class LazyWrapper[A](builder: => A) {
  lazy val value : A = builder
}

class MemoryStore(id: String, maxHashCapacity: Int, maxKeyCapacity: Int, maxLimitCapacity: Int)(implicit context: ExecutionContext) extends KVStore with LazyLogging with Instrumented {
  def getMillis():Long = System.currentTimeMillis
  private val setStore = new ConcurrentLinkedHashMap.Builder[String, LazyWrapper[TrieSet[String]]]
    .initialCapacity(1000)
    .maximumWeightedCapacity(Math.max(1000, maxHashCapacity))
    .build()
  // todo: tweak capacity - can we do by memory size? (weigher to weigh by memory footprint)
  private val keyStore = new ConcurrentLinkedHashMap.Builder[String, HttpResponse]
    .initialCapacity(1000)
    .maximumWeightedCapacity(Math.max(1000, maxKeyCapacity))
    .build()
  private val limitStore = new ConcurrentLinkedHashMap.Builder[String, AtomicInteger]
    .initialCapacity(1000)
    .maximumWeightedCapacity(Math.max(1000, maxLimitCapacity))
    .build()

  // todo: profiling optimization - triesets are expensive to build.  Is there a better data structure we can use?
  private def getOrSet[V](set: ConcurrentLinkedHashMap[String, V], key: String, default: V) = set.putIfAbsent(key, default) match {
    case null => default
    case existing => existing
  }

  val setGetTimer = timing("setGet", id)
  def setGet(key: String) : Future[Seq[String]] = setGetTimer {
    Future.successful(getOrSet(setStore, key, new LazyWrapper[TrieSet[String]](TrieSet[String]())).value.toSeq)
  }
  val setDeleteTimer = timing("setDelete", id)
  def setDelete(key: String) : Future[Long] = setDeleteTimer {
    setStore.remove(key)
    // todo: implement these according to the same semantics as RedisStore
    Future.successful(0L)
  }
  val setAddTimer = timing("setAdd", id)
  def setAdd(key: String, value: String) : Future[Long] = setAddTimer {
    getOrSet(setStore, key, new LazyWrapper[TrieSet[String]](TrieSet[String]())).value += value
    Future.successful(0L)
  }
  val setRemoveTimer = timing("setRemove", id)
  def setRemove(key: String, value: String) : Future[Long] = setRemoveTimer {
    getOrSet(setStore, key, new LazyWrapper[TrieSet[String]](TrieSet[String]())).value -= value
    Future.successful(0L)
  }
  val keyGetTimer = timing("keyGet", id)
  def keyGet(key: String) : Future[Option[HttpResponse]] = keyGetTimer {
    Future.successful(Option(keyStore.get(key)))
  }
  val keySetTimer = timing("keySet", id)
  def keySet(key: String, value: HttpResponse) : Future[Boolean] = keySetTimer {
    keyStore.put(key, value)
    Future.successful(true)
  }
  val keyDeleteTimer = timing("keyDelete", id)
  def keyDelete(key: String) : Future[Long] = keyDeleteTimer {
    keyStore.remove(key)
    Future.successful(0L)
  }

  val tokenTimer = timing("tokenRateLimit", id)
  def tokenRateLimit(key: String, rate: Int, perSeconds: Int) : Future[Boolean] = tokenTimer {
    // we could set up a concurrent system for actively pruning expired entries or....
    // we could just let them get evicted via lru policy
    val floored = Math.floor(getMillis() / (perSeconds * 1000)).toLong
    val fullKey = s"rl:$floored:$key"
    val counter = getOrSet(limitStore, fullKey, new AtomicInteger(0))
    // doesn't matter if we increment over the count (ie count rate limited requests), since it won't spill
    // over to the next bucket
    Future.successful(counter.incrementAndGet() <= rate)
  }
}
