---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: domains
activeSubPill: upstream_static
---
# Static Upstream Discovery

Uses a static list of pre-configured upstreams for Shield.  This config cannot be changed at runtime.

If any of the Upstream Service Objects are invalid, a warning is logged for each invalid object and Shield will continue
on using the valid entries.

{% highlight javascript %}
// Domain Object
{
  upstream-watcher: StaticUpstreamWatcher

  // The list of Upstream Service Objects
  upstreams: [
    {...}
  ]
}

{% endhighlight %}

For details about the Upstream Service Object, see [Upstream Services]({{ site.baseurl }}/docs/0.2/upstream/).
