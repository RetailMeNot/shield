---
layout: start
title: Alternatives
activeTab: start
activePill: alternatives
---
# Alternatives to Shield

Shield fits into a category of software called an API Gateway.  There are several
alternative implementations, so let's talk about how they're different.

One feature that none of these alternatives appear to have is the concept of Endpoint Discovery:  Shield will 
discover what endpoints an upstream service has and automatically build its routing table accordingly.  In the projects
below, you must explicitly configure each endpoint (if the project handles routing between services at all).

<div class="panel panel-info">
  <div class="panel-heading">Correctness of Comparisons</div>
  <div class="panel-body">
    <p>
        We try to be objective as possible in these comparisons.  However, it's possible that we missed some features in 
        our research or that the project has been updated in the mean time.  
    </p>
    <p>
        If you notice an error in these comparions, please <a href="https://github.com/RetailMeNot/shield/issues">open
        an issue</a> or <a href="https://github.com/RetailMeNot/shield/pulls">submit a pull request.</a>
    </p>
  </div>
</div>


### AWS' API Gateway

[https://aws.amazon.com/api-gateway/](https://aws.amazon.com/api-gateway/)

API Gateway is a managed-only service.  It has a limited set of middleware, and a very manual configuration process.

### API Umbrella

[https://apiumbrella.io/](https://apiumbrella.io/)

API Umbrella has a few baked in pieces of middleware, and uses nginx under the hood for load balancing.  However, it has
very limited routing capabilities: path-prefix or domain-level routing.

### HAProxy

[http://www.haproxy.org/](http://www.haproxy.org/)

HAProxy doesn't have service discovery baked in, requiring an external nanny process to create new configs and reload 
HAProxy.  The logic for generating the config can grow complicated very quickly.

### Kong

[https://getkong.org/](https://getkong.org/)

Kong has a solid middleware story, but assumes there's only one upstream service.  It does not handle routing or
load balancing.

### Nginx

[https://www.nginx.com/](https://www.nginx.com/)

Nginx doesn't have service discovery baked in, requiring an external nanny process to create new configs and reload 
Nginx.  The logic for generating the config can grow complicated very quickly.

### Squid

[http://www.squid-cache.org/](http://www.squid-cache.org/)

Squid doesn't have service discovery baked in, requiring an external nanny process to create new configs and reload 
Squid.  The logic for generating the config can grow complicated very quickly.

### Traefik

[https://traefik.io/](https://traefik.io/)

Traefik has a great service discovery, routing, and load balancing story.  Unfortunately, it does not support any form
of middleware.

### Tyk

[https://tyk.io/](https://tyk.io/)

Tyk has a good middleware and load balancing story. However, it does not have the ability to route requests to different
upstream services.

### VulcanD

[http://vulcand.github.io/](http://vulcand.github.io/)

VulcanD has a great load balancing story and a good routing story. It has the concept of middleware, but you must
rebuild from source after writing your own in Go.
