package shield.consul

import shield.implicits.StringImplicits
import StringImplicits._
import spray.http.{HttpResponse, HttpEntity}
import spray.httpx.unmarshalling.{Deserialized, MalformedContent, FromResponseUnmarshaller, Unmarshaller}

object ConsulVersionedResponse {
  implicit def kvpUnmarshaller(implicit nested: Unmarshaller[List[ConsulKVP]]) : FromResponseUnmarshaller[ConsulVersionedResponse[List[ConsulKVP]]] = new VersionedResponseDeserializer[List[ConsulKVP]](nested)
  implicit def serviceUnmarshaller(implicit nested: Unmarshaller[List[ConsulServiceNode]]) : FromResponseUnmarshaller[ConsulVersionedResponse[List[ConsulServiceNode]]] = new VersionedResponseDeserializer[List[ConsulServiceNode]](nested)
}

private class VersionedResponseDeserializer[T](nested: Unmarshaller[T]) extends FromResponseUnmarshaller[ConsulVersionedResponse[T]] {
  override def apply(response: HttpResponse) = {
    val parsedIndex : Deserialized[Int] = response.headers.find(_.lowercaseName == "x-consul-index").flatMap(_.value.toIntOpt).map(Right(_)).getOrElse(Left(MalformedContent("Could not decode x-consul-index header", None)))

    val parsedResponse = nested(response.entity)

    parsedIndex.right.flatMap(index => parsedResponse.right.map(body => ConsulVersionedResponse(index, body)))
  }
}

case class ConsulVersionedResponse[T](consulIndex: Int, body: T)
