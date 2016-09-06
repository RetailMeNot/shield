package shield.proxying

import com.typesafe.scalalogging.LazyLogging
import shield.kvstore.KVStore
import spray.http._
import spray.http.parser.HttpParser

import scala.concurrent.{ExecutionContext, Future}


// todo: unit tests
// todo: profiling tests
// todo: rfc5861 support (stale-if-error, stale-while-revalidate) - https://tools.ietf.org/html/rfc5861#section-4
// todo: swagger auth support - does it implicitly add headers or parameters?
// todo: POST caching support - https://www.mnot.net/blog/2012/09/24/caching_POST
// todo: vendor config on swagger endpoint to allow caching on the future of first request
// todo: enable support for receiving conditional requests
// todo: enable support for using conditional requests for validation
// todo: enable support for range requests
// todo: enable support for streaming requests/responses
// todo: enable support for HEAD requests
// todo: seem to have lost support for only-if-cached
// todo: prefetch when <= 10s freshness left (jitter to prevent thundering herd)
// todo: optimization test: store uuid and freshness information in the cached hash
// todo: invalidate on post (where is this in the rfc?)
// the holy bible: https://tools.ietf.org/html/rfc7234

case object CacheLookupError extends RuntimeException

trait HttpCache {
  def lookup(request: ParsedRequest, cacheKey: String) : Future[HttpResponse]
  // todo: verify that the response has a date header before saving
  def save(request: ParsedRequest, rawResponse: HttpResponse, cacheKey: String) : Unit = safeSave(request, withDateHeader(rawResponse), cacheKey)

  def withDateHeader(response: HttpResponse) : HttpResponse = {
    val dateHeader = response.header[HttpHeaders.Date]
    response.copy(headers = dateHeader.getOrElse(HttpHeaders.Date(DateTime.now)) :: response.headers.filter(_.lowercaseName != "date"))
  }

  protected def safeSave(request: ParsedRequest, rawResponse: HttpResponse, cacheKey: String) : Unit
}


