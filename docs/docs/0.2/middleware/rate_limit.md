---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: middleware
activeSubPill: rate_limit
---
# Rate Limiting

A middleware that filters request based how many requests they've made.  It uses the IP Address present in the `Client-Address`
header for grouping requests.  (See the [`trust-proxies`]({{ site.baseurl }}/docs/0.2/shield/) setting if Shield is behind a
proxy.)  If the client has made too many request recently, the middleware returns a `429 Too Many Requests` response.

This implementation groups requests into a bucket of time, rather than a rolling window of time.  As soon as the bucket
expires, clients can immediately make their allowed number of requests again.

{% highlight javascript %}
// Domain Object
{
  domain-name: "example.com"
  middleware-chain: [
    {id: rate_limit, sla: 20 ms, builder: BucketRateLimitBuilder}
  ]

  middleware {

    // id used in the middleware-chain list
    rate_limit {

      // A "secret" header that, if present, allows the request to bypass this middleware.  To disable,
      // set this value to an illegal header name ("" should work).
      bypass-header: "some-header"

      // With the below config, clients are allowed to make 10 requests every 2 seconds.

      // The number of requests allowed from a single client during a bucket period
      calls-per: 10

      // The window of time that a bucket lasts
      per-seconds: 2

      // The identifier of the Key Value Store to persist the rate limiting state
      kvstore: "redis_kv"
    }
  }
}

{% endhighlight %}
