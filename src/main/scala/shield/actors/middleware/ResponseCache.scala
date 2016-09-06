package shield.actors.middleware

import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.pipe
import nl.grons.metrics.scala.{Meter, Timer}
import shield.actors._
import shield.config.{ServiceLocation, Settings}
import shield.implicits.FutureUtil
import shield.implicits.HttpImplicits._
import shield.kvstore._
import shield.metrics.Instrumented
import shield.proxying._
import shield.routing._
import spray.http.HttpHeaders.RawHeader
import spray.http._

import scala.concurrent.Future
import scala.concurrent.duration._


object ResponseCache extends Instrumented {
  def props(id: String, store: KVStore, location: ServiceLocation) : Props = Props(new ResponseCache(id, store, location))

  private val keyTimer: Timer = metrics.timer("cacheKey")
  // todo: this feels like it should belong somewhere else
  def requestCacheKey(request: HttpRequest, tryParams: Set[Param]) : String = keyTimer.time {
    val keyTuples = tryParams.toList.flatMap(p => p.getValues(request).map(v => s"${p.location.key}|${p.name}|$v")).sorted
    s"${request.uri.path}?${keyTuples.mkString("&")}"
  }

  def responseCacheKey(request: HttpRequest, response: HttpResponse, explicitCacheParams: Option[Set[Param]]) : String = explicitCacheParams match {
    case None =>
      // todo: the chance of constructing a key here that we'll actually hit at request time seems miniscule.  Maybe we'd
      //       be better off not bothering?
      val queryParams = request.uri.query.map(p => QueryParam(p._1)).toSet
      val headerParams = response.headers.find(_.lowercaseName == "vary").map(_.value.split(",").map(h => HeaderParam(h.trim().toLowerCase)).toSet).getOrElse(Set.empty)
      requestCacheKey(request, queryParams ++ headerParams)
    case Some(params) =>
      requestCacheKey(request, params)
  }
}
class ResponseCache(id: String, store: KVStore, location: ServiceLocation) extends Actor with ActorLogging with RestartLogging with Instrumented {
  import context._
  val cache = new ContentAwareCache(id, store)
  val settings = Settings(context.system)
  // todo: responseMiddleware.id needs to use this.id for uniqueness
  def staleRecoverMiddleware(request: ParsedRequest, staleResponse: ParsedResponse) = Some(Middleware("cache-stale-rescue", 5.millis, context.actorOf(CacheStaleRecover.props(s"$id-stale-recovery", request, staleResponse, location, settings.LocalServiceName, cache))))
  val uncacheableInjector = Some(Middleware("cache-header", 10.millis, context.actorOf(CacheHeaderInjector.props(s"$id-header-injector", "uncacheable", cache))))
  val missInjector = Some(Middleware("cache-header", 10.millis, context.actorOf(CacheHeaderInjector.props(s"$id-header-injector", "miss", cache))))

  val fromCacheTimer = metrics.timer("serveFromCache", id)
  private val hitMeter: Meter = metrics.meter("cacheHit", id)
  def serveFromCache(stage: String, template: EndpointTemplate, request: ParsedRequest)(cachedResponse: (HttpResponse, Set[Param])) : MiddlewareResponse = fromCacheTimer.time {
    val response = ParsedResponse.parse(cachedResponse._1)
    val ttl = response.ttl(request.wallClock, request.cache_control_max_age, request.cache_control_min_fresh)
    // the response is stale if ttl <= 0
    if (ttl <= 0) {
      hitMeter.mark()
      if (request.cache_control_max_stale_set && request.cache_control_max_stale.map(_ > -ttl).getOrElse(true)) {
        ForwardResponseCmd(stage, ResponseDetails(location, settings.LocalServiceName, template, Some(cachedResponse._2), cachedResponse._1.withReplacedHeaders(
          response.ageHeader(request.wallClock),
          response.staleHeader,
          RawHeader("X-Cache", "stale-by-request")
        )))
      } else {
        ForwardRequestCmd(
          stage,
          request.rawRequest,
          responseMiddleware = staleRecoverMiddleware(request, response)
        )
      }
    } else {
      hitMeter.mark()
      ForwardResponseCmd(stage, ResponseDetails(location, settings.LocalServiceName, template, Some(cachedResponse._2), cachedResponse._1.withReplacedHeaders(
        response.ageHeader(request.wallClock),
        RawHeader("X-Cache", "hit")
      )))
    }
  }


