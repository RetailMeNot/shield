---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: domains
---
# Domain Configuration

A Domain Object has the following schema.  Since these objects usually appear in a list, there is no inheritance
from `reference.conf` and therefore no default values.

{% highlight javascript %}
// Domain Object
{
  // The domain name this Domain Object is used for
  domain-name: "example.com"

  // The ordered list of Middleware Objects for this domain
  middleware-chain: [
    {...},
    {...}
  ]
  // Configuration objects for each middleware
  middleware: {
    id: {...}
  }

  // This set of Listener Objects for this domain
  listeners: [
    {...}
  ]
  // Configuration objects for each listener
  listener-config: {
    id: {...}
  }

  // The mapping of Key Value Stores for this domain.
  kvstores: {
    ???: { ... }
  }

  // The set of extensions to ignore on incoming requests.  These extensions are ignored when
  // determining which upstreams can handle a request.
  // Eg: "http://example.com/user/1234.json" will be routed as "http://example.com/user/1234"
  ignoreExtensions: [
    "json",
    "xml"
  ]

  // This section controls how the domain handles changing weights for upstream services.
  upstream-weighting: {

    // Controls how many steps there are between the initial weight and final weight when
    // changing the weight.
    step-count: 100

    // Controls how long each step lasts when changing the weight
    step-duration: 6 seconds
  }

  // Determines which method is used for discovering upstream microservices.
  // See options below
  upstream-watcher: StaticUpstreamWatcher
}

{% endhighlight %}

#### Middleware Objects

These objects specify the middleware Shield should use when processing a request.  [See more.]({{ site.baseurl }}/docs/0.2/middleware)

#### Listener Objects

These objects specify the listeners Shield should notify after processing a request.  [See more.]({{ site.baseurl }}/docs/0.2/listeners)

#### Key Value Stores

Shield uses Key Value Stores for persisting any state.  [See more.]({{ site.baseurl }}/docs/0.2/kvstores)

#### Upstream Watcher Settings

**Config Value**: `StaticUpstreamWatcher`

A static set of upstream services.  [See more.]({{ site.baseurl }}/docs/0.2/domains/upstream_static/)

**Config Value**: `S3UpstreamWatcher`

Continuously polls a file in AWS S3 that contains the upstream services.  [See more.]({{ site.baseurl }}/docs/0.2/domains/upstream_s3/)

**Config Value**: _(Fully Qualified Class Name)_

Shield will create an actor of the given class.  This allows an external class to manage upstream service discovery.
[See more.]({{ site.baseurl }}/docs/0.2/domains/upstream_custom/)
