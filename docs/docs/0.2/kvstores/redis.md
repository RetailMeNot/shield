---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: kvstores
activeSubPill: redis
---
# Redis Key Value Store

Uses [Redis](http://redis.io/) as a shared key value store.

{% highlight javascript %}
// Domain Object
{
  kvstores: {

    // The identifier of the KVStore
    shared_redis: {

      type: redis

      // The URL to use when connecting to redis
      url: "redis://redis.example.com:6379"
    }
  }
}

{% endhighlight %}
