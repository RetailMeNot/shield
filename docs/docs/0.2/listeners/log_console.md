---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: listeners
activeSubPill: log_console
---
# Console Logger

A listener that writes the request and response logs to stdout.

{% highlight javascript %}
// Domain Object
{
  domain-name: "example.com"
  listeners: [
    {id: log_console, builder: ConsoleLogBuilder}
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
    log_console: {
        // no settings, but the object needs to be present.
    }
  }
}

{% endhighlight %}


#### Console Output

    [INFO] [09/05/2016 20:57:27.274] [on-spray-can-akka.actor.default-dispatcher-2] [akka://on-spray-can/user/shield/domain-watcher/config-watcher-localhost/consoleLogger-lbuilder/$a] {"responding_host":"http://thor:9001","method":"GET","@timestamp":"2016-09-05T20:57:27.214-05:00","cache_status":"nocache","middleware_time":{},"path":"http://localhost:9001/healthcheck","response_headers":{},"response_size":387,"responding_service":"shield","request_headers":{"client-address":"0:0:0:0:0:0:0:1","User-Agent":"curl/7.47.1"},"response_status":200,"shield_host":"http://thor:9001","template":"/healthcheck","overall_time":24}

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
