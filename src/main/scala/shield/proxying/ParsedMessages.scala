package shield.proxying

import shield.implicits.StringImplicits._
import shield.metrics.Instrumented
import spray.http.HttpHeaders.{RawHeader, `Cache-Control`}
import spray.http._
import spray.http.parser.HttpParser

import scala.concurrent.duration._


// todo: allow config to ignore request or response cache-control directives
// todo: handle repeated headers as invalid (refer to spec)
// todo: cleanup unused fields
object ParsedRequest extends Instrumented {
  private val parseTimer = metrics.timer("parse")
  def parse(request: HttpRequest) : ParsedRequest = parseTimer.time {
    var max_age : Option[Long] = None
    var s_maxage : Option[Long] = None
    var min_fresh : Option[Long] = None
    var max_stale_set = false
    var max_stale : Option[Long] = None
    var no_cache = false
    var no_store = false
    var only_if_cached = false
    var pragma = false
    var cache_control_present = false
    var auth = false
    var accept = false


    def visitDirective(directive: CacheDirective) : Unit = directive match {
      case CacheDirectives.`max-age`(seconds) => max_age = Some(seconds)
      case CacheDirectives.`s-maxage`(seconds) => s_maxage = Some(seconds)
      case CacheDirectives.`min-fresh`(seconds) => min_fresh = Some(seconds)
      case CacheDirectives.`max-stale`(seconds) => {
        max_stale = seconds
        max_stale_set = true
      }
      case CacheDirectives.`no-cache` => no_cache = true
      case CacheDirectives.`no-cache`(_) => no_cache = true //todo: listen to field names
      case CacheDirectives.`no-store` => no_store = true
      case CacheDirectives.`only-if-cached` => only_if_cached = true
      case _ => ()
    }

    // todo: rawheader pattern matching here is broken - case sensitive!
    def visitHeader(header: HttpHeader) : Unit = header match {
      case _ : HttpHeaders.Authorization => auth = true
      case _ : HttpHeaders.Accept => accept = true
      case RawHeader("pragma", value) if value.toLowerCase.contains("no-cache") => pragma = true
      case `Cache-Control`(directives) =>  {
        cache_control_present = true
        directives.foreach(visitDirective)
      }
      case _ => ()
    }

    request.headers.foreach(visitHeader)

    ParsedRequest(request.method, request.uri.path.toString(), max_age, s_maxage, min_fresh, max_stale, max_stale_set, no_cache, no_store, only_if_cached, pragma && !cache_control_present, auth, accept, DateTime.now, request)
  }
}

case class ParsedRequest(
                          method: HttpMethod,
                          path: String,
                          cache_control_max_age: Option[Long],
                          cache_control_s_maxage: Option[Long],
                          cache_control_min_fresh: Option[Long],
                          cache_control_max_stale: Option[Long],
                          cache_control_max_stale_set: Boolean,
                          cache_control_no_cache: Boolean,
                          cache_control_no_store: Boolean,
                          cache_control_only_if_cached: Boolean,
                          pragma_no_cache: Boolean,
                          authorization: Boolean,
                          accept: Boolean,
                          wallClock: DateTime,
                          rawRequest: HttpRequest
                          ) {
  def mustValidate : Boolean =
    cache_control_no_cache || pragma_no_cache

  def forbiddenToServeStale : Boolean = (
    (cache_control_max_age.isDefined && cache_control_max_stale.isEmpty) // ss5.2.1.1
      || cache_control_s_maxage.isDefined
    )
}

