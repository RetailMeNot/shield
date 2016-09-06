---
layout: start
title: Request Lifecycle
activeTab: start
activePill: lifecycle
activeSubPill: local_endpoints
---
# Shield Endpoints

There are a few endpoints that Shield will respond to directly.

### Swagger Doc Endpoint

When a request comes in for `GET /spec` (the path can be changed via config), Shield will return a Swagger JSON object
that represents all of the combined upstream endpoints.

### Metrics Endpoint

When a request comes in for `GET /metrics` (the path can be changed via config), Shield will return a JSON object with
metrics on it's performance.

### Healthcheck Endpoint

When a request comes in for `GET /healthcheck` (the path can be changed via config), Shield will return a JSON object
describing the health of the upstream services for that domain.

    {
      "listeners": {
        "pending": [],
        "ready": ["consoleLogger"]
      },
      "buildInfo": {
        "builtAtMillis": "1462987769832",
        "name": "Shield",
        "scalaVersion": "2.11.7",
        "version": "0.2-SNAPSHOT",
        "sbtVersion": "0.13.8",
        "builtAtString": "2016-05-11 17:29:29.832"
      },
      "host": "THOR",
      "middleware": {
        "pending": [],
        "ready": []
      },
      "upstreams": []
    }

When starting up, this endpoint will initially return a `503 Service Unavailable` status.  Once Shield receives confirmation
that all the listeners, middleware, and upstreams have been initialized and can communicate successfully, this endpoint
will start returning a `200 OK` status.  From that point on, even if current upstreams start having problems or new
upstreams can't be initialized, this endpoint will continue to return a `200 OK` because Shield itself is operational.
