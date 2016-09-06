---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: kvstores
---
# Key-Value Stores Configuration

Shield relies on key value stores for maintaining any of state for middleware.

{% highlight javascript %}
// Domain Object
{
  kvstores: {

    // The identifier of the KVStore
    memory_kvs: {

      // The type of the KVStore.  See below for options
      type: memory

      // Configuration options for the given KVStore type
      ???: ...
    }
  }
}

{% endhighlight %}

#### Key Value Store Types

**Config Value**: `memory`

Uses an in-memory key value store for persisting state.  [See more.]({{ site.baseurl }}/docs/0.2/kvstores/memory/)

**Config Value**: `redis`

Uses Redis as an external key value store for persisting state.  [See more.]({{ site.baseurl }}/docs/0.2/kvstores/redis/)

**Config Value**: `breaker`

Wraps another key value store in the circuit breaker pattern.  [See more.]({{ site.baseurl }}/docs/0.2/kvstores/breaker/)
