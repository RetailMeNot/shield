---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: listeners
activeSubPill: diff
---
# Upstream Diff

A listener that will replay a request to an alternate upstream.  If the the original response and the re-played response
are different, the request and the two responses are logged to AWS S3.

Only requests that can be handled by the alternate upstream are sent to the alternate upstream.

This is a handy utility if you're rewriting one of your upstream services and want to make sure they have the same behavior.

{% highlight javascript %}
// Domain Object
{
  domain-name: "example.com"
  listeners: [
    {id: upstream_diff, builder: AlternateUpstreamBuilder}
  ]

  listener-config: {

    // id used in the listener list
    upstream_diff: {

      // The serviceType of the alternate upstream.  See `Upstream Services` for details
      serviceType: "swagger2"

      // The serviceLocation of the alternate upstream.  See `Upstream Services` for details
      serviceLocation: "https://example.com"

      // Every "1 in {{freq}}" requests will be sent to the alternate upstream.
      // If freq == 1, 100% of matching requests will be sent to the alternate upstream
      // If freq == 10, 10% of matching requests will be sent to the alternate upstream
      freq: 10

      // The S3 bucket to write the diffs to
      bucket: "s3-upstream-diff-bucket"

      // The prefix within the bucket to write the diff to
      folder: "/"
    }
  }
}

{% endhighlight %}


### Authentication

Shield uses the AWS Java SDK for communicating with AWS Services.  See the documentation on how to set up Shield with
[AWS Credentials](http://docs.aws.amazon.com/java-sdk/latest/developer-guide/credentials.html).
