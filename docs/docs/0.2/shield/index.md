---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: shield
---
# Base Shield Configuration

All Shield configuration is done using the [HOCON language](https://github.com/typesafehub/config/blob/master/HOCON.md),
same as the underling Spray and Akka libraries.  This allows one configuration mechanism to control all aspects of Shield's
operation.

To supply your config, create an `application.conf` file and set the `config.file` Java property to the location of your
config file.  For more details, see [HOCON's standard behavior for loading configuration](https://github.com/typesafehub/config#standard-behavior).

{% highlight javascript %}

shield {
  // The name used to represent the local service when doing request logging
  service-name: "shield"

  // The name used to represent the local host when doing request logging
  // If this is not set, it falls back to the `HOSTNAME` environment variable,
  // and if that is not set, it falls back to `java.net.InetAddress.getLocalHost.getHostName`
  hostname: // no default value

  // The port that Shield listens on for incoming connections
  port: 8080

  paths {
    // The path that Shield uses for its healthcheck endpoint
    healthcheck: "/healthcheck"

    // The path that Shield uses for its healthcheck endpoint
    metrics: "/metrics"

    // The path that Shield uses when retrieving Swagger1 Documentation from an
    // upstream service
    swagger1: "/api-docs"

    // The path that Shield uses when retrieving Swagger2 Documentation from an
    // upstream service
    swagger2: "/spec"
  }

  // The number of proxies in front of Shield.  This is used when calculating the
  // value to use when setting the `Client-Address` header.  Compare this to the
  // `Remote-Address` header, which Spray sets to the address of the actual connection.
  //
  // If this value is 0, `Client-Address` is the same as `Remote-Address`
  // If this value is > 0, `Client-Address` is set to the address specified by the
  // `X-Forwarded-For` header, counting from the back
  trust-proxies: 0

  // If this is set to `true`, Shield will update the URI of the request to use the
  // protocol specified by the `X-Forwarded-Proto` header.
  trust-x-forwarded-proto: false

  // Determines which method is used for loading configuration for domains.
  // See options below
  domain-watcher: StaticDomainWatcher

}

{% endhighlight %}

#### Domain Watcher Settings

**Config Value**: `StaticDomainWatcher`

A static set of domains.  [See more.]({{ site.baseurl }}/docs/0.2/shield/domain_static/)

**Config Value**: `S3DomainWatcher`

Continuously polls a file in AWS S3 that contains the domain settings.  [See more.]({{ site.baseurl }}/docs/0.2/shield/domain_s3/)

**Config Value**: _(Fully Qualified Class Name)_

Shield will create an actor of the given class.  This allows an external class to manage domain discovery.
[See more.]({{ site.baseurl }}/docs/0.2/shield/domain_custom/)
