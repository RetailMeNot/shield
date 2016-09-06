package shield.transports

import com.amazonaws.services.lambda._
import spray.http.HttpHeaders.RawHeader
import spray.http.{HttpEntity, _}
import spray.json._
import spray.json.JsonParser
import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.services.lambda.model._
import com.google.common.io.BaseEncoding
import spray.http.parser.HttpParser

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.util.Try

object LambdaTransport {
  type SendReceive = spray.client.pipelining.SendReceive
  val base64 = BaseEncoding.base64()
  class LambdaAsyncHandler(promise: Promise[InvokeResult]) extends AsyncHandler[InvokeRequest, InvokeResult] {
    def onError(exception: Exception) = promise.failure(exception)

    def onSuccess(request: InvokeRequest, result: InvokeResult) = promise.success(result)
  }

  def lambdaTransport(arn: String): SendReceive = {
    val client = AWSLambdaAsyncClientBuilder.defaultClient()
    def executeCall(request: HttpRequest): Future[HttpResponse] = {
      val req = new InvokeRequest()
        .withFunctionName(arn)
        .withPayload(LambdaRequest.translate(request).toJson.compactPrint)
      val p = Promise[InvokeResult]
      client.invokeAsync(req, new LambdaAsyncHandler(p))
      p.future.map(req => {
        JsonParser(ParserInput(req.getPayload.array)).convertTo[LambdaResponse].toResponse
      })
    }
    executeCall
  }

  case class LambdaRequest(method: String, headers: List[(String, String)], uri: String, body: Option[String])

  object LambdaRequest extends DefaultJsonProtocol {
    def translate(request: HttpRequest): LambdaRequest = {
      val processedHeaders = request.entity.toOption match {
        // No body - leave the content-type header (probably a HEAD request)
        case None => request.headers
        // Some body - set the content-type based off what spray associated with the entity
        // * content-type has probably been filtered by HttpProxyLogic before reaching here, so this likely redundant
        // * Adding-content-type from the entity matches Spray's HttpRequest rendering logic
        case Some(entity) => HttpHeaders.`Content-Type`(entity.contentType) :: request.headers.filterNot(_.lowercaseName == "content-type")
      }
      val headers =  processedHeaders.map(header => (header.lowercaseName, header.value))
      val body = request.entity.toOption.map(e => base64.encode(e.data.toByteArray))
      LambdaRequest(request.method.value, headers, request.uri.toString, body)
    }
    implicit val requestFormat : JsonFormat[LambdaRequest] = jsonFormat4(LambdaRequest.apply)
  }

  case class LambdaResponse(status: Int, headers: List[(String, String)], body: Option[String]) {
    def parseHeaders : List[HttpHeader] = HttpParser.parseHeaders(headers.map(RawHeader.tupled))._2
    def toResponse: HttpResponse = {
      val parsedContentType = headers.find(_._1.toLowerCase() == "content-type").map(ct => HttpParser.parse(HttpParser.ContentTypeHeaderValue, ct._2))
      val entity = body.map(base64.decode)

      (parsedContentType, entity) match {
        case (None, None) => HttpResponse(status, headers = parseHeaders)
        case (None, Some(data)) => HttpResponse(status, HttpEntity(ContentTypes.`application/octet-stream`, data), parseHeaders)
        case (Some(Left(err)), _) => HttpResponse(StatusCodes.BadGateway, HttpEntity(s"Upstream supplied an invalid content-type header: ${err.detail}"))
        case (Some(Right(contentType)), None) => HttpResponse(status, headers = parseHeaders)
        case (Some(Right(contentType)), Some(data)) => HttpResponse(status, HttpEntity(contentType, data), parseHeaders)
      }
    }
  }

  object LambdaResponse extends DefaultJsonProtocol {
    implicit val responseFormat : JsonFormat[LambdaResponse] = jsonFormat3(LambdaResponse.apply)
  }
}
