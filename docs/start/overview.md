---
layout: start
title: Getting Started
activeTab: start
activePill: overview
---

# What Is Shield?

Shield is the mortar between the brickwork of your service oriented architecture, the glue that fits services smoothly 
together. It gathers multiple services into a single domain and adds a production ready varnish on top in the form of
HTTP middleware.

Shield is ostensibly another implementation of the API Gateway pattern, but provides a unique combination of configurable
middleware, upstream service aggregation, and intelligent request routing.

For those unfamiliar with the API Gateway pattern, the gateway acts as the primary entrypoint into a microservice 
ecosystem.  Depending on how it's configured, both internal and external clients may send requests to the gateway for 
delivery to the appropriate upstream service.

## Shield: Service Discovery 

Automated tools make life much simpler for the infrastructure engineer.  Shield integrates with existing service discovery 
mechanisms to detect new deployments and scaling events in order to automatically start sending traffic to them.

<img src="{{ site.baseurl }}/assets/images/service_discovery.gif" class="img-responsive">

As these new hosts come online, Shield will retrieve their [Swagger Documentation](http://swagger.io) that describes 
what endpoints they are capable of serving.  Shield will intelligently route traffic to that host if it's actually 
capable of handling the request.

## Shield: The Router

When Shield is in front of multiple unique services, it needs to know where to forward the request on to.  To
solve this routing problem, Shield builds a routing table for all the upstream endpoints that were discovered
during the Service Discovery phase.  The routing table then maps a request to a service by looking at the HTTP method, 
path, and content type of the request and desired response.

<img src="{{ site.baseurl }}/assets/images/routing.gif" class="img-responsive">

This routing is fast, on the order of 0.01ms, even when choosing among hundreds of endpoints exposed by the upstream
services.  

Shield is smart when multiple upstream endpoints could be used to satisfy the request, and uses the [most
specific endpoint]({{ site.baseurl }}/start/lifecycle/endpoint_sorting) possible when deciding which upstream to proxy
to.

## Shield: The Load Balancer

Shield will load balance requests on a per endpoint basis. This means a request will be balanced across multiple 
instances of the same service and across different services that advertise the same endpoint.

<img src="{{ site.baseurl }}/assets/images/load_balancing.gif" class="img-responsive">

Shield has weighted load balancing logic, and will smoothly transition between weight values.  In the example above,
"Service B" could be the next version of "Service A".  Updating the weight on Service B would smoothly transition the 
load to the new deploy, giving it ample time to scale up if needed.  This is ideal for graceful blue/green deployments.


## Shield: The Proxy

Shield takes advantage of its position as a proxy to help guard the upstream services during times of trouble.  Each 
upstream service is guarded by a [Circuit Breaker](http://doc.akka.io/docs/akka/2.4.4/common/circuitbreaker.html) so that
Shield will automatically re-route or shed traffic if the upstream service is experiencing consistent failures or slowdowns.  
Additionally, each endpoint on the upstream service has its own dedicated Circuit Breaker so one endpoint that 
relies on a flaky resource does not bring down the whole service.  

In the example below, **Service B** has experienced a failure and its circuit breaker has opened, resulting in Shield 
immediately returning a failure response.

<img src="{{ site.baseurl }}/assets/images/proxying.gif" class="img-responsive">

While the lingua franca of the web is HTTP, it's very common to use a different method for communicating between the 
services in your architecture.  Shield supports each upstream service having a different transport mechanism.  The 
incoming request will be translated and sent across the wire in the dialect that the service supports.  Shield currently
handles HTTP, HTTPS, and AWS Lambda, with more to come in the near future.

Shield will also handle the compression of the requests and responses, inflating or deflating them based on the 
preferences of the clients and the services.


## Shield: The Middleware Engine

Under the hood, Shield uses [Akka's](http://akka.io) Actor model to power request and response middleware.  These 
middleware can perform common operations like caching, rate limiting, and authentication.  These middleware are all 
driven by Shield configuration, including the ability to set timeouts before which the middleware must respond.  If the 
middleware actors does not respond in time, the request processor will auto advance to the next middleware.

<img src="{{ site.baseurl }}/assets/images/middleware.gif" class="img-responsive">
<img src="{{ site.baseurl }}/assets/images/actor_legend.png" class="img-responsive">

Listeners are similar to middleware, but they receive the request and response pair after the response has already been
sent to the client.  This allows listeners to asynchronously do their processing without impacting the client experience.
Common use cases here include logging and analytics.  Like their middleware brethren, listeners are controlled via 
configuration.


## Shield: Production Ready

Shield has been powering RetailMeNot's API since early 2016 and has proven to be stable and fast across billions of 
requests.

From observation of production deployments and load testing, Shield performance traits are quite impressive:

1. Fast (~5ms overhead)
1. Scalable (1 host: 20,000 req/s)
1. Reliable (Akka's "Let It Crash" mentality)
