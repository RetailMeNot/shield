---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: kvstores
activeSubPill: memory
---
# In-memory Key Value Store

A fast in-memory key value store using Google's LRU Concurrent Linked Hashmap.

{% highlight javascript %}
// Domain Object
{
  kvstores: {

    // The identifier of the KVStore
    in_memory: {

      type: memory

      // Limits how many Sets can be stored
      maxHashCapacity: 10000

      // Limits how many Key Value Pairs can be stored
      maxKeyCapacity: 10000

      // Limits how many Rate Limit entries can be stored
      maxLimitCapacity: 10000
    }
  }
}

{% endhighlight %}
