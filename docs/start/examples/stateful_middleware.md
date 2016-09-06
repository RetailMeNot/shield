---
layout: start
title: Stateful Middleware
activeTab: start
activePill: examples
activeSubPill: stateful_middleware
---
# Stateful Middleware using Key Value Store

Sometimes middleware need to keep track of state to be useful.  In this example, we extend the
[Middleware]({{ site.baseurl }}/start/examples/multiple_static) example with another middleware that requires a Key Value
Store.

Now if a client sends more than 10 requests in one second, Shield will automatically send a `429 Too Many Requests`
response.  Note that we've placed the rate limiting middleware in front of the auth middleware, so that even requests
that do not have the appropriate authentication will be rate limited.

### application.conf

{% highlight javascript %}
shield {
  domain-watcher: StaticDomainWatcher
  domains: [
    {
      domain-name: "api.example.org"
      middleware-chain: [
        {id: rate_limit, sla: 50ms, builder: BucketRateLimitBuilder},
        {id: auth, sla: 1s, builder: ApiKeyAuthBuilder}
      ]
      middleware: {
        auth: {
          header-name: "auth-key",
          allowed: "secret_key",
          case-sensitive: false
        }
        rate_limit: {
          bypass-header: "",
          calls-per: 10
          per-seconds: 1
          kvstore: "redis_kv"
        }
      }
      listeners: []
      listener-config: {}
      kvstores: {
        redis_kv: {
          type: "redis"
          url: "redis://127.0.0.1:6379"
        }
      }
      ignoreExtensions: []
      log: {
        request-headers: []
        response-headers: []
      }
      upstream-weighting: {
        step-count: 10
        step-duration: 3 seconds
      }

      upstream-watcher: StaticUpstreamWatcher
      upstreams: [
        {"serviceType": "swagger2", "serviceLocation": "https://example.com"},
        {"serviceType": "swagger2", "serviceLocation": "https://example.net"}
      ]
    }
  ]
}
{% endhighlight %}
