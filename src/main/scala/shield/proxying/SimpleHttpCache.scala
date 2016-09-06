package shield.proxying

import com.typesafe.scalalogging.LazyLogging
import shield.implicits.FutureUtil
import shield.kvstore.KVStore
import spray.http.{HttpMethods, HttpResponse}

import scala.concurrent.{Future, ExecutionContext}

class SimpleHttpCache(store: KVStore)(implicit context: ExecutionContext) extends HttpCache with LazyLogging {

  // todo: reasonable timeouts
  def lookup(request: ParsedRequest, cacheKey: String) : Future[HttpResponse] = {
    store.keyGet(s"response|$cacheKey").flatMap {
      case None => Future.failed(CacheLookupError)
      case Some(response) => Future.successful(response)
    }
  }

  def safeSave(request: ParsedRequest, rawResponse: HttpResponse, cacheKey: String) = {
    val response = ParsedResponse.parse(rawResponse)

    // don't bother storing it if it will require validation
    val canStore = (
      request.method == HttpMethods.GET
        && (!request.cache_control_no_store && !response.cache_control_no_store)
        && !response.cache_control_private
        && (!request.authorization || response.cache_control_public)
        && response.canDetermineExpiration
        && !response.mustValidate
      )

    // ss4.4 - Invalidation
    if (!request.method.isSafe && rawResponse.status.isSuccess) {
      // todo: delete other cache entries using response's location and content-location header values
      store.keyDelete(s"response|$cacheKey").andThen(FutureUtil.logFailure("SimpleHttpCache::invalidateOnUnsafe"))
    }

    if (canStore) {
      store.keySet(s"response|$cacheKey", rawResponse).andThen(FutureUtil.logFailure("SimpleHttpCache::saveFullResponse"))
    }
  }
}
