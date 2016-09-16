---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: domains
activeSubPill: upstream_custom
---
# Custom Upstream Discovery

You can implement your own Upstream Service Discovery mechanism.  Write an actor class, make it available on the class path, and
set the config to the [fully qualified class name](https://docs.oracle.com/javase/tutorial/java/package/namingpkgs.html).

{% highlight javascript %}
// Domain Object
{
  // The fully qualified class name of the actor to load
  upstream-watcher: "org.example.CustomUpstreamWatcherActor"
}

{% endhighlight %}

The class to be loaded must implement `shield.actors.config.upstream.UpstreamWatcher`, and have a constructor that takes
one parameter of type `com.typesafe.config.Config` which will be the parsed Domain Object.

{% highlight scala %}

trait UpstreamWatcher { self: Actor =>
  val settings = Settings(context.system)
}

{% endhighlight %}

To send an updated list of upstream services to Shield, the actor must send a `shield.actors.config.UpstreamAggregatorMsgs.DiscoveredUpstreams`
message to its parent actor.


{% highlight scala %}

case class DiscoveredUpstreams(services: Map[ServiceLocation, ServiceDetails])

{% endhighlight %}
