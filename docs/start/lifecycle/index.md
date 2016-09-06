---
layout: start
title: Request Lifecycle
activeTab: start
activePill: lifecycle
---

# Request Lifecycle

### 1) Determine the Domain

Shield determines the domain of the request by looking at the `Host` header, or if the header is not present, the domain
of the URI.

### 2) Create a RequestProcessor

Shield creates a `RequestProcessor` actor for the request with the current configuration of the domain determined in step
one.  This includes the current routing table, middleware, listeners, and the proxy actors for each upstream service.

This means each request receives their own actor, allowing Shield to take advantage of Akka's scaling and reliability
mechanisms.  Any unexpected error is isolated to the one request, and will not affect any other requests in flight.

### 3) Pre-Processing

The Request Processor prepares the request for processing by making several alterations.

<div class="panel panel-info">
  <div class="panel-heading">Future Plans</div>
  <div class="panel-body">
    In the future, these hard coded steps will be updated to become dynamic driven via configuration.
  </div>
</div>

* The `X-Forwarded-For` header is updated with the current connection's IP.
* The `Client-Address` header is added using the address determined by the `X-Forwarded-For` header
and the `shield.trust-proxies` setting.
* If the `shield.trust-x-forwarded-proto` config option is set to true, the URI is updated with the correct protocol.
* If the `ignoreExtensions` setting for the domain contains the extension of the request, the URI is updated to remove
the extension.

### 4) Routing

The Request Processor runs the request through the domain's Routing Table to determine which upstream services are capable
of handling it.

* Find the [most specific endpoint]({{ site.baseurl }}/start/endpoint_sorting) endpoint that matches the path of the
request (Ignores trailing slash)
    * If no match is found, the router returns a `404 Not Found` response
* Filters possible upstreams by whether or not the support the HTTP Method of the request.
    * Automatically handles `OPTION` requests if no upstream implements one.
    * Does not yet automatically handle `HEAD` requests if no upstream implements one.
    * If no match is found, the router returns a `405 Method Not Allowed` response.
* Filters possible upstreams by whether or not they can consume the body of the request (`Content-Type` header)
    * If the Swagger Doc of the upstream does not specify what they can consume, Shield treats it as if it can consume
      anything
    * If there is no body for the request, this step is skipped.
    * If no match is found, the router returns a `415 Unsupported Media Type` response.
* Filters possible upstreams by whether or not they can produce the requested response type (`Accept` header)
    * If the Swagger Doc of the upstream does not specify what it can produce, Shield treats it as if it can produce
      anything
    * If the request does not include an `Accept` header, the router filters possible upstreams to those that do not
      specify what they can produce
    * If no match is found, the router returns a `406 Not Acceptable` header.

There are a few endpoints that Shield implements locally that will return a response.  The path can be changed via config,
 but they will take precedence over any upstream that declares the same endpoint.

* `GET /healthcheck`
* `GET /metrics`
* `GET /spec`

If the Routing Table returned a response, that response is immediately forwarded to the client.  Otherwise, the Request
Processor continues on to the next step.

### 5) Request Middleware

Based off the destination endpoint selected by the routing table, the Request Process removes any
[disabled middleware]({{ site.baseurl }}/docs/0.2/swagger).  For each active middleware, the Request Processor sends the
current request and waits for the middleware to reply with either a response to send back or a request to send onwards.
Each request middleware has the option to register a Response Middleware for intercepting a response.

If the middleware does not respond within the allotted time, the Request Processor advances on to the next middleware, or
on to the next stage if no middleware remain.

### 6) Upstream Proxy

The Request Processor takes the latest request from the middleware and forwards it on to the
[Host Proxy]({{ site.baseurl }}/start/upstream).  If there are multiple hosts that can satisfy the request, the Request
Processor will apply a weighted round-robin load balancing strategy to select one.

At this point the Host Proxy will prepare the request to be forwarded by updating the `X-Forwraded-For` header and setting
the `Accept-Encoding: gzip` and `Connection: Keep-Alive` headers, and then forward it on using the given transport mechanism
for the selected upstream.

The Host Proxy will also apply [Circuit Breakers](http://doc.akka.io/docs/akka/2.4.2/common/circuitbreaker.html) at both
the host level (50 failures, 10 second timeouts, resets after 10 seconds) and the host/endpoint level (25 failures, 10
second timeouts, resets after 10 seconds).  These breakers will trip if the upstream endpoint takes too long or responds
with a 5XX Server Error.  If the circuit breakers are open, Shield will immediately reply with a `503 Service Unavailable`
response.  If the request takes too long, Shield will reply with a `504 Gateway Timeout` response.

### 7) Response Middleware

Once the Request Processor has received a response, whether from a middleware or from the Host Proxy, it will iterate
through the Response Middleware that were registered during the "Request Middleware" stage.  These middleware have the
chance to take some action and reply with a new response to forward.

If the middleware does not respond within the allotted time, the Request Processor advances on to the next middleware, or
on to the next stage if no middleware remain.

### 8) Response forwarding

The Request Processor prepares the response to be sent to the client by adding the `Via: 1.1 Shield` header and gzipping
the response [if appropriate](https://developer.mozilla.org/en-US/docs/Web/HTTP/Content_negotiation#The_Accept-Encoding_header),
or unzipping the response if necessary.

Once ready, the response is sent to the client.

### 9) Listeners

The Request Processor wraps up by gathering metrics about the various stages and forwards the metrics, the original request,
and the final response on to all registered listeners.  These listeners usually do logging to some other location, but
may also take other actions that don't need to block the forwarding of the response to the client.

Once done with this stage, the Request Process terminates itself.
