---
layout: start
title: Middleware
activeTab: start
activePill: examples
activeSubPill: middleware
---
# Adding Middleware

Here we extend our [Multiple Static Upstreams]({{ site.baseurl }}/start/examples/multiple_static) example with middleware.
Now Shield is more than just a simple proxy, it adds authentication enforcement to incoming requests so that neither
example.com nor example.net need to implement their own authentication.

If any incoming request does not have the `Auth-Key: secret_key` header, the client will receive a `403 Forbidden` response
or the `401 Unauthorized` response as appropriate, and Shield won't forward the request to either upstream service.

For security purposes, you should limit connections to the upstream service to only incoming connections from Shield.
Otherwise there's nothing preventing an unauthenticated request being made directly against the upstream service.

### application.conf

{% highlight javascript %}
shield {
  domain-watcher: StaticDomainWatcher
  domains: [
    {
      domain-name: "api.example.org"
      middleware-chain: [
        {id: auth, sla: 1s, builder: ApiKeyAuthBuilder}
      ]
      middleware: {
        auth: {
          header-name: "auth-key",
          allowed: "secret_key",
          case-sensitive: false
        }
      }
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
