---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: shield
activeSubPill: domain_static
---
# Static Domain Discovery

Uses a static list of pre-configured domains for Shield.  This config cannot be changed at runtime.

{% highlight javascript %}

shield {
  domain-watcher: StaticDomainWatcher

  domains: [
    // A list of domain objects go here
    { ... },
    { ... }
  ]
}

{% endhighlight %}

For details about the Domain Object, see [Domain Config]({{ site.baseurl }}/docs/0.2/domains/).
