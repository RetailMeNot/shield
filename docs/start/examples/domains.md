---
layout: start
title: Domains
activeTab: start
activePill: examples
activeSubPill: domains
---
# Multiple Domains

Shield can easily power multiple domains with different middleware, listeners, and upstream services.

### application.conf

{% highlight javascript %}
shield {
  domain-watcher: StaticDomainWatcher
  domains: [
    {
      domain-name: "api.example.org"
      middleware-chain: []
      middleware: {}
      listeners: []
      listener-config: {}
      kvstores: {}
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
        {"serviceType": "swagger2", "serviceLocation": "http://10.10.0.158:8080"}
      ]
    },
    {
      domain-name: "example.org"
      middleware-chain: []
      middleware: {}
      listeners: []
      listener-config: {}
      kvstores: {}
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
        {"serviceType": "swagger2", "serviceLocation": "http://10.0.0.28:9000"}
      ]
    }
  ]
}
{% endhighlight %}
