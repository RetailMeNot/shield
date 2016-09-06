package shield.transports

import com.google.common.io.BaseEncoding
import org.specs2.mutable._
import shield.transports.LambdaTransport.{LambdaRequest, LambdaResponse}
import spray.http.HttpHeaders.RawHeader
import spray.http._
import spray.json.{ParserInput, _}

class LambdaTransportSpec extends Specification {
  "LambdaTransport" should {
    val base64 = BaseEncoding.base64()

    "encode a request with a body" in {
      val request = HttpRequest(HttpMethods.POST, Uri("http://www.example.com"), List(RawHeader("test-header","test-header-value")), HttpEntity(HttpData("Hello World Test Data")))
      val expected = JsObject(
        "method" -> JsString("POST"),
        "uri" -> JsString("http://www.example.com"),
        "headers" -> JsArray(JsArray(JsString("content-type"),JsString("application/octet-stream")), JsArray(JsString("test-header"),JsString("test-header-value"))),
        "body" -> JsString(base64.encode("Hello World Test Data".getBytes))
      )
      LambdaRequest.translate(request).toJson mustEqual expected
    }

    "encode a request with a body, setting content-type from the entity" in {
      val request = HttpRequest(
        HttpMethods.POST,
        Uri("http://www.example.com"),
        List(RawHeader("test-header","test-header-value")),
        HttpEntity(ContentTypes.`application/json`, HttpData("{\"hello\": \"world\"}"))
      )
      val expected = JsObject(
        "method" -> JsString("POST"),
        "uri" -> JsString("http://www.example.com"),
        "headers" -> JsArray(JsArray(JsString("content-type"),JsString("application/json; charset=UTF-8")), JsArray(JsString("test-header"),JsString("test-header-value"))),
        "body" -> JsString(base64.encode("{\"hello\": \"world\"}".getBytes))
      )
      LambdaRequest.translate(request).toJson mustEqual expected
    }

    "encode a request with a body, ignoring the content-type header and using content-type from the entity" in {
      val request = HttpRequest(
        HttpMethods.POST,
        Uri("http://www.example.com"),
        List(
          RawHeader("test-header","test-header-value"),
          HttpHeaders.`Content-Type`(ContentTypes.`text/plain`)
        ),
        HttpEntity(HttpData("Hello World Test Data"))
      )
      val expected = JsObject(
        "method" -> JsString("POST"),
        "uri" -> JsString("http://www.example.com"),
        "headers" -> JsArray(
          JsArray(JsString("content-type"),JsString("application/octet-stream")),
          JsArray(JsString("test-header"),JsString("test-header-value"))
        ),
        "body" -> JsString(base64.encode("Hello World Test Data".getBytes))
      )
      LambdaRequest.translate(request).toJson mustEqual expected
    }

    "encode a request without a body" in {
      val request = HttpRequest(HttpMethods.POST, Uri("http://www.example.com"), List(RawHeader("test-header","test-header-value")))
      val expected = JsObject(
        "method" -> JsString("POST"),
        "uri" -> JsString("http://www.example.com"),
        "headers" -> JsArray(JsArray(JsString("test-header"),JsString("test-header-value")))
      )
      LambdaRequest.translate(request).toJson mustEqual expected
    }

    "encode a request without a body, but keep the content-type header" in {
      val request = HttpRequest(
        HttpMethods.HEAD,
        Uri("http://www.example.com"),
        List(
          HttpHeaders.`Content-Type`(ContentTypes.`application/json`),
          RawHeader("test-header","test-header-value")
        )
      )
      val expected = JsObject(
        "method" -> JsString("HEAD"),
        "uri" -> JsString("http://www.example.com"),
        "headers" -> JsArray(
          JsArray(JsString("content-type"),JsString("application/json; charset=UTF-8")),
          JsArray(JsString("test-header"),JsString("test-header-value"))
        )
      )
      LambdaRequest.translate(request).toJson mustEqual expected
    }

    "decode a response with a body" in {
      val response = JsObject(
        "status" -> JsNumber(200),
        "headers" -> JsArray(JsArray(JsString("test-header"),JsString("test-header-value"))),
        "body" -> JsString(base64.encode("Hello World Test Data".getBytes))
      )
      val expected = HttpResponse(status = 200, headers=List(RawHeader("test-header","test-header-value")), entity = HttpEntity(HttpData("Hello World Test Data")))
      response.convertTo[LambdaResponse].toResponse mustEqual expected
    }

    "decode a response with a body with a valid Content-Type" in {
      val response = JsObject(
        "status" -> JsNumber(200),
        "headers" -> JsArray(JsArray(JsString("test-header"),JsString("test-header-value")), JsArray(JsString("Content-Type"), JsString("application/json; charset=UTF-8"))),
        "body" -> JsString(base64.encode("{\"hello\": \"world\"}".getBytes))
      )
      val expected = HttpResponse(status = 200, headers=List(HttpHeaders.`Content-Type`(ContentTypes.`application/json`), RawHeader("test-header","test-header-value")), entity = HttpEntity(ContentTypes.`application/json`, "{\"hello\": \"world\"}"))
      val actual = response.convertTo[LambdaResponse].toResponse

      actual mustEqual expected
    }

    "decode a response with a body with an invalid Content-Type" in {
      val response = JsObject(
        "status" -> JsNumber(200),
        "headers" -> JsArray(JsArray(JsString("test-header"),JsString("test-header-value")), JsArray(JsString("Content-Type"), JsString("not-a-real-content-type"))),
        "body" -> JsString(base64.encode("{\"hello\": \"world\"}".getBytes))
      )
      val expected = HttpResponse(status = 502, HttpEntity("""Upstream supplied an invalid content-type header: Unexpected end of input, expected TokenChar or '/' (line 1, pos 24):
                                                             |not-a-real-content-type
                                                             |                       ^
                                                             |""".stripMargin))
      val actual = response.convertTo[LambdaResponse].toResponse

      actual mustEqual expected
    }

    "decode a response without a body" in {
      val response = JsObject(
        "status" -> JsNumber(200),
        "headers" -> JsArray(JsArray(JsString("test-header"),JsString("test-header-value")))
      )
      val expected = HttpResponse(status = 200, headers=List(RawHeader("test-header","test-header-value")))
      val actual = response.convertTo[LambdaResponse].toResponse

      actual mustEqual expected
    }

    "decode a response without a body with a valid Content-Type" in {
      val response = JsObject(
        "status" -> JsNumber(204),
        "headers" -> JsArray(JsArray(JsString("test-header"),JsString("test-header-value")), JsArray(JsString("Content-Type"), JsString("application/json; charset=UTF-8")))
      )
      val expected = HttpResponse(status = 204, headers=List(HttpHeaders.`Content-Type`(ContentTypes.`application/json`), RawHeader("test-header","test-header-value")))
      val actual = response.convertTo[LambdaResponse].toResponse

      actual mustEqual expected
    }

    "decode a response without a body with an invalid Content-Type" in {
      val response = JsObject(
        "status" -> JsNumber(204),
        "headers" -> JsArray(JsArray(JsString("test-header"),JsString("test-header-value")), JsArray(JsString("Content-Type"), JsString("not-a-real-content-type")))
      )
      val expected = HttpResponse(status = 502, HttpEntity("""Upstream supplied an invalid content-type header: Unexpected end of input, expected TokenChar or '/' (line 1, pos 24):
                                                             |not-a-real-content-type
                                                             |                       ^
                                                             |""".stripMargin))
      val actual = response.convertTo[LambdaResponse].toResponse

      actual mustEqual expected
    }
  }
}
