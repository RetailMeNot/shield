package shield.routing

import spray.http.{HttpMethod, HttpRequest, MediaType}

object EndpointTemplate {
  def pseudo(request: HttpRequest) : EndpointTemplate = {
    EndpointTemplate(request.method, Path(request.uri.path.toString()))
  }
}
case class EndpointTemplate(method: HttpMethod, path: Path)

object EndpointDetails {
  val empty = EndpointDetails(Set.empty, Set.empty, Set.empty, Set.empty, Set.empty)
}
case class EndpointDetails(params: Set[Param], canConsume: Set[MediaType], canProduce: Set[MediaType], disabledMiddleware: Set[String], disabledListeners: Set[String])
