---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: middleware
activeSubPill: cache
---
# Response Caching

A middleware that stores and serves responses in compliance with [RFC-7234](https://tools.ietf.org/html/rfc7234).  The
cache key is determined using the path of the request and filtering query parameters to those specified in the Swagger Documentation
for the endpoint.  The cache is content-aware; that is, it won't attempt to serve a cached XML response if the current
request asks for a JSON response.

<div class="panel panel-info">
  <div class="panel-heading">Incomplete Functionality</div>
  <div class="panel-body">
    This caching middleware does not implement section 4.3.  If a response is stale in the cache, the middleware will
    send the original request on to the upstream instead of trying to validate the stale response with a
    <code>If-Modified-Since</code> or <code>If-Match</code> header.
  </div>
</div>

{% highlight javascript %}
// Domain Object
{
  domain-name: "example.com"
  middleware-chain: [
    {id: http_cache, sla: 20 ms, builder: ResponseCacheBuilder}
  ]

  middleware {

    // id used in the middleware-chain list
    http_cache {

      // The identifier of the Key Value Store to persist the responses
      kvstore: "redis_kv"
    }
  }
}

{% endhighlight %}
