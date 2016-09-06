---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: listeners
activeSubPill: log_fluentd
---
# Fluentd Logger

A listener that writes the request and response logs to the [FluentD in_http collector](http://docs.fluentd.org/articles/in_http).
For efficiency, it batches multiple logs together.

{% highlight javascript %}
// Domain Object
{
  domain-name: "example.com"
  listeners: [
    {id: log_fluentd, builder: FluentdHttpBuilder}
  ]

  log: {
    // A list of request headers to capture in the logging output
    request-headers: [
      "user-agent",
      "client-address"
    ]

    // A list of response headers to capture in the logging output
    response-headers: [
      "content-type"
    ]
  }

  listener-config: {

    // id used in the listener list
    log_fluentd: {

        // the host (and port) of the fluentd listener
        host: "localhost:8888"

        // To bleed off pressure if Fluentd is backed up, this limits the number of outstanding
        // requests to Fluentd. If there are more than `max-outstanding` requests to FluentD,
        // future requests are dropped until one of the in flight requests completes.
        max-outstanding: 10

        // The logger will wait until there are `buffer-size` entries ready to go, or will
        // flush every 100 ms to Fluentd
        buffer-size: 100
    }
  }
}

{% endhighlight %}


#### Json Log

{% highlight javascript %}
{
  "responding_host": "http://thor:9001",
  "method": "GET",
  "@timestamp": "2016-09-05T20:57:27.214-05:00",
  "cache_status": "nocache",
  "middleware_time": {},
  "path": "http://localhost:9001/healthcheck",
  "response_headers": {"content-type": "application/json; charset=UTF-8"},
  "response_size": 387,
  "responding_service": "shield",
  "request_headers": {"client-address":"0:0:0:0:0:0:0:1","User-Agent":"curl/7.47.1"},
  "response_status": 200,
  "shield_host": "http://thor:9001",
  "template": "/healthcheck",
  "overall_time": 24
}
{% endhighlight %}
