package shield.proxying

import org.specs2.mutable.Specification
import spray.http.HttpHeaders.RawHeader
import spray.http.{HttpHeaders, HttpRequest}
import shield.implicits.HttpImplicits._

class HttpProxyLogicSpec extends Specification {
  "HttpProxyLogic" should {
    "Use remote-address when adding to x-forwarded-for" in {
      val request = HttpRequest().withHeaders(
        RawHeader("X-Forwarded-For", "1.1.1.1"),
        RawHeader("Remote-Address", "2.2.2.2")
      ).withTrustXForwardedFor(1)
      getRemoteAddress(request) must be equalTo "2.2.2.2"
      getClientIp(request) must be equalTo "1.1.1.1"
    }

    "Add remote-address to x-forward-for header" in {
      val request = HttpRequest().withHeaders(
        RawHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2"),
        RawHeader("Remote-Address", "3.3.3.3")
      ).withTrustXForwardedFor(1)
      HttpProxyLogic.forwardedHeader(request.headers) must be equalTo RawHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2, 3.3.3.3")
    }

    "Add remote-address to x-forwarded-for header" in {
      val request = HttpRequest().withHeaders(
        RawHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2"),
        RawHeader("Remote-Address", "3.3.3.3")
      )
      HttpProxyLogic.forwardedHeader(request.headers) must be equalTo RawHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2, 3.3.3.3")
    }

    "If remote-address does not exist then append 127.0.0.1" in {
      val request = HttpRequest().withHeaders(
        RawHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2")
      )
      HttpProxyLogic.forwardedHeader(request.headers) must be equalTo RawHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2, 127.0.0.1")
    }

    "Scrub requests of unrequired headers" in {
      val request = HttpRequest().withHeaders(
        HttpHeaders.`X-Forwarded-For`("1.1.1.1"),
        HttpHeaders.`Remote-Address`("2.2.2.2")
      ).withTrustXForwardedFor(1)

      HttpProxyLogic.scrubRequest(request).headers.exists(_.lowercaseName == "client-address") must be equalTo false
    }

    def getHeader(request: HttpRequest, header: String): Option[String] = {
      request.headers.find(_.lowercaseName == header).map(_.value)
    }

    def getRemoteAddress(request: HttpRequest) : String = {
      getHeader(request, "remote-address").get
    }

    def getClientIp(request: HttpRequest): String = {
      getHeader(request, "client-address").get
    }
  }
}