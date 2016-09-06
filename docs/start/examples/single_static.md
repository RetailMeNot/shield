---
layout: start
title: Single Static
activeTab: start
activePill: examples
activeSubPill: single_static
---
# Single Static HTTPS Upstream

A single static upstream with no listeners and no middleware.  In this config, Shield acts as a very basic reverse proxy,
listening to requests to "api.example.org" and forwarding them to the endpoints described in example.com's Swagger2
Documentation.

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
        {"serviceType": "swagger2", "serviceLocation": "https://example.com"}
      ]
    }
  ]
}
{% endhighlight %}
