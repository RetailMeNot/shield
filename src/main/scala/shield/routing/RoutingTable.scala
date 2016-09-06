package shield.routing

import akka.actor.ActorRef
import com.typesafe.scalalogging.LazyLogging
import shield.actors.{Middleware, ResponseDetails}
import shield.config.ServiceLocation
import shield.proxying.{ProxyBalancer, ProxyBalancerBuilder}
import spray.http._

import scala.util.Try
import scala.util.matching.Regex


case class RoutingDestination(template: EndpointTemplate, possibleDetails: List[EndpointDetails], allMiddleware: List[Middleware], upstreamBalancer: ProxyBalancer) {
  // optimization to de-dupe possible cache keys ahead of time
  val uniqueParamSets : List[Set[Param]] = possibleDetails.map(_.params).distinct
  // nb: this means that if any one of the upstream endpoints disables the middleware, it will be disabled for all of them
  private val disabledMiddleware : Set[String] = possibleDetails.flatMap(_.disabledMiddleware.map(_.toLowerCase)).toSet

  val activeMiddleware = allMiddleware.filterNot(m => disabledMiddleware(m.name.toLowerCase))
  val disabledListeners = possibleDetails.flatMap(_.disabledListeners.map(_.toLowerCase)).toSet
}

case class RoutableEndpoint(template: EndpointTemplate, details: EndpointDetails, handler: Either[HttpRequest => HttpResponse, ActorRef])

trait RequestRouter extends LazyLogging {
  def route(request: HttpRequest) : Either[ResponseDetails, RoutingDestination]
}

object SimpleRouter {
  def apply(routes: Iterable[RoutableEndpoint], template: EndpointTemplate, localService: ServiceLocation, localServiceName: String, builder: ProxyBalancerBuilder[_]) : RequestRouter = {
    val endpoints = routes.map(_.template).toSet
    require(endpoints.size == 1 && endpoints.head == template)

    // if any of these provide an immediate response, use that instead
    val (fastEndpoints, remoteEndpoints) = routes.partition(_.handler.isLeft)

    fastEndpoints.headOption
      .map(fastEndpoint => new FastRouter(template, localService, localServiceName, fastEndpoint.handler.left.get))
      .getOrElse(new StaticRouter(Right(RoutingDestination(
        template,
        remoteEndpoints.map(_.details).toList,
        builder.allMiddleware,
        builder.build(
          remoteEndpoints.map(_.handler.right.get).toSet
        )
      ))))
  }
}
class FastRouter(template: EndpointTemplate, localService: ServiceLocation, localServiceName: String, callback: HttpRequest => HttpResponse) extends RequestRouter {
  def route(request: HttpRequest): Either[ResponseDetails, RoutingDestination] = {
    val response = Try {
      callback(request)
    }.recover {
      // todo: standardized error object
      case e => HttpResponse(StatusCodes.InternalServerError, HttpEntity(e.getMessage))
    }
    Left(ResponseDetails(localService, localServiceName, template, None, response.get))
  }
}
class StaticRouter(destination: Either[ResponseDetails, RoutingDestination]) extends RequestRouter {
  def route(request: HttpRequest): Either[ResponseDetails, RoutingDestination] = destination
}

class ContentNegotiationRouter(val routes: Iterable[RoutableEndpoint], template: EndpointTemplate, localService: ServiceLocation, localServiceName: String, builder: ProxyBalancerBuilder[_]) extends RequestRouter {
  private val allContentTypes = routes.flatMap(r => r.details.canProduce).map(ContentType(_)).toSet.toSeq
  private val allMediaTypes = routes.flatMap(r => r.details.canProduce).toSet.toSeq

  // if an endpoint doesn't specify the content types it produces, assume it's capable of producing anything
  private val (unspecified, specified) = routes.partition(_.details.canProduce.isEmpty)
  private val routerByMediaType : Map[MediaType, RequestRouter] = specified
      .flatMap(e => e.details.canProduce.map(mt => (mt, e)))
      .groupBy(_._1)
      .map { case (mediaType, values) => mediaType -> SimpleRouter(values.map(_._2) ++ unspecified, template, localService, localServiceName, builder) }

  private val defaultRouter = if (unspecified.nonEmpty) {
    Some(SimpleRouter(unspecified, template, localService, localServiceName, builder))
  } else {
    None
  }

  private val allRoutes = SimpleRouter(routes, template, localService, localServiceName, builder)

