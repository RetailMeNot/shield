package shield.routing

import spray.http.HttpRequest

sealed trait ParamLocation {
  val key: String

  def getValues(name: String, request: HttpRequest) : List[String]
  def apply(name: String) = Param(this, name)
}
object BodyParam extends ParamLocation {
  val key = "b"

  def getValues(name: String, request: HttpRequest) = Nil //todo
}
object FormParam extends ParamLocation {
  val key = "f"

  def getValues(name: String, request: HttpRequest) = Nil //todo
}
object HeaderParam extends ParamLocation {
  val key = "h"

  def getValues(name: String, request: HttpRequest) = request.headers.filter(_.lowercaseName == name).map(_.value)
}
object PathParam extends ParamLocation {
  val key = "p"

  def getValues(name: String, request: HttpRequest) = Nil // noop - already captured by the full path in the cache key
}
object QueryParam extends ParamLocation {
  val key = "q"

  def getValues(name: String, request: HttpRequest) = request.uri.query.getAll(name)
}

case class Param(location: ParamLocation, name: String) {
  def getValues(request: HttpRequest) = location.getValues(name, request)
}
