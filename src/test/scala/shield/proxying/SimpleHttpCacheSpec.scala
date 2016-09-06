package shield.proxying

import org.specs2.mutable.Specification
import shield.kvstore.MemoryStore
import spray.http.HttpHeaders.RawHeader
import spray.http._

import scala.concurrent.Future

class SimpleHttpCacheSpec extends Specification {

  "SimpleHttpCache lookup" should {
    "throw CacheLookupError when no cache key exists" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val simpleHttpCache = new SimpleHttpCache(store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com")).withHeaders(HttpHeaders.Accept(MediaTypes.`application/json`))
      )
      val responseFuture = simpleHttpCache.lookup(parsedRequest, "<cacheKey>")
      responseFuture must throwAn(CacheLookupError).await
    }

    "return a response" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val simpleHttpCache = new SimpleHttpCache(store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com")).withHeaders(HttpHeaders.Accept(MediaTypes.`application/json`))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
      store.keySet("response|<cacheKey>", response)
      val responseFuture = simpleHttpCache.lookup(parsedRequest, "<cacheKey>")
      responseFuture must be_==(response).await
    }
  }

  "SimpleHttpCache safeSave" should {
    "not save response if http method is not GET" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val simpleHttpCache = new SimpleHttpCache(store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(method=HttpMethods.POST, uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
      simpleHttpCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "not save response if request says not to store it" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val simpleHttpCache = new SimpleHttpCache(store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
          .withHeaders(HttpHeaders.`Cache-Control`.apply(CacheDirectives.`no-store`))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
      simpleHttpCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "not save response if response says not to store it" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val simpleHttpCache = new SimpleHttpCache(store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
        .withHeaders(HttpHeaders.`Cache-Control`.apply(CacheDirectives.`no-store`))
      simpleHttpCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "not save response if response is private" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val simpleHttpCache = new SimpleHttpCache(store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
        .withHeaders(HttpHeaders.`Cache-Control`.apply(CacheDirectives.`private`.apply("<field>")))
      simpleHttpCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "not save response if request contains authorization header and response is not marked as public" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val simpleHttpCache = new SimpleHttpCache(store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
          .withHeaders(HttpHeaders.Authorization.apply(BasicHttpCredentials.apply("<credential>")))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
      simpleHttpCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "not save response if response cannot determine expiration" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val simpleHttpCache = new SimpleHttpCache(store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.Accepted, entity = "<response>")
      simpleHttpCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "not save response if response must be validated" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val simpleHttpCache = new SimpleHttpCache(store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
        .withHeaders(HttpHeaders.`Cache-Control`.apply(CacheDirectives.`no-cache`))
      simpleHttpCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|<cacheKey>")
      responseFuture must be_==(None).await
    }

    "delete cache entries if the request is not safe" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val simpleHttpCache = new SimpleHttpCache(store)
      val getRequest = ParsedRequest.parse(
        new HttpRequest(method=HttpMethods.GET, uri = Uri("http://example.com"))
      )
      val postRequest = ParsedRequest.parse(
        new HttpRequest(method=HttpMethods.POST, uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
      store.setAdd("contenttypes|<cacheKey>", MediaTypes.`application/octet-stream`.toString())
      store.keySet("response|<cacheKey>", response)

      val responseFuture = simpleHttpCache.lookup(getRequest, "<cacheKey>")
      responseFuture must be_==(response).await

      simpleHttpCache.safeSave(postRequest, response, "<cacheKey>")
      val errorFuture = simpleHttpCache.lookup(getRequest, "<cacheKey>")
      errorFuture must throwAn(CacheLookupError).await
    }

    "save response" in {
      val store = new MemoryStore("<storeId>", 1000, 1000, 1000)
      val simpleHttpCache = new SimpleHttpCache(store)
      val parsedRequest = ParsedRequest.parse(
        new HttpRequest(uri = Uri("http://example.com"))
      )
      val response = HttpResponse(status = StatusCodes.OK, entity = "<response>")
      simpleHttpCache.safeSave(parsedRequest, response, "<cacheKey>")
      val responseFuture = store.keyGet("response|<cacheKey>")
      responseFuture must be_==(Some(response)).await
    }
  }

}
