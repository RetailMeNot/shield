---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: domains
activeSubPill: upstream_s3
---
# AWS S3 Upstream Discovery

Continuously polls a file in S3 that contains a list of [Upstream Service Objects]({{ site.baseurl }}/docs/0.2/upstream/).  If the
file is invalid, the `S3UpstreamWatcher` treats it as if there was no update.


{% highlight javascript %}
// Domain Object
{
  upstream-watcher: S3UpstreamWatcher

  s3-upstream-watcher {

    // The bucket name that contains the config file
    bucket-name: "s3-upstreams-bucket-name"

    // The key within the bucket to the config file.
    config-filename: "domains.conf"

    // The duration between polling for file updates
    refresh-interval: 30s
  }
}
{% endhighlight %}


### S3 File

{% highlight javascript %}
[
  // Upstream Service Objects
  {...},
  {...}
]

{% endhighlight %}

### Authentication

Shield uses the AWS Java SDK for communicating with AWS Services.  See the documentation on how to set up Shield with
[AWS Credentials](http://docs.aws.amazon.com/java-sdk/latest/developer-guide/credentials.html).
