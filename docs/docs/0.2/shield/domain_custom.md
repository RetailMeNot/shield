---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: shield
activeSubPill: domain_custom
---
# Custom Domain Discovery

You can implement your own Domain Discovery mechanism.  Write an actor class, make it available on the class path, and
set the config to the [fully qualified class name](https://docs.oracle.com/javase/tutorial/java/package/namingpkgs.html).

{% highlight javascript %}

shield {
  // The fully qualified class name of the actor to load
  domain-watcher: "org.example.CustomDomainWatcherActor"
}

{% endhighlight %}

The class to be loaded must implement `shield.actors.config.domain.DomainWatcher`, and have a constructor that takes
no parameters.

{% highlight scala %}

trait DomainWatcher extends Actor {
  val settings = Settings(context.system)
}

{% endhighlight %}

To send an updated list of domains to Shield, the actor must send a `shield.actors.ShieldActorMsgs.DomainsUpdated`
message to it's parent actor.


{% highlight scala %}

case class DomainsUpdated(domains: Map[String, DomainSettings])

{% endhighlight %}
