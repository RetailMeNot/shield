package shield.proxying

import com.typesafe.scalalogging.LazyLogging
import shield.implicits.FutureUtil
import shield.kvstore.KVStore
import shield.metrics.Instrumented
import spray.http.parser.HttpParser
import spray.http.{HttpMethods, HttpResponse}

import scala.concurrent.{ExecutionContext, Future}

class ContentAwareCache(id: String, store: KVStore)(implicit context: ExecutionContext) extends HttpCache with LazyLogging with Instrumented {
  val contentTypeSelectionTimer = metrics.timer("contentTypeSelection", id)
  val contentTypeSetSizeHistogram = metrics.histogram("contentTypeSetSize", id)
  val mappingFoundMeter = metrics.meter("mappingFound", id)
  val noMappingFoundMeter = metrics.meter("noMappingFound", id)
  val retrievalSuccess = metrics.meter("retrievalSuccess", id)
  val retrievalFailure = metrics.meter("retrievalFailure", id)

  // todo: support accept-charset negotiation
  // todo: be clever here - this first lookup from redis/memory should also let us determine if the cached response is fresh or not, avoiding a 2nd lookup if need be
  def bestCachedResponseType(request: ParsedRequest, contentTypes: Seq[String]) : Option[String] = contentTypeSelectionTimer.time {
    if (contentTypes.isEmpty) {
      None
    }
    // http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html
    // ss14.1 states:
    // "If no Accept header field is present, then it is assumed that the client accepts all media types
    else if (request.accept) {
      val cachedMediaTypes = contentTypes.map(ct => (HttpParser.parse(HttpParser.ContentTypeHeaderValue, ct).right.get, ct)).toMap
      val bestContentType = request.rawRequest.acceptableContentType(cachedMediaTypes.keys.toSeq)
      // spray auto-adds a charset while calculating acceptable, but we store only store mediaType (which gets parsed as ContentType with charset = None)
      bestContentType.map(bct => cachedMediaTypes(bct.copy(definedCharset = None)))
    } else {
      // todo: try and get the most recent entry
      contentTypes.headOption
    }
  }

  // todo: reasonable timeouts
  // todo: optimization - if there's only one mediatype for the set of handlers, skip the contenttype lookup/calculation
  def lookup(request: ParsedRequest, cacheKey: String) : Future[HttpResponse] = {
    store.setGet(s"contenttypes|$cacheKey").flatMap { contentTypeMap =>
      contentTypeSetSizeHistogram += contentTypeMap.size
      bestCachedResponseType(request, contentTypeMap) match {
        case None =>
          noMappingFoundMeter.mark()
          Future.failed(CacheLookupError)
        case Some(contentType) =>
          mappingFoundMeter.mark()
          store.keyGet(s"response|$contentType|$cacheKey").flatMap {
            case None =>
              // couldn't retrieve the response (probably because it was evicted), so let's trim the hash
              retrievalFailure.mark()
              store.setRemove(s"contenttypes|$cacheKey", contentType).andThen(FutureUtil.logFailure("ContentAwareCache::removeContentType"))
              Future.failed(CacheLookupError)
            case Some(response) =>
              retrievalSuccess.mark()
              Future.successful(response)
        }
      }
    }
  }

  val trySaveTimer = metrics.timer("try-save", id)
  val saveTimer = metrics.timer("save", id)
  val invalidationMeter = metrics.meter("invalidateOnUnsafe", id)
  def safeSave(request: ParsedRequest, rawResponse: HttpResponse, cacheKey: String) = trySaveTimer.time {
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
      invalidationMeter.mark()
      store.setDelete(s"contenttypes|$cacheKey").andThen(FutureUtil.logFailure("ContentAwareCache::invalidateOnUnsafe"))
    }

    if (canStore) saveTimer.time {
      store.keySet(s"response|${response.content_type.mediaType.toString()}|$cacheKey", rawResponse).andThen(FutureUtil.logFailure("ContentAwareCache::saveFullResponse"))
      store.setAdd(s"contenttypes|$cacheKey", response.content_type.mediaType.toString()).andThen(FutureUtil.logFailure("ContentAwareCache::saveMediaTypeUuid"))
    }
  }
}
