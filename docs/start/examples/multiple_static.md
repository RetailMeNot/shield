---
layout: start
title: Multiple Static
activeTab: start
activePill: examples
activeSubPill: multiple_static
---
# Multiple Static HTTPS Upstreams

Here we add another upstream in addition to example.com.  Shield will listen to requests to "api.example.org" and use
the Swagger2 Documentation from each upstream to figure out where to forward the request.

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
        {"serviceType": "swagger2", "serviceLocation": "https://example.com"},
        {"serviceType": "swagger2", "serviceLocation": "https://example.net"}
      ]
    }
  ]
}
{% endhighlight %}
