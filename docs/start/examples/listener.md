---
layout: start
title: Listeners
activeTab: start
activePill: examples
activeSubPill: listener
---
# Adding Listeners

In the [Stateful Middleware]({{ site.baseurl }}/start/examples/stateful_middleware) example, we set up Shield to start
doing some heavy lifting.  However, it's hard to appreciate our work if we can't keep track of what Shield has done.

To fix that, let's add a listener that write request/response log information to stdout.  We've also updated Shield's
logger to capture the `User-Agent` header of the request so we can see what devices use our site.

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
      listeners: [
        {id: log_console, builder: ConsoleLogBuilder}
      ]
      listener-config: {
        log_console: {
          // ConsoleLogBuilder doesn't take any config
        }
      }
      kvstores: {
        redis_kv: {
          type: "redis"
          url: "redis://127.0.0.1:6379"
        }
      }
      ignoreExtensions: []
      log: {
        request-headers: ["User-Agent"]
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
