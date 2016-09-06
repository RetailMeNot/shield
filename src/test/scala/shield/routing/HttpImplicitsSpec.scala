package shield.routing

import org.specs2.mutable.Specification
import shield.implicits.HttpImplicits._
import spray.http.HttpHeaders.RawHeader
import spray.http.{Uri, HttpMethods, HttpHeaders, HttpRequest}

class HttpImplicitsSpec extends Specification {
  "HttpImplicits" should {
    "Use the latest X-Forwarded-For header" in {
      val request = HttpRequest().withHeaders(
        HttpHeaders.`X-Forwarded-For`("1.1.1.1"),
        HttpHeaders.`Remote-Address`("2.2.2.2")
      )
      getClientAddress(request.withTrustXForwardedFor(1)) must be equalTo "1.1.1.1"
    }

    "Handle X-Forwarded-For headers are not formed properly" in {
      val request = HttpRequest().withHeaders(
        RawHeader("X-Forwarded-For", "1111"),
        HttpHeaders.`Remote-Address`("2.2.2.2")
      )
      getClientAddress(request.withTrustXForwardedFor(1)) must be equalTo "1111"
    }

    "Handle Remote-Address headers are not formed properly" in {
      val request = HttpRequest().withHeaders(
        RawHeader("X-Forwarded-For", "1111"),
        RawHeader("Remote-Address", "2222")
      )
      getClientAddress(request.withTrustXForwardedFor(1)) must be equalTo "1111"
    }

    "Use \"Remote-Address\" if no \"X-Forwarded-For\" is found" in {
      val request = HttpRequest().withHeaders(
        HttpHeaders.`Remote-Address`("2.2.2.2")
      )
      getClientAddress(request.withTrustXForwardedFor(1)) must be equalTo "2.2.2.2"
    }

    "Use \"Remote-Address\" header if trustProxies is set to 0" in {
      val request = HttpRequest().withHeaders(
        HttpHeaders.`X-Forwarded-For`("1.1.1.1"),
        HttpHeaders.`Remote-Address`("2.2.2.2")
      )
      getClientAddress(request.withTrustXForwardedFor(0)) must be equalTo "2.2.2.2"
    }

    "Default to \"127.0.0.1\" if neither \"X-Forwarded-For\" and \"Remote-Address\" are not present" in {
      val request = HttpRequest()
      getClientAddress(request.withTrustXForwardedFor(0)) must be equalTo "127.0.0.1"
    }

    "Use previous proxies located in the \"X-Forwarded-For\" header if the trustProxies is set to" in {
      val request = HttpRequest().withHeaders(
        RawHeader("X-Forwarded-For", "1.1.1.1,2.2.2.2,3.3.3.3,4.4.4.4"),
        HttpHeaders.`Remote-Address`("7.7.7.7")
      )
      getClientAddress(request.withTrustXForwardedFor(2)) must be equalTo "3.3.3.3"
    }

    "Using a negative trustProxie value results in an exception" in {
      val request = HttpRequest().withHeaders(
        HttpHeaders.`X-Forwarded-For`("1.1.1.1"),
        HttpHeaders.`Remote-Address`("2.2.2.2")
      )
      getClientAddress(request.withTrustXForwardedFor(-1)) must throwA[Exception]
    }

    "Use the last \"X-Forwarded-For\" value if an input is larger than the list" in {
      val request = HttpRequest().withHeaders(
        RawHeader("X-Forwarded-For", "1111,2.2.2.2,3333,4.4.4.4"),
        HttpHeaders.`Remote-Address`("7.7.7.7")
      )
      getClientAddress(request.withTrustXForwardedFor(45)) must be equalTo "1111"
    }

    "Rewrite a path using the X-Forwarded-Proto header" in {
      val request = HttpRequest(HttpMethods.GET, Uri("http://example.com/test")).withHeaders(
        RawHeader("X-Forwarded-For", "1111,2.2.2.2,3333,4.4.4.4"),
        HttpHeaders.`Remote-Address`("7.7.7.7"),
        RawHeader("X-Forwarded-Proto", "https")
      )

      request.withTrustXForwardedProto(true).uri must be equalTo "https://example.com/test"
    }

    "Leave the URI untouched if trust-x-forwarded-proto is false" in {
      val request = HttpRequest(HttpMethods.GET, Uri("http://example.com/test")).withHeaders(
        RawHeader("X-Forwarded-For", "1111,2.2.2.2,3333,4.4.4.4"),
        HttpHeaders.`Remote-Address`("7.7.7.7"),
        RawHeader("X-Forwarded-Proto", "https")
      )

      request.withTrustXForwardedProto(false).uri must be equalTo "http://example.com/test"
    }

    "Use original request if an invalid \"X-Forwarded-Proto\" header is received" in {
      val request = HttpRequest(HttpMethods.GET, Uri("http://example.com/test")).withHeaders(
        RawHeader("X-Forwarded-For", "1111,2.2.2.2,3333,4.4.4.4"),
        HttpHeaders.`Remote-Address`("7.7.7.7"),
        RawHeader("X-Forwarded-Proto", "3")
      )
       request.withTrustXForwardedProto(true).uri must be equalTo "http://example.com/test"
    }

    val uris = Set[String](
      "http://example.com/test.json",
      "http://example.com/test.xml",
      "http://example.com/test.",
      "http://example.com/test.kkkkkkkwwwwwwwwnnnnnnnnlskdfj",
      "http://example.com/test"
    )

    import shield.implicits.StringImplicits._
    val extensions = Set[String](
      "json",
      "xml",
      "kkkkkkkwwwwwwwwnnnnnnnnlskdfj",
      "ext",
      "",
      "txt",
      "php"
    ).map(t => t.mustStartWith("."))

    for(uri <- uris) {
      s"Correctly strip extensions from request $uri" in {
        HttpRequest(HttpMethods.GET, Uri(uri)).withStrippedExtensions(extensions).uri must be equalTo "http://example.com/test"
        HttpRequest(HttpMethods.GET, Uri(uri)).withStrippedExtensions(Set[String]()).uri must be equalTo uri
      }
    }

    "Not remove segments from a path, only extensions" in {
      HttpRequest(HttpMethods.GET, Uri("http://example.com/test.foo.bar/fizz/buzz")).withStrippedExtensions(extensions).uri must be equalTo "http://example.com/test.foo.bar/fizz/buzz"
      HttpRequest(HttpMethods.GET, Uri("http://example.com/test.foo.bar/fizz/buzz.ext")).withStrippedExtensions(extensions).uri must be equalTo "http://example.com/test.foo.bar/fizz/buzz"
      HttpRequest(HttpMethods.GET, Uri("http://example.com/test.foo.bar/fizz/buzz.")).withStrippedExtensions(extensions).uri must be equalTo "http://example.com/test.foo.bar/fizz/buzz"
    }

    "Not remove file extensions from query parameters" in {
      HttpRequest(HttpMethods.GET, Uri("http://example.com/handler?file=foo.txt")).withStrippedExtensions(extensions).uri must be equalTo "http://example.com/handler?file=foo.txt"
      HttpRequest(HttpMethods.GET, Uri("http://example.com/handler.php?file=foo.txt")).withStrippedExtensions(extensions).uri must be equalTo "http://example.com/handler?file=foo.txt"
    }
    "Keep remote-address unchanged" in {
      val request = HttpRequest().withHeaders(
        HttpHeaders.`X-Forwarded-For`("1.1.1.1"),
        HttpHeaders.`Remote-Address`("2.2.2.2")
      )
      getRemoteAddress(request.withTrustXForwardedFor(1)) must be equalTo "2.2.2.2"
    }
  }

  def getHeader(request: HttpRequest, header: String) : Option[String] = {
      request.headers.find(_.lowercaseName == header).map(_.value)
  }

  def getRemoteAddress(request: HttpRequest) : String = {
    getHeader(request, "remote-address").get
  }

  def getClientAddress(request: HttpRequest) : String = {
    getHeader(request, "client-address").get
  }
}