  // todo: meters per X-Cache directive for responses
  val localWorkTimer = metrics.timer("local-work", id)
  val kvWorkTimer = timing("kv-work", id)
  def receive = {
    case ur : DownstreamRequest => localWorkTimer.time {
      val _sender = sender()
      val request = ParsedRequest.parse(ur.request)

      // todo: handle cache revalidation
      if (request.method != HttpMethods.GET || request.mustValidate) {
        _sender ! ForwardRequestCmd(ur.stage, ur.request, responseMiddleware = uncacheableInjector)
      } else pipe(kvWorkTimer {
        val lookupAttempts = ur.destination.uniqueParamSets.map(ps => cache.lookup(request, ResponseCache.requestCacheKey(ur.request, ps)).map(r => (r, ps)))
        // todo: verify that this returns a failure only when all the lookup attempts failed
        val cacheLookup = Future.firstCompletedOf(lookupAttempts).map(serveFromCache(ur.stage, ur.destination.template, request))

        cacheLookup.andThen(FutureUtil.logFailure("cacheLookup", {
          case CacheLookupError =>
        })).recover {
          case _ => ForwardRequestCmd(ur.stage, ur.request, responseMiddleware = missInjector)
        }
      }) to _sender
    }
  }
}

// Philosophy:
// 1) If the response comes from upstream, set X-Cache header only if not set
// 2) If the response comes from this cache, force set the X-Cache header

object CacheHeaderInjector {
  def props(id: String, value: String, cache: HttpCache) : Props = Props(new CacheHeaderInjector(id, value, cache))
}
class CacheHeaderInjector(id: String, value: String, cache: HttpCache) extends Actor with ActorLogging with RestartLogging {
  def receive = {
    case r: UpstreamResponse => {
      val cacheHeader = r.details.response.headers.find(_.lowercaseName == "x-cache").getOrElse(RawHeader("X-Cache", value))
      sender ! ForwardResponseCmd(r.stage, r.details.copy(response = r.details.response.copy(
        headers = cacheHeader :: r.details.response.headers.filter(_.lowercaseName != "x-cache")
      )))
      val request = ParsedRequest.parse(r.request)

      //log.info(s"${r.response.entity.data.length} bytes - ${request.method} ${request.rawRequest.uri}")
      // todo: config drive this (we probably want to make this a "per-kvstore" setting"
      if (r.details.response.entity.data.length < 128000) {
        cache.save(request, r.details.response, ResponseCache.responseCacheKey(r.request, r.details.response, r.details.explicitCacheParams))
      }
    }
  }
}

object CacheStaleRecover {
  def props(id: String, request: ParsedRequest, response: ParsedResponse, localService: ServiceLocation, localServiceName: String, cache: HttpCache) : Props = Props(new CacheStaleRecover(id, request, response, localService, localServiceName, cache))
}
// todo: profiling optimization: destroying these actors is expensive.  Make a resizable pool
class CacheStaleRecover(id: String, request: ParsedRequest, staleResponse: ParsedResponse, localService: ServiceLocation, localServiceName: String, cache: HttpCache) extends Actor with ActorLogging with RestartLogging {
  def receive = {
    case r: UpstreamResponse => {
      if (r.details.response.status.isInstanceOf[StatusCodes.ServerError]) {
        sender ! ForwardResponseCmd(r.stage, r.details.copy(
          serviceLocation = localService,
          serviceName = localServiceName,
          response = staleResponse.rawResponse.withReplacedHeaders(
            RawHeader("X-Cache", "stale-recover"),
            staleResponse.ageHeader(request.wallClock),
            staleResponse.staleHeader
          )
        ))
      } else {
        val cacheHeader = r.details.response.headers.find(_.lowercaseName == "x-cache").getOrElse(RawHeader("X-Cache", "stale-miss"))
        sender ! ForwardResponseCmd(r.stage, r.details.copy(response = r.details.response.copy(
          headers = cacheHeader :: r.details.response.headers.filter(_.lowercaseName != "x-cache")
        )))

        // todo: if we're saving a response for an existing cacheKey/mediaType combo, we should purge the old uuid/response
        // log.info(s"${r.response.entity.data.length} bytes - ${request.method} ${request.rawRequest.uri}")
        // todo: config drive this (we probably want to make this a "per-kvstore" setting"
        if (r.details.response.entity.data.length < 128000) {
          cache.save(request, r.details.response, ResponseCache.responseCacheKey(request.rawRequest, r.details.response, r.details.explicitCacheParams))
        }
      }

      // todo: timeout harikari
      context.stop(self)
    }
  }
}
