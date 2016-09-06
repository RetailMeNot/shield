---
layout: start
title: Multiple Static
activeTab: start
activePill: examples
activeSubPill: blue_green
---
# Blue Green Deployments

This is effectively the same configuration as our [Dynamic Upstreams]({{ site.baseurl }}/start/examples/dynamic) example.
The only difference is that api-blue.example.com and api-green.example.com have the same service deployed to them.  Shield
will load balance requests across the two upstreams according to their weight.

Editing the weight in `shield_upstreams.conf` will prompt Shield to smoothly transition their weight from it's current
value to it's new target value (NB: The weights do not need to sum to 100).

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
  {"serviceType": "swagger2", "serviceLocation": "https://api-blue.example.com", weight: 90},
  {"serviceType": "swagger2", "serviceLocation": "https://api-green.example.com", weight: 10}
]
{% endhighlight %}
