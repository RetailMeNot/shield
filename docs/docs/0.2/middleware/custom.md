---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: middleware
activeSubPill: custom
---
# Custom Middleware

You can implement your own middleware builder.  Write an actor class, make it available on the class path, and
set the config to the [fully qualified class name](https://docs.oracle.com/javase/tutorial/java/package/namingpkgs.html).

{% highlight javascript %}
// Domain Object
{
  domain-name: "example.com"
  middleware-chain: [
    {id: my_middleware, sla: 20 ms, builder: "com.example.MyMiddlewareBuilder"}
  ]

  middleware {

    // id used in the middleware-chain list
    my_middleware {

      // The settings in this object will be provided to the middleware builder
      ???: ...
    }
  }
}

{% endhighlight %}

The class to be loaded must implement `shield.actors.config.middleware.MiddlewareBuilder`, and have a constructor that takes
two parameters:

* `id: String` - The id of the middleware to be built
* `settings: shield.config.DomainSettings` - The domain settings, which provides access to the settings for the middleware to be built

{% highlight scala %}

trait MiddlewareBuilder extends Actor {
  val settings = Settings(context.system)
}

{% endhighlight %}

To register the middleware with Shield, the builder actor must send a `shield.actors.config.ConfigWatcherMsgs.MiddlewareUpdated`
message to its parent actor.  It can do this multiple times if it wants to change actors used by Shield.  If you're swapping
actors, you should wait for Spray's global request timeout to elapse before killing the old middleware actor, to allow any
in flight requests to be drained.


{% highlight scala %}

case class MiddlewareUpdated(middleware: Middleware)
case class Middleware(name: String, sla: FiniteDuration, actor: ActorRef)

{% endhighlight %}

The actor that gets registered as a middleware will receive `shield.actors.DownstreamRequest` messages.  It then has the
configured `sla` duration to reply to the sender actor with either a `shield.actors.ForwardRequestCmd` message which will
 send the request to either the next middleware or the upstream service, or a
`shield.actors.ForwardResponseCmd` which will start forwarding the response through previous middleware and on back to the
client.

{% highlight scala %}

// Incoming message
case class DownstreamRequest(
  stage: String,
  destination: RoutingDestination,
  request: HttpRequest
)

// Possible replies:
case class ForwardRequestCmd(
  // Set this to the `stage` value on the incoming DownstreamRequest message,
  // otherwise the RequestProcessor will consider your reply "Out of Order" and ignore it
  stage: String,
  request: HttpRequest,
  responseMiddleware: Option[Middleware] = None
) extends MiddlewareResponse

case class ForwardResponseCmd(
  // Set this to the `stage` value on the incoming DownstreamRequest message,
  // otherwise the RequestProcessor will consider your reply "Out of Order" and ignore it
  stage: String,
  details: ResponseDetails
) extends MiddlewareResponse

// Information about the Response
case class ResponseDetails(

  // Used in request/response logging to determine who actually generated the response
  serviceLocation: ServiceLocation,

  // Used in request/response logging to determine who actually generated the response
  serviceName: String,

  // Keeps track of which endpoint (parsed from the Swagger docs) this request was aimed at
  template: EndpointTemplate,

  // Set this to `None` unless you know what you're doing
  explicitCacheParams: Option[Set[Param]],

  // The actual response to forward to the client
  response: HttpResponse
)

{% endhighlight %}

If the Request Processor does not receive the middleware's reply within the allotted SLA, it will ignore it and advance
to the next stage.  You need not worry about implementing the SLA within your middleware.  The Request Processor will
ignore the response from your middleware even if it comes in late.

Your middleware can optionally include a `responseMiddleware` if it replies with a `ForwardRequestCmd`.  The response middleware
will be visited in reverse order after receiving a response from either a later middleware or the upstream service.
This response middleware will receive a `shield.actors.UpstreamResponse` message.  It then has the configured `sla` duration to
reply to the sender actor with a `shield.actors.ForwardResponseCmd` message.

{% highlight scala %}

// Incoming message
case class UpstreamResponse(
  stage: String,
  request: HttpRequest,
  details: ResponseDetails
)

case class ForwardResponseCmd(
  // Set this to the `stage` value on the incoming UpstreamResponse message,
  // otherwise the RequestProcessor will consider your reply "Out of Order" and ignore it
  stage: String,
  details: ResponseDetails
) extends MiddlewareResponse



{% endhighlight %}

Much like with request middleware, if the Request Processor does not receive the middleware's reply within the allotted
SLA, it will ignore it and advance to the next stage.