object ParsedResponse extends Instrumented {
  private val parseTimer = metrics.timer("parse")
  def parse(response: HttpResponse) : ParsedResponse = parseTimer.time {

    var no_store = false
    var no_cache = false
    var priv = false
    var must_revalidate = false
    var proxy_revalidate = false
    var public = false
    var max_age : Option[Long] = None
    var s_maxage : Option[Long] = None
    var expires: Option[DateTime] = None
    var content_range = false
    var content_type = ContentTypes.`application/octet-stream`
    var age: Option[Long] = None
    var date: Option[DateTime] = None // todo: warn if not received from origin?
    var last_modified: Option[DateTime] = None


    def visitDirective(directive: CacheDirective) : Unit = directive match {
      case CacheDirectives.`no-cache`(_) => no_cache = true //todo: listen to field names
      case CacheDirectives.`no-cache` => no_cache = true
      case CacheDirectives.`no-store` => no_store = true
      case CacheDirectives.`private`(_) => priv = true // todo: listen to field names
      case CacheDirectives.`must-revalidate` => must_revalidate = true
      case CacheDirectives.`proxy-revalidate` => proxy_revalidate = true
      case CacheDirectives.public => public = true
      case CacheDirectives.`max-age`(seconds) => max_age = Some(seconds)
      case CacheDirectives.`s-maxage`(seconds) => s_maxage = Some(seconds)
      case _ => ()
    }

    // todo: rawheader pattern matching here is broken - case sensitive!
    def visitHeader(header: HttpHeader) : Unit = header match {
      case RawHeader("expires", dt) => expires = HttpParser.parse(HttpParser.`RFC1123/RFC850 Date`, dt).fold(_ => None, Some(_)) // todo: log on bad parse
      case RawHeader("content-range", _) => content_range = true
      case RawHeader("age", ageString) => age = ageString.replace("\"", "").toLongOpt // todo: log on bad parse
      case HttpHeaders.`Content-Type`(contentType) => content_type = contentType
      case HttpHeaders.Date(responseDate) => date = Some(responseDate)
      case HttpHeaders.`Last-Modified`(lastModified) => last_modified = Some(lastModified)
      case `Cache-Control`(directives) => directives.foreach(visitDirective)
      case _ => ()
    }

    response.headers.foreach(visitHeader)

    ParsedResponse(no_store, no_cache, priv, must_revalidate, proxy_revalidate, public, max_age, s_maxage, expires, content_range, content_type, age, date.getOrElse(DateTime.now), DateTime.now, last_modified, response.status, response)
  }

  val defaultCacheableCodes = Set(200, 203, 204, 206, 300, 301, 404, 405, 410, 414, 501)
}
case class ParsedResponse(
                           cache_control_no_store: Boolean,
                           cache_control_no_cache: Boolean,
                           cache_control_private: Boolean,
                           cache_control_must_revalidate: Boolean,
                           cache_control_proxy_revalidate: Boolean,
                           cache_control_public: Boolean,
                           cache_control_max_age: Option[Long],
                           cache_control_s_maxage: Option[Long],
                           expires: Option[DateTime],
                           content_range: Boolean,
                           content_type: ContentType,
                           age: Option[Long],
                           date: DateTime,
                           wallClock: DateTime,
                           lastModified: Option[DateTime],
                           status: StatusCode,
                           rawResponse: HttpResponse
                           ) {
  def mustValidate : Boolean = cache_control_no_cache
  def forbiddenToServeStale : Boolean = (
    cache_control_must_revalidate
      || cache_control_proxy_revalidate
      || cache_control_s_maxage.isDefined
    )
  def explicitExpiration : Boolean = (
    expires.isDefined
      || cache_control_max_age.isDefined
      || cache_control_s_maxage.isDefined
    )
  def implicitExpirationAllowed : Boolean = (
    ParsedResponse.defaultCacheableCodes.contains(status.intValue)
      || cache_control_public
    )
  def canDetermineExpiration : Boolean = (
    explicitExpiration
      || implicitExpirationAllowed
    )

  def ageHeader(now: DateTime) : HttpHeader = {
    RawHeader("Age", cacheAge(now).toString)
  }
  val staleHeader = RawHeader("Warning", "110 - \"Response is Stale\"")
  def cacheAge(now: DateTime) : Long = {
    // todo: this assumes that `date` is equivalent to when the entry was placed in the cache.  Validate or fix that assumption
    // invalid assumption if there is an upstream cache that doesn't refresh the date header
    math.max(0, (now.clicks - date.clicks) / 1000 + age.getOrElse(0L))
  }

  def ttl(now: DateTime, requestMaxAge: Option[Long], minFresh: Option[Long]) : Long = {
    val effectiveAge = cacheAge(now) + minFresh.getOrElse(0L)
    val maxAge = math.min(requestMaxAge.getOrElse(freshnessLifetime), freshnessLifetime)

    maxAge - effectiveAge
  }

  def freshnessLifetime: Long = {
    // todo: implement
    /*
     When there is more than one value present for a given directive
     (e.g., two Expires header fields, multiple Cache-Control: max-age
     directives), the directive's value is considered invalid.  Caches are
     encouraged to consider responses that have invalid freshness
     information to be stale.
     */
    cache_control_s_maxage.getOrElse(
      cache_control_max_age.getOrElse(
        expires.map(exp => (exp.clicks - date.clicks) / 1000).getOrElse(
          heuristicFreshness
        )
      )
    )
  }

  def heuristicFreshness : Long = {
    lastModified.map(lm => ((lm.clicks - date.clicks) * 0.1).toLong / 1000).getOrElse(
      // todo: config drive this default duration
      1.hours.toSeconds
    )
  }
}
