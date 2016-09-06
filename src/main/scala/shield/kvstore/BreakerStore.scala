package shield.kvstore

import akka.pattern.CircuitBreaker
import com.typesafe.scalalogging.LazyLogging
import spray.http.HttpResponse

import scala.concurrent.Future

class BreakerStore(id: String, backer: KVStore, breaker: CircuitBreaker) extends KVStore with LazyLogging {
  val cb = breaker
    .onOpen(logger.warn(s"$id breaker opened"))
    .onHalfOpen(logger.warn(s"$id breaker half opened"))
    .onClose(logger.warn(s"$id breaker closed"))

  override def setGet(key: String): Future[Seq[String]] = cb.withCircuitBreaker(backer.setGet(key))

  override def keyGet(key: String): Future[Option[HttpResponse]] = cb.withCircuitBreaker(backer.keyGet(key))

  override def keyDelete(key: String): Future[Long] = cb.withCircuitBreaker(backer.keyDelete(key))

  override def setDelete(key: String): Future[Long] = cb.withCircuitBreaker(backer.setDelete(key))

  override def tokenRateLimit(key: String, rate: Int, perSeconds: Int): Future[Boolean] = cb.withCircuitBreaker(backer.tokenRateLimit(key, rate, perSeconds))

  override def setRemove(key: String, value: String): Future[Long] = cb.withCircuitBreaker(backer.setRemove(key, value))

  override def setAdd(key: String, value: String): Future[Long] = cb.withCircuitBreaker(backer.setAdd(key, value))

  override def keySet(key: String, value: HttpResponse): Future[Boolean] = cb.withCircuitBreaker(backer.keySet(key, value))
}
