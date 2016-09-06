---
layout: start
title: Request Lifecycle
activeTab: start
activePill: lifecycle
activeSubPill: endpoint_sorting
---

# Endpoint Specificity

What exactly does it mean when we say the Routing Table "finds the most specific endpoint"?  The problem arises when there
are two endpoints that could possibly handle the current request.

For example, Shield receives a request for `GET /user/1234/prefs`.  It knows about two different upstream endpoints:

* `GET /user/{path: .*}`
* `GET /user/{id}/prefs`

Technically, both endpoints could handle this request.  However, a problem arises if these endpoints are declared by
two separate upstream services.  The one that declares the wildcard endpoint (`/user/{.*}`) may not actually handle it
correctly.  Chances are the upstream service was a bit too lax when documenting their endpoints, but Shield still needs
to handle these problems regardless.

To solve this problem, Shield parses the endpoint templates into different segments and sorts them by their specificity.

### Segment Types:

From most specific to least specific.

* Slash Segment: `/`
* Static Segment: `user`
* [Extension Segment](https://tools.ietf.org/html/rfc6570#section-3.2.5): `{.identifier}`
* [String Segment](https://tools.ietf.org/html/rfc6570#section-3.2.2): `{identifier}`
* Regex Segment: `{identifier: regex}`
* [Reserved Segment](https://tools.ietf.org/html/rfc6570#section-3.2.3): `{+identifier}`

If the number segments between two endpoints is different but the overlapping segments have the same specificity,
preference is given to the endpoint with more segments.

If they are still an exact match, Shield compares them by raw string values.
