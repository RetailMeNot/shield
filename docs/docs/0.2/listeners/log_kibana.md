---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: listeners
activeSubPill: log_kibana
---
# Kibana/ElasticSearch Logging

A listener that writes the request and response logs to the [ELK Stack](https://www.elastic.co/webinars/introduction-elk-stack).
For efficiency, it batches multiple logs together.

A helper script is provided at `scripts/kibana_index_mapping.sh` for setting up the Index Mappings in ElasticSearch.

{% highlight javascript %}
// Domain Object
{
  domain-name: "example.com"
  listeners: [
    {id: log_kibana, builder: KibanaBuilder}
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
    log_kibana: {

        // the host (and port) of the ElasticSearch service
        host: "localhost:9200"

        // The log forwarder sends the logs to the index named "{{index-prefix}}-yyyy.MM.dd",
        // mimicking the behavior of LogStash
        index-prefix: "logstash"

        // The "type" to use when writing to ElasticSearch
        type: "access_log"

        // To bleed off pressure if ElasticSearch is backed up, this limits the number of outstanding
        // requests to ElasticSearch. If there are more than `max-outstanding` requests to ElasticSearch,
        // future requests are dropped until one of the in flight requests completes.
        max-outstanding: 10

        // The logger will wait until there are `buffer-size` entries ready to go, or will
        // flush every 100 ms to ElasticSearch
        buffer-size: 100

        // Optional: Signs the request before writing to the AWS ElasticSearch Service
        AWSSigning: {
          // Whether or not to sign the request
          active: true

          // Used in AWS's signing algorithm.  Should pretty much always be "es"
          service: "es"

          // The region that the ElasticSearch service is running in
          region: "us-west-2"
        }
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
