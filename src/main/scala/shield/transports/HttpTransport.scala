package shield.transports

import akka.actor.{ActorRef, ActorRefFactory}
import akka.pattern.ask
import akka.io.IO
import akka.util.Timeout
import spray.can.Http
import spray.can.Http.HostConnectorSetup
import spray.can.client.HostConnectorSettings
import spray.http.Uri.Authority
import spray.http.{Uri, HttpResponsePart, HttpResponse, HttpRequest}
import spray.httpx.{ResponseTransformation, RequestBuilding}
import spray.util._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object HttpTransport extends RequestBuilding with ResponseTransformation {
  // Imitating client\pipelining.scala from spray, but taking advantage of the fact that
  // Spray's "Request-level API" lets you send a tuple of (Request, ConnectorSetup) to specify destination
  // without an absolute uri or a Host header
  type SendReceive = spray.client.pipelining.SendReceive

  def httpTransport(host: Uri, futureTimeout: FiniteDuration)(implicit refFactory: ActorRefFactory, executionContext: ExecutionContext) : SendReceive =
    httpTransport(IO(Http)(actorSystem), HostConnectorSetup(host.authority.host.address, host.effectivePort, sslEncryption = host.scheme == "https"))(executionContext, Timeout(futureTimeout))

  def httpTransport(host: Uri, settings: HostConnectorSettings)(implicit refFactory: ActorRefFactory, executionContext: ExecutionContext,
                                                                futureTimeout: Timeout) : SendReceive =
    httpTransport(IO(Http)(actorSystem), HostConnectorSetup(host.authority.host.address, host.effectivePort, sslEncryption = host.scheme == "https", settings=Some(settings)))


  def httpTransport(transport: ActorRef, config: HostConnectorSetup)(implicit ec: ExecutionContext, futureTimeout: Timeout): SendReceive =
    request => transport ? (request, config) map {
      case x: HttpResponse          => x
      case x: HttpResponsePart      => sys.error("sendReceive doesn't support chunked responses, try sendTo instead")
      case x: Http.ConnectionClosed => sys.error("Connection closed before reception of response: " + x)
      case x                        => sys.error("Unexpected response from HTTP transport: " + x)
    }
}
