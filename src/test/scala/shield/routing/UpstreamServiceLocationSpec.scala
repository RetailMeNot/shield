package shield.routing

import org.specs2.mutable.Specification
import shield.config.{HttpServiceLocation, ServiceLocation}
import spray.http.Uri

import scala.util.Try

class UpstreamServiceLocationSpec extends Specification {
  "UpstreamServiceLocation" should {
    "accept valid https urls" in {
      val svc = HttpServiceLocation(Uri("https://example.edu"))
      svc.baseUrl.scheme must be equalTo "https"
      svc.baseUrl.authority.host.address must be equalTo "example.edu"
    }

    "accept valid http urls" in {
      val svc = HttpServiceLocation(Uri("http://example.edu"))
      svc.baseUrl.scheme must be equalTo "http"
      svc.baseUrl.authority.host.address must be equalTo "example.edu"
    }

    "use the correct port" in {
      val default_http = HttpServiceLocation(Uri("http://example.edu"))
      default_http.baseUrl.authority.port must be equalTo 0

      val custom_http = HttpServiceLocation(Uri("http://example.edu:5001"))
      custom_http.baseUrl.authority.port must be equalTo 5001

      val default_https = HttpServiceLocation(Uri("https://example.edu"))
      default_https.baseUrl.authority.port must be equalTo 0

      val custom_https = HttpServiceLocation(Uri("https://example.edu:8443"))
      custom_https.baseUrl.authority.port must be equalTo 8443
    }

    "reject unrecognized schemes" in {
      val ftp = Try { HttpServiceLocation(Uri("ftp://example.edu")) }
      ftp.isSuccess must be equalTo false

      val mailto = Try { HttpServiceLocation(Uri("mailto://example.edu")) }
      mailto.isSuccess must be equalTo false
    }

    "ignore case in urls" in {
      val svc = HttpServiceLocation(Uri("HTTPs://EXamPLE.edu"))
      svc.baseUrl.authority.host.address must be equalTo "example.edu"
      svc.baseUrl.scheme must be equalTo "https"
    }

    "reject urls with a path" in {
      val empty = Try { HttpServiceLocation(Uri("http://example.edu/")) }
      empty.isSuccess must be equalTo false

      val nonempty = Try { HttpServiceLocation(Uri("http://example.edu/foobar")) }
      nonempty.isSuccess must be equalTo false
    }

    "reject urls with a fragment" in {
      val empty = Try { HttpServiceLocation(Uri("http://example.edu#")) }
      empty.isSuccess must be equalTo false

      val nonempty = Try { HttpServiceLocation(Uri("http://example.edu#foobar")) }
      nonempty.isSuccess must be equalTo false
    }

    "reject urls with a query" in {
      val empty = Try { HttpServiceLocation(Uri("http://example.edu?")) }
      empty.isSuccess must be equalTo false

      val nonempty = Try { HttpServiceLocation(Uri("http://example.edu?foo=bar")) }
      nonempty.isSuccess must be equalTo false
    }

    "reject urls with a auth information" in {
      val empty = Try { HttpServiceLocation(Uri("http://:@example.edu")) }
      empty.isSuccess must be equalTo false

      val nonempty = Try { HttpServiceLocation(Uri("http://foo:bar@example.edu")) }
      nonempty.isSuccess must be equalTo false
    }

    "reject relative urls" in {
      val relative = Try { HttpServiceLocation(Uri("/foobar")) }
      relative.isSuccess must be equalTo false
    }

    "reject empty urls" in {
      val relative = Try { HttpServiceLocation(Uri("")) }
      relative.isSuccess must be equalTo false
    }
  }
}
