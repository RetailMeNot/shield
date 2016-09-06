package shield.routing

import shield.config.ServiceLocation
import shield.proxying.EmptyBalancerBuilder
import spray.http._
import spray.json._

object DomainInfo extends DefaultJsonProtocol {
  implicit val domainInfoFormat = jsonFormat1(DomainInfo.apply)
}

case class DomainInfo(domains: Set[String])

object BootstrapRouter {
  def router(localService: ServiceLocation, localServiceName: String, healthcheckTemplate: EndpointTemplate) : RequestRouter = new RoutingTable(
    List(
      RoutableEndpoint(
        healthcheckTemplate,
        EndpointDetails.empty,
        Left((_ : HttpRequest) => HttpResponse(StatusCodes.ServiceUnavailable))
      )
    ),
    localService,
    localServiceName,
    EmptyBalancerBuilder
  )

  def defaultRouter(localService: ServiceLocation, localServiceName: String, healthcheckTemplate: EndpointTemplate, domains: Set[String]) : RequestRouter = {
    import DefaultJsonProtocol._

    new RoutingTable(
      List(
        RoutableEndpoint(
          healthcheckTemplate,
          EndpointDetails.empty,
          Left((_: HttpRequest) => HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/json`, JsObject("domains" -> domains.toJson).prettyPrint)))
        )
      ),
      localService,
      localServiceName,
      EmptyBalancerBuilder
    )
  }
}