  def route(request: HttpRequest) : Either[ResponseDetails, RoutingDestination] = {
    // http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html  ss 14.1
    // "If no Accept header field is present, then it is assumed that the client accepts all media types."
    if (request.header[HttpHeaders.Accept].isEmpty) {
      allRoutes.route(request)
    } else {
      val preferredMedia = request.acceptableContentType(allContentTypes).map(_.mediaType)
      preferredMedia.flatMap(routerByMediaType.get)
        .orElse(defaultRouter)
        .map(_.route(request))
        .getOrElse(Left(ResponseDetails(localService, localServiceName, template, None, HttpResponse(StatusCodes.NotAcceptable))))
    }
  }
}

/**
 * Filter handlers by checking if they can consume the body of the request
 */
class ConsumeRouter(routes: Iterable[RoutableEndpoint], template: EndpointTemplate, localService: ServiceLocation, localServiceName: String, builder: ProxyBalancerBuilder[_]) extends RequestRouter {

  // if an endpoint doesn't specify the content types they consume, assume they can consume anything
  // endpoints that don't specify the content types they consume are also the only ones that can consume a request w/o content-type
  private val (unspecified, specified) = routes.partition(_.details.canConsume.isEmpty)
  private val routerByContentType : Map[ContentType, RequestRouter] =  specified
      .flatMap(e => e.details.canConsume.map(mt => (ContentType(mt), e)))
      .groupBy(_._1)
      .map { case (contentType, kvps) => contentType -> new ContentNegotiationRouter(kvps.map(_._2) ++ unspecified, template, localService, localServiceName, builder) }

  private val defaultRouter = if (unspecified.nonEmpty) {
    Some(new ContentNegotiationRouter(unspecified, template, localService, localServiceName, builder))
  } else {
    None
  }

  def route(request: HttpRequest) : Either[ResponseDetails, RoutingDestination] = {
    val requestContentType = request.entity.toOption.map(_.contentType)

    requestContentType.flatMap(routerByContentType.get)
      .orElse(defaultRouter)
      .map(_.route(request))
      .getOrElse(Left(ResponseDetails(localService, localServiceName, template, None, HttpResponse(StatusCodes.UnsupportedMediaType))))
  }
}


class DefaultOptionMethod(path: Path, localService: ServiceLocation, localServiceName: String, allowed: HttpHeaders.Allow) extends RequestRouter {
  def route(request: HttpRequest) : Either[ResponseDetails, RoutingDestination] = {
    Left(ResponseDetails(localService, localServiceName, EndpointTemplate(HttpMethods.OPTIONS, path), None, HttpResponse(StatusCodes.OK, headers=List(allowed))))
  }
}

class MethodRouter(routes: Iterable[RoutableEndpoint], path: Path, localService: ServiceLocation, localServiceName: String, builder: ProxyBalancerBuilder[_]) extends RequestRouter {
  private val rawGroups : Map[HttpMethod, RequestRouter] = routes.groupBy(_.template.method).map(kvp => kvp._1 -> new ConsumeRouter(kvp._2, EndpointTemplate(kvp._1, path), localService, localServiceName, builder))
  private val groups = if (rawGroups.contains(HttpMethods.OPTIONS)) {
    rawGroups
  } else {
    rawGroups + (HttpMethods.OPTIONS -> new DefaultOptionMethod(path, localService, localServiceName, HttpHeaders.Allow(rawGroups.keys.toSeq: _*)))
  }

  def route(request: HttpRequest) : Either[ResponseDetails, RoutingDestination] = {
    groups.get(request.method)
      .map(_.route(request))
      .getOrElse(Left(ResponseDetails(localService, localServiceName, EndpointTemplate(request.method, path), None, HttpResponse(StatusCodes.MethodNotAllowed, headers = List(HttpHeaders.Allow(rawGroups.keys.toSeq: _*))))))
  }
}

class RoutingTable(routes: Iterable[RoutableEndpoint], localService: ServiceLocation, localServiceName: String, builder: ProxyBalancerBuilder[_]) extends RequestRouter {
  // sort by path specificity, so that the router prefers specific paths rather than catch-all handlers
  private val byPath = routes.groupBy(_.template.path).toList.sortBy(_._1)
  private val paths = byPath.map(kvp => (kvp._1.regex, new MethodRouter(kvp._2, kvp._1, localService, localServiceName, builder)))

  def matches(requestPath: String)(kvp: (Regex, MethodRouter)) = kvp._1.findFirstIn(requestPath).isDefined

  def route(request: HttpRequest) : Either[ResponseDetails, RoutingDestination] = {
    // todo: profiling optimization - can we do something with tries or the already parsed path?
    paths.find(matches(request.uri.path.toString().stripSuffix("/")))
      .map(_._2.route(request))
      .getOrElse(Left(ResponseDetails(localService, localServiceName, EndpointTemplate.pseudo(request), None, HttpResponse(StatusCodes.NotFound))))
  }
}
