---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: listeners
activeSubPill: custom
---
# Custom Listener

You can implement your own listener builder.  Write an actor class, make it available on the class path, and
set the config to the [fully qualified class name](https://docs.oracle.com/javase/tutorial/java/package/namingpkgs.html).

{% highlight javascript %}
// Domain Object
{
  domain-name: "example.com"
  listeners: [
    {id: my_listener, builder: "com.example.MyListenerBuilder"}
  ]

  listener-config {

    // id used in the listeners list
    my_listener {

      // The settings in this object will be provided to the listener builder
      ???: ...
    }
  }
}

{% endhighlight %}

The class to be loaded must implement `shield.actors.config.listener.ListenerBuilder`, and have a constructor that takes
two parameters:

* `id: String` - The id of the listener to be built
* `settings: shield.config.DomainSettings` - The domain settings, which provides access to the settings for the listener to be built

{% highlight scala %}

trait ListenerBuilder extends Actor {
  val settings = Settings(context.system)
}

{% endhighlight %}

To register the listener with Shield, the builder actor must send a `shield.actors.config.ConfigWatcherMsgs.ListenerUpdated`
message to it's parent actor.  It can do this multiple times if it wants to change actors used by Shield.  If you're swapping
actors, you should wait for Spray's global request timeout to elapse before killing the old listener actor, to allow any
in flight requests to be drained.


{% highlight scala %}

case class ListenerUpdated(id: String, listener: ActorRef)

{% endhighlight %}

The actor that gets registered as a listener will receive `shield.actors.RequestProcessorCompleted` messages.  It is
then free to whatever it wants.

{% highlight scala %}

// Incoming message
case class RequestProcessorCompleted(
  completion: ResponseCompleted,
  overallTiming: Long,
  middlewareTiming: Map[String, Long]
)

case class ResponseCompleted(
  request: HttpRequest,
  details: ResponseDetails
)

case class ResponseDetails(
  serviceLocation: ServiceLocation,
  serviceName: String,
  template: EndpointTemplate,
  explicitCacheParams: Option[Set[Param]],
  response: HttpResponse
)

{% endhighlight %}


