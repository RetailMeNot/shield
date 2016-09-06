package shield.actors.listeners

import akka.actor._
import shield.actors._
import shield.actors.config.UpstreamAggregatorMsgs.StateUpdated
import shield.actors.config.{ServiceDetails, UpstreamAggregator, WeightedProxyState}
import shield.config._
import shield.implicits.FutureUtil
import shield.metrics.Instrumented
import shield.proxying._
import shield.routing._
import spray.http.Rendering._
import spray.http._
import spray.httpx.encoding.Gzip
import spray.json.JsonParser
import spray.util._

import scala.util.Try

case class ComparisonDiffFile(fileName: String, contents: Array[Byte])
object AlternateUpstream{
  def props(id: String, localServiceLocation: ServiceLocation, hostUri: String, hostType: String, freq: Int, uploader: ActorRef) : Props = Props(new AlternateUpstream(id, localServiceLocation, hostUri, hostType, freq, uploader))
}
class AlternateUpstream(id: String, localServiceLocation: ServiceLocation, hostUri: String, hostType: String, freq: Int, uploader: ActorRef) extends Actor with ActorLogging with RestartLogging with Instrumented {
  implicit val ctx = context.dispatcher

  val settings = Settings(context.system)

  val totalRoutableRequestsCounter = metrics.counter("totalRequests", id)
  val totalAlternateRequestCounter = metrics.counter("alternateRequests", id)
  val totalDiffCounter = metrics.counter("totalDiff",id)

  def shouldGoUpstream(): Boolean = {
    totalRoutableRequestsCounter += 1
    if (totalRoutableRequestsCounter.count % freq == 0) {
      totalAlternateRequestCounter += 1
      true
    } else {
      false
    }
  }

  var balancerBuilder : ProxyBalancerBuilder[_] = EmptyBalancerBuilder
  var routingTable = BootstrapRouter.router(localServiceLocation, settings.LocalServiceName, settings.HealthcheckTemplate)

  // Keep track of alternate upstream
  val upstreamLocation = HttpServiceLocation(Uri(hostUri))
  val upstreamDetails = ServiceDetails(ServiceType.lookup(hostType), 1)
  var upstreamName = "(unknown)"

  def startingState() : WeightedProxyState = UpstreamAggregator.defaultState(
    upstreamDetails,
    context.actorOf(HostProxy.props(upstreamLocation, upstreamDetails.serviceType, localServiceLocation, TransportFactory(upstreamLocation)))
  )

  buildRouter(startingState())

  def receive = {
    case r: RequestProcessorCompleted if r.completion.details.serviceName != settings.LocalServiceName =>
      val req = r.completion.request
      routingTable.route(req) match {
        case Right(dest: RoutingDestination) if shouldGoUpstream() =>
          dest.upstreamBalancer.proxy(dest.template, req).map(handleResults(r)).andThen(FutureUtil.logFailure("AlternateUpstream::handle"))

        case _ =>
      }

    case StateUpdated(location, state) if location == upstreamLocation =>
      buildRouter(WeightedProxyState(1, state))
  }

  protected def buildRouter(weighted: WeightedProxyState) = {
    upstreamName = weighted.state.service
    val remoteEndpoints = weighted.state.allEndpoints.map(kvp => RoutableEndpoint(kvp._1, kvp._2, Right(weighted.state.proxy)))
    balancerBuilder.teardown()
    balancerBuilder = new RoundRobinBalancerBuilder(List[Middleware](), context, Map(weighted.state.proxy -> weighted))
    routingTable = new RoutingTable(remoteEndpoints, localServiceLocation, settings.LocalServiceName, balancerBuilder)
  }

  def handleResults(completeRequest: RequestProcessorCompleted)(r: ProxiedResponse) : Unit = {
    import shield.implicits.HttpImplicits._
    val request = completeRequest.completion.request
    //todo RequestProcessorCompleted should include original requests/responses as well as the changed request/responses
    val altResponse = Gzip.decode(HttpProxyLogic.scrubResponse(r.response))
    val mainResponse = Gzip.decode(completeRequest.completion.details.response).withStrippedHeaders(Set("X-Cache"))

    val tryJsonDiff : Option[Boolean] = (mainResponse.entity, altResponse.entity) match {
      case (HttpEntity.NonEmpty(ContentType(MediaTypes.`application/json`, _), mainData),
            HttpEntity.NonEmpty(ContentType(MediaTypes.`application/json`, _), altData)) =>
        Try { JsonParser(mainData.toByteArray) != JsonParser(altData.toByteArray) }.toOption
      case (_, _) => None
    }

    val bodyDiff = tryJsonDiff.getOrElse(mainResponse.entity != altResponse.entity)

    if (mainResponse.headers.toSet != altResponse.headers.toSet || bodyDiff || mainResponse.status != altResponse.status) {
      totalDiffCounter += 1

      val rendering = new ByteArrayRendering((altResponse.entity.data.length + mainResponse.entity.data.length).toInt)
      renderRequest(rendering, request)
      rendering ~~ CrLf ~~ CrLf
      rendering ~~ s"_${localServiceLocation.locationName}"
      rendering ~~ CrLf ~~ CrLf
      renderResponse(rendering, mainResponse)
      rendering ~~ CrLf ~~ CrLf
      renderResponse(rendering, altResponse)

      uploader ! ComparisonDiffFile(s"${upstreamName}_${totalDiffCounter.count}_${System.currentTimeMillis()}", rendering.get)
    }
  }

  def renderRequest(r: Rendering, request: HttpRequest) : Unit = {
    def render(h: HttpHeader) = r ~~ h ~~ CrLf

    r ~~ request.method ~~ ' '
    request.uri.renderWithoutFragment(r, UTF8)
    r ~~ ' ' ~~ request.protocol ~~ CrLf
    request.headers.foreach(render)
    r ~~ CrLf ~~ request.entity.data
  }

  def renderResponse(r: Rendering, response: HttpResponse): Unit = {
    def render(h: HttpHeader) = r ~~ h ~~ CrLf

    r ~~ "HTTP/1.1 " ~~ response.status ~~ CrLf
    response.headers.foreach(render)
    r ~~ CrLf ~~ response.entity.data
  }
}

