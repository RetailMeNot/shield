---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: kvstores
activeSubPill: breaker
---
# Circuit Breaker Wrapper

Wraps another KVStore in a [Circuit Breaker](http://doc.akka.io/docs/akka/2.4.2/common/circuitbreaker.html) to help
shed load if it becomes unresponsive.

{% highlight javascript %}
// Domain Object
{
  kvstores: {

    // The identifier of the KVStore
    redis_cb: {

        // The id of the KVStore to wrap
        backer: shared_redis

        // Number of failures before opening the circuitbreaker
        maxFailures: 32

        // Duration after which a call becomes a failure
        callTimeout: 500 ms

        // Time before the circuit breaker enters the half-open state after opening
        resetTimeout: 2 s
    }

    shared_redis: {
      type: redis
      url: "redis://redis.example.com:6379"
    }
  }
}

{% endhighlight %}
