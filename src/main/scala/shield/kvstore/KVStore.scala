package shield.kvstore

import spray.http.{MediaType, HttpResponse}

import scala.concurrent.Future

trait KVStore {
  def setGet(key: String) : Future[Seq[String]]
  def setDelete(key: String) : Future[Long]
  def setAdd(key: String, value: String) : Future[Long]
  def setRemove(key: String, value: String) : Future[Long]
  def keyGet(key: String) : Future[Option[HttpResponse]]
  def keySet(key: String, value: HttpResponse) : Future[Boolean]
  def keyDelete(key: String) : Future[Long]
  def tokenRateLimit(key: String, rate: Int, perSeconds: Int) : Future[Boolean]
}
