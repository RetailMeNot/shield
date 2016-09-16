---
layout: start
title: Dynamic Upstreams
activeTab: start
activePill: examples
activeSubPill: dynamic
---
# Dynamic Upstreams

Here we change the "upstream-watcher" setting to watch a file hosted in AWS S3.  When this file is updated, Shield will
add and remove upstreams as needed, updating its internal routing table with the Swagger Documentation pulled from each
service.

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

      upstream-watcher: S3UpstreamWatcher
      s3-upstream-watcher: {
        bucket-name: "s3-example-bucket"
        config-filename: "shield_upstreams.conf"
        refresh-interval: 30s
      }
    }
  ]
}
{% endhighlight %}

### shield_upstreams.conf
{% highlight javascript %}
[
  {"serviceType": "swagger2", "serviceLocation": "https://example.com"},
  {"serviceType": "swagger2", "serviceLocation": "https://example.net"}
]
{% endhighlight %}

