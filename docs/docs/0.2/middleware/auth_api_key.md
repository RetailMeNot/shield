---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: middleware
activeSubPill: auth_api_key
---
# API Key Authentication

A middleware that filters request based on a white-list of API keys.  If the appropriate header is not present,
the middleware returns a `401 Unauthorized` response.  If the header is present but does not contain a valid key,
the middleware returns a `403 Forbidden` response.

{% highlight javascript %}
// Domain Object
{
  domain-name: "example.com"
  middleware-chain: [
    {id: auth, sla: 20 ms, builder: ApiKeyAuthBuilder}
  ]

  middleware {

    // id used in the middleware-chain list
    auth {

      // The header that should contain the API key
      header-name: "some-header"

      // The set of authorized api keys
      allowed: [
        "key1",
        "key2"
      ]

      // Whether or not the API Keys should be treated as case-sensitive
      case-sensitive: true
    }
  }
}

{% endhighlight %}
