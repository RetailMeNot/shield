---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: swagger
---
# Swagger Vendor Extensions

The Swagger Specification allows for [Vendor Extensions](http://swagger.io/specification/#vendorExtensions) to provide
functionality above and beyond the normal.  Shield takes advantage of these to let upstream service influence the behavior
of Shield.

### [Operation Object](http://swagger.io/specification/#operationObject) Extensions

* `x-disabled-middleware`
  * List[String]
  * A list of middleware identifiers to skip when a request comes in to this endpoint

* `x-disabled-listeners`
  * List[String]
  * A list of listener identifiers to skip when a request comes in to this endpoint
