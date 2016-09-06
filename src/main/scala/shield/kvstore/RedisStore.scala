package shield.kvstore


import redis.{ByteStringFormatter, RedisClient}
import shield.implicits.FutureUtil
import shield.kvstore.protobuf.HttpResponseProtos.ProtoResponse
import shield.kvstore.protobuf.HttpResponseProtos.ProtoResponse.ProtoHeader
import shield.metrics.Instrumented
import spray.http.HttpHeaders.RawHeader
import spray.http.parser.HttpParser
import spray.httpx.encoding.Gzip
import akka.util.{Timeout, ByteString}
import spray.http._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import collection.JavaConversions._

object ProtobufResponseHelper {
  def apply(response: HttpResponse) : ProtoResponse = {
    val iterHeaders = response.headers.map(ProtobufHeaderHelper.build)
    ProtoResponse.newBuilder()
      .setStatus(response.status.intValue)
      .setData(com.google.protobuf.ByteString.copyFrom(response.entity.data.toByteArray))
      .addAllHeaders(iterHeaders)
      .build()
  }
}

object ProtobufHeaderHelper {
  def build(header: HttpHeader) : ProtoHeader = {
    ProtoHeader.newBuilder()
      .setName(header.lowercaseName)
      .setValue(header.value)
      .build()
  }
}

class RedisStore(id: String, client: RedisClient)(implicit context: ExecutionContext) extends KVStore with Instrumented {

  val deserializeTimer = metrics.timer("deserialize", id)
  val encodeTimer = metrics.timer("encode", id)
  val serializeTimer = metrics.timer("serialize", id)
  val serializedSize = metrics.histogram("serialized-size", id)

  implicit val timeout = Timeout(5.seconds)
  implicit val responseFormatter = new ByteStringFormatter[HttpResponse] {
    override def deserialize(bs: ByteString): HttpResponse = {
      deserializeTimer.time {
        // todo: single pass over headers to convert to rawheader and grab content type
        // Protobuf
        val msg = ProtoResponse.parseFrom(bs.toArray)
        val scalaHeaders: List[ProtoHeader] = msg.getHeadersList.toList
        // todo: profiling optimization: parsing these headers is more expensive than deserializing the protobuf
        val contentType = scalaHeaders.find(_.getName == "content-type").map(ct => HttpParser.parse(HttpParser.ContentTypeHeaderValue, ct.getValue).right.get).getOrElse(ContentTypes.`application/octet-stream`)
        // even if it's technically http-1.0, we'd just scrub it later
        HttpResponse(msg.getStatus, HttpEntity(contentType, msg.getData.toByteArray), HttpParser.parseHeaders(scalaHeaders.map(h => RawHeader(h.getName, h.getValue)))._2, HttpProtocols.`HTTP/1.1`)
      }
    }
    override def serialize(rawResponse: HttpResponse): ByteString = {
      val response = encodeTimer.time(Gzip.encode(rawResponse))
      serializeTimer.time {
        // Protobuf
        val msg = ProtobufResponseHelper(response)
        val b = msg.toByteArray

        serializedSize += b.length
        ByteString(b)
      }
    }
  }

  val setGetTimer = timing("setGet", id)
  def setGet(key: String) : Future[Seq[String]] = setGetTimer { client.smembers[String](key) }
  val setDeleteTimer = timing("setDelete", id)
  def setDelete(key: String) : Future[Long] = setDeleteTimer { client.del(key) }
  val setAddTimer = timing("setAdd", id)
  def setAdd(key: String, value: String) : Future[Long] = setAddTimer { client.sadd(key, value) }
  val setRemoveTimer = timing("setRemove", id)
  def setRemove(key: String, value: String) : Future[Long] = setRemoveTimer { client.srem(key, value) }
  val keyGetTimer = timing("keyGet", id)
  def keyGet(key: String) : Future[Option[HttpResponse]] = keyGetTimer { client.get[HttpResponse](key) }
  val keySetTimer = timing("keySet", id)
  def keySet(key: String, value: HttpResponse) : Future[Boolean] = keySetTimer { client.set(key, value) }
  val keyDeleteTimer = timing("keyDelete", id)
  def keyDelete(key: String) : Future[Long] = keyDeleteTimer { client.del(key) }

  val tokenTimer = timing("tokenRateLimit", id)
  def tokenRateLimit(key: String, rate: Int, perSeconds: Int) : Future[Boolean] = tokenTimer {
    val floored = Math.floor(System.currentTimeMillis() / (perSeconds * 1000)).toLong
    val fullKey = s"rl:$floored:$key"

    // yes, we're refreshing the expire on the bucket multiple times, but once System.currentTimeMillis
    // advances enough to increment floored, we'll stop refreshing the expire on this key
    client.expire(fullKey, perSeconds).andThen(FutureUtil.logFailure("RedisStore::tokenRateLimit-expire"))

    client.incr(fullKey).map(_ <= rate)
  }
}
