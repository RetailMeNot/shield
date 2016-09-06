---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: listeners
---
# Listener Configuration

A Listener Object has the following schema.  As long as the `id` is unique, a listener can be present multiple times.

{% highlight javascript %}
// Listener Object
{
  // Unique identifier for this listener.  Used when looking up
  // configuration and reporting metrics.
  id: "console_logger",

  // The actor class used to build the Listener actor.  This allows for separate
  // concerns of managing the listener and implementing the listener.
  // See options below.
  builder: ConsoleLogBuilder
}

{% endhighlight %}

#### Middleware Builder Settings

**Config Value**: `ConsoleLogBuilder`

Creates a Listener actor that writes request/response logs to StdOut.  [See more.]({{ site.baseurl }}/docs/0.2/listeners/log_console/)

**Config Value**: `FluentdHttpBuilder`

Creates a Listener actor that sends request/response logs to Fluentd's HTTP listener.  [See more.]({{ site.baseurl }}/docs/0.2/listeners/log_fluentd/)

**Config Value**: `KibanaBuilder`

Creates a Listener actor that sends request/response logs to an ElasticSearch service for use with Kibana.  [See more.]({{ site.baseurl }}/docs/0.2/listeners/log_kibana/)

**Config Value**: `AlternateUpstreamBuilder`

Creates a Listener that will replay a request to an alternate upstream and log any differences between the original response
and the re-played response.  [See more.]({{ site.baseurl }}/docs/0.2/listeners/diff/)

**Config Value**: _(Fully Qualified Class Name)_

Shield will create an actor of the given class.  This allows an external class to manage a custom listener implementation.
[See more.]({{ site.baseurl }}/docs/0.2/listeners/custom/)
