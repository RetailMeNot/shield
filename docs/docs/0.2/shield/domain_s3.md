---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: shield
activeSubPill: domain_s3
---
# AWS S3 Domain Discovery

Continuously polls a file in S3 that contains a list of [Domain Objects]({{ site.baseurl }}/docs/0.2/domains/).  If the
file is invalid, the `S3DomainWatcher` treats it as if there was no update.


{% highlight javascript %}

shield {
  domain-watcher: S3DomainWatcher

  s3-domain-watcher {

    // The bucket name that contains the config file
    bucket-name: "s3-domains-bucket-name"

    // The key within the bucket to the config file.
    config-filename: "domains.conf"

    // The duration between polling for file updates
    refresh-interval: 30s
  }
}

{% endhighlight %}


### S3 File

{% highlight javascript %}
{
  domains: [
    // A list of domain objects go here
    { ... },
    { ... }
  ]
}

{% endhighlight %}

### Authentication

Shield uses the AWS Java SDK for communicating with AWS Services.  See the documentation on how to set up Shield with
[AWS Credentials](http://docs.aws.amazon.com/java-sdk/latest/developer-guide/credentials.html).
