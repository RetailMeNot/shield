package shield.proxying

import shield.actors.RequestProcessorCompleted
import shield.metrics.Instrumented
import spray.http.HttpHeaders.RawHeader
import spray.http._
import spray.httpx.encoding.Gzip

object HttpProxyLogic extends Instrumented {

  // todo: document why each of these headers are in these lists
  val scrubbedRequestHeaders = Set("transfer-encoding", "via", "x-forwarded-for", "remote-address", "vary", "accept-encoding", "connection", "client-address")
  val scrubbedResponseHeaders = Set("date", "server", "transfer-encoding", "via", "connection")

  // we only scrub these if there's no entity/body of the message.  if there is an entity/body, spray will auto-set these.  if there's not an entity/body, it's probably
  // a HEAD request
  val scrubbedBodyHeaders = Set("content-type", "content-length")

  def viaHeader(protocol: HttpProtocol, headers: List[HttpHeader]) = {
    val append = s"$protocol Shield"

    RawHeader(
      "Via",
      headers.find(_.lowercaseName == "via").map(via => s"${via.value}, $append").getOrElse(append)
    )
  }

  def forwardedHeader(headers: List[HttpHeader]) = {
    // todo: can we do something with tcp connections to get this raw?
    val realIp = headers.find(_.lowercaseName == "remote-address").map(_.value).getOrElse("127.0.0.1")

    RawHeader(
      "X-Forwarded-For",
      headers.find(_.lowercaseName == "x-forwarded-for").map(forwarded => s"${forwarded.value}, $realIp").getOrElse(realIp)
    )
  }

  private val scrubRequestTimer = metrics.timer("scrubRequest")
  def scrubRequest(request: HttpRequest) = scrubRequestTimer.time {
    val toScrub = scrubbedRequestHeaders ++ (if (request.entity.nonEmpty) scrubbedBodyHeaders else Set.empty)
    request.copy(
      uri=request.uri.toRelative,
      headers=forwardedHeader(request.headers) :: HttpHeaders.`Accept-Encoding`(HttpEncodings.gzip) :: HttpHeaders.Connection("Keep-Alive") :: request.headers.filterNot(h => toScrub.contains(h.lowercaseName)),
      protocol = HttpProtocols.`HTTP/1.1`
    )
  }

  // todo: set Vary header according to handler.HeaderParams, add Accept-Encoding, Accept
  private val scrubResponseTimer = metrics.timer("scrubResponse")
  def scrubResponse(response: HttpResponse) = scrubResponseTimer.time {
    val toScrub = scrubbedResponseHeaders ++ (if (response.entity.nonEmpty) scrubbedBodyHeaders else Set.empty)
    response.copy(
      headers = viaHeader(response.protocol, response.headers) :: response.headers.filterNot(h => toScrub.contains(h.lowercaseName)),
      protocol = HttpProtocols.`HTTP/1.1`
    )
  }

  val identityMeter = metrics.meter("identity")
  val decodeTimer = metrics.timer("decode")
  val encodeTimer = metrics.timer("encode")
  def negotiateEncoding(response: RequestProcessorCompleted) : RequestProcessorCompleted = {
    // todo: honor preferences for identity encoding
    val gzipAllowed = response.completion.request.header[HttpHeaders.`Accept-Encoding`].isDefined && response.completion.request.isEncodingAccepted(HttpEncodings.gzip)

    // todo: support more encodings, both upstream and downstream
    if (response.completion.details.response.header[HttpHeaders.`Content-Encoding`].exists(_.encoding == HttpEncodings.gzip)) {
      if (gzipAllowed) {
        identityMeter.mark()
        response
      } else {
        decodeTimer.time {
          val decoded = Gzip.decode(response.completion.details.response)
          response.copy(completion = response.completion.copy(details = response.completion.details.copy(response = decoded)))
        }
      }
    } else {
      if (gzipAllowed) {
        encodeTimer.time {
          val encoded = Gzip.encode(response.completion.details.response)
          response.copy(completion = response.completion.copy(details = response.completion.details.copy(response = encoded)))
        }
      } else {
        identityMeter.mark()
        response
      }
    }
  }
}
