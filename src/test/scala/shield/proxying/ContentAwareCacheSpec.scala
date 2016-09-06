package shield.proxying

import java.util.concurrent.TimeUnit
import org.specs2.mutable.Specification
import scala.concurrent.duration.Duration
import shield.kvstore.MemoryStore
import spray.http._
import scala.concurrent.Await
import shield.metrics.Instrumented
import scala.util.Try

class ContentAwareCacheSpec extends Specification with Instrumented{

  "ContentAwareCache lookup" should {
    "throw CacheLookupError when no cache key exists" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com")).withHeaders(HttpHeaders.Accept(MediaTypes.`application/json`))
      )

      //Test failed randomly due to warmup of first lookup with the cache taking >1 second.
      //Send an initial request to not mistakenly fail the test.
      val warmupRequest = contentAwareCache.lookup(parsedRequest, "<cacheKey>")
      Try { Await.result(warmupRequest, Duration(1, TimeUnit.SECONDS)) }

      val responseFuture = contentAwareCache.lookup(parsedRequest, "<cacheKey>")
      responseFuture must throwAn(CacheLookupError).await
    }

    "throw CacheLookupError when no content type matches" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com")).withHeaders(HttpHeaders.Accept(MediaTypes.`application/json`))
      )
      store.setAdd("contenttypes|<cacheKey>", MediaTypes.`application/xml`.toString())

      val warmupRequest = contentAwareCache.lookup(parsedRequest, "<cacheKey>")
      Try { Await.result(warmupRequest, Duration(1, TimeUnit.SECONDS)) }

      val responseFuture = contentAwareCache.lookup(parsedRequest, "<cacheKey>")
      Await.result(responseFuture, Duration(1, "second")) should throwA[RuntimeException]
    }

    "throw CacheLookupError when no response cached" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com")).withHeaders(HttpHeaders.Accept(MediaTypes.`application/json`))
      )
      store.setAdd("contenttypes|<cacheKey>", MediaTypes.`application/json`.toString())

      val warmupRequest = contentAwareCache.lookup(parsedRequest, "<cacheKey>")
      Try { Await.result(warmupRequest, Duration(1, TimeUnit.SECONDS)) }

      val responseFuture = contentAwareCache.lookup(parsedRequest, "<cacheKey>")
      responseFuture must throwAn(CacheLookupError).await
    }

    "return a response" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com")).withHeaders(HttpHeaders.Accept(MediaTypes.`application/json`))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
      store.setAdd("contenttypes|<cacheKey>", MediaTypes.`application/json`.toString())
      store.keySet("response|application/json|<cacheKey>", response)

      val warmupRequest = contentAwareCache.lookup(parsedRequest, "<cacheKey>")
      Try { Await.result(warmupRequest, Duration(1, TimeUnit.SECONDS)) }

      val responseFuture = contentAwareCache.lookup(parsedRequest, "<cacheKey>")
      responseFuture must be_==(response).await
    }

    "return a response based on content type" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val jsonRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com")).withHeaders(HttpHeaders.Accept(MediaTypes.`application/json`))
      )
      val xmlRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com")).withHeaders(HttpHeaders.Accept(MediaTypes.`application/xml`))
      )
      val stringRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com")).withHeaders(HttpHeaders.Accept(MediaTypes.`application/octet-stream`))
      )
      val jsonResponse = HttpResponse(status = StatusCodes.OK, entity = "<json>")
      val xmlResponse = HttpResponse(status = StatusCodes.OK, entity = "<xml>")
      val stringResponse = HttpResponse(status = StatusCodes.OK, entity = "<string>")
      store.setAdd("contenttypes|<cacheKey>", MediaTypes.`application/json`.toString())
      store.setAdd("contenttypes|<cacheKey>", MediaTypes.`application/xml`.toString())
      store.setAdd("contenttypes|<cacheKey>", MediaTypes.`application/octet-stream`.toString())
      store.keySet("response|application/json|<cacheKey>", jsonResponse)
      store.keySet("response|application/xml|<cacheKey>", xmlResponse)
      store.keySet("response|application/octet-stream|<cacheKey>", stringResponse)

      val warmupRequest = contentAwareCache.lookup(jsonRequest, "<cacheKey>")
      Try { Await.result(warmupRequest, Duration(1, TimeUnit.SECONDS)) }

      val jsonResponseFuture = contentAwareCache.lookup(jsonRequest, "<cacheKey>")
      val xmlResponseFuture = contentAwareCache.lookup(xmlRequest, "<cacheKey>")
      val stringResponseFuture = contentAwareCache.lookup(stringRequest, "<cacheKey>")
      jsonResponseFuture must be_==(jsonResponse).await
      xmlResponseFuture must be_==(xmlResponse).await
      stringResponseFuture must be_==(stringResponse).await
    }

  }

  "ContentAwareCache safeSave" should {

    "not save response if http method is not GET" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(method=HttpMethods.POST, uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
      contentAwareCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|application/octet-stream|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "not save response if request says not to store it" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
          .withHeaders(HttpHeaders.`Cache-Control`.apply(CacheDirectives.`no-store`))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
      contentAwareCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|application/octet-stream|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "not save response if response says not to store it" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
        .withHeaders(HttpHeaders.`Cache-Control`.apply(CacheDirectives.`no-store`))
      contentAwareCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|application/octet-stream|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "not save response if response is private" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
        .withHeaders(HttpHeaders.`Cache-Control`.apply(CacheDirectives.`private`.apply("<field>")))
      contentAwareCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|application/octet-stream|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "not save response if request contains authorization header and response is not marked as public" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
          .withHeaders(HttpHeaders.Authorization.apply(BasicHttpCredentials.apply("<credential>")))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
      contentAwareCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|application/octet-stream|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "not save response if response cannot determine expiration" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.Accepted, entity = "<response>")
      contentAwareCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|application/octet-stream|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "not save response if response must be validated" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
        .withHeaders(HttpHeaders.`Cache-Control`.apply(CacheDirectives.`no-cache`))
      contentAwareCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|application/octet-stream|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "delete cache entries if the request is not safe" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val getRequest = ParsedRequest.parse(
        new HttpRequest(method=HttpMethods.GET, uri = Uri("http://example.com"))
      )
      val postRequest = ParsedRequest.parse(
        new HttpRequest(method=HttpMethods.POST, uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
      store.setAdd("contenttypes|<cacheKey>", MediaTypes.`application/octet-stream`.toString())
      store.keySet("response|application/octet-stream|<cacheKey>", response)

      val responseFuture = contentAwareCache.lookup(getRequest, "<cacheKey>")
      responseFuture must be_==(response).await

      contentAwareCache.safeSave(postRequest, response, "<cacheKey>")
      val errorFuture = contentAwareCache.lookup(getRequest, "<cacheKey>")
      errorFuture must throwAn(CacheLookupError).await
    }

    "save response" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val contentAwareCache = new ContentAwareCache("<cacheId>", store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
      contentAwareCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|application/octet-stream|<cacheKey>")
      responseFuture must be_==(Some(response)).await
    }
  }

}
