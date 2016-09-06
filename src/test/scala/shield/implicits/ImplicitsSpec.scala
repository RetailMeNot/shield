package shield.implicits

import org.specs2.mutable.Specification
import spray.http.HttpHeaders.RawHeader
import spray.http.{HttpResponse, HttpRequest}
import shield.implicits.HttpImplicits._

class ImplicitsSpec extends Specification {
  "HttpImplicits" should {
    "Replace a request header" in {
      var request = HttpRequest().withHeaders(
        RawHeader("sample", "header")
      )
      request = request.withReplacedHeaders(RawHeader("sample","newHeader"))
      request.headers.length must be equalTo 1
      request.headers(0).name must be equalTo "sample"
      request.headers(0).value must be equalTo "newHeader"
    }

    "Add a request header" in {
      var request = HttpRequest().withHeaders(
        RawHeader("sample", "header")
      )
      request = request.withAdditionalHeaders(
        RawHeader("additional", "testHeader")
      )

      request.headers.length must be equalTo 2
      request.headers.find(_.lowercaseName == "additional").get.value must be equalTo "testHeader"
      request.headers.find(_.lowercaseName == "sample").get.value must be equalTo "header"
    }

    "Strip a request header" in {
      var request = HttpRequest().withHeaders(
        RawHeader("sample", "header"),
        RawHeader("additional", "testHeader")
      )

      request = request.withStrippedHeaders(Set("sample"))
      request.headers.length must be equalTo 1
      request.headers(0).name must be equalTo "additional"
      request.headers(0).value must be equalTo "testHeader"
    }

    "Replace a response header" in {
      var response = HttpResponse().withHeaders(
        RawHeader("sample", "header")
      )
      response = response.withReplacedHeaders(RawHeader("sample","newHeader"))
      response.headers.length must be equalTo 1
      response.headers(0).name must be equalTo "sample"
      response.headers(0).value must be equalTo "newHeader"
    }

    "Add a response header" in {
      var response = HttpResponse().withHeaders(
        RawHeader("sample", "header")
      )
      response = response.withAdditionalHeaders(
        RawHeader("additional", "testHeader")
      )

      response.headers.length must be equalTo 2
      response.headers.find(_.lowercaseName == "additional").get.value must be equalTo "testHeader"
      response.headers.find(_.lowercaseName == "sample").get.value must be equalTo "header"
    }

    "Strip a response header" in {
      var response = HttpResponse().withHeaders(
        RawHeader("sample", "header"),
        RawHeader("additional", "testHeader")
      )

      response = response.withStrippedHeaders(Set("sample"))
      response.headers.length must be equalTo 1
      response.headers(0).name must be equalTo "additional"
      response.headers(0).value must be equalTo "testHeader"
    }

    "Strip a response header that is capitalized" in {
      var response = HttpResponse().withHeaders(
        RawHeader("X-Cache", "header"),
        RawHeader("additional","testHeader")
      )

      response = response.withStrippedHeaders(Set("X-Cache"))
      response.headers.length must be equalTo 1
      response.headers(0).name must be equalTo "additional"
      response.headers(0).value must be equalTo "testHeader"
    }
  }
}
