---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: middleware
---
# Middleware Configuration

A Middleware Object has the following schema.  As long as the `id` is unique, a middleware can be re-used multiple times.
For example, one bucket ratelimiter to limit burst requests per second, and another to limit requests per day.

{% highlight javascript %}
// Middleware Object
{
  // Unique identifier for this middleware.  Used when looking up
  // configuration and reporting metrics.
  id: "ratelimit",

  // The duration the middleware actor must respond within, otherwise the
  // middleware will be skipped for that request.
  // If a middleware must not be skipped, set this timeout to be greater
  // than Spray's timeout value
  sla: 20 ms,

  // The actor class used to build the Middleware actor.  This allows for separate
  // concerns of managing the middleware and implementing the middleware.
  // See options below.
  builder: BucketRateLimitBuilder
}

{% endhighlight %}

#### Middleware Builder Settings

**Config Value**: `ApiKeyAuthBuilder`

Creates a Middleware actor that does authentication based off an API Key header.  [See more.]({{ site.baseurl }}/docs/0.2/middleware/auth_api_key/)

**Config Value**: `BucketRateLimitBuilder`

Creates a Middleware actor that does rate limiting by grouping requests into buckets.  [See more.]({{ site.baseurl }}/docs/0.2/middleware/rate_limit/)

**Config Value**: `ResponseCacheBuilder`

Creates a Middleware actor that caches and serves responses.  [See more.]({{ site.baseurl }}/docs/0.2/middleware/cache/)

**Config Value**: _(Fully Qualified Class Name)_

Shield will create an actor of the given class.  This allows an external class to manage a custom middleware implementation.
[See more.]({{ site.baseurl }}/docs/0.2/middleware/custom/)
