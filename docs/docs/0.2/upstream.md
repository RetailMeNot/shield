---
layout: docs_0_2
title: Docs
activeTab: docs
activePill: upstream
---
# Upstream Service

An Upstream Service Object has the following schema:

<div class="panel panel-warning">
  <div class="panel-heading">Departure from Standard</div>
  <div class="panel-body">
    Due to "legacy reasons", these Upstream Service Objects are treated as JSON Objects, not HOCON.  Make sure they are
    formatted to JSON standards.
  </div>
</div>

{% highlight javascript %}
// Upstream Service Object
{
  // Indicates how to communicate with the upstream service.
  // See below for valid options
  "serviceType": "swagger2",

  // The address of the upstream service.  Valid format changes for each different "serviceType"
  "serviceLocation": "https://api-w2.retailmenot.com",

  // The current weight of the service for load balancing.
  // Optional:  If not present, the service is given the weight of 1
  //
  // When a service is added, it is immediately set to it's initial weight.
  //
  // When a service is removed, it's weight is immediately set to 0 and starts to drain
  // any still in-flight requests.
  //
  // If the weight is modified after Shield has already been made aware of the service,
  // the weight will modified linearly from it's original value to it's updated value
  // according to the "upstream-weighting" configuration on the domain.
  // Modifying the "weight" value here while this transition is in process will reset
  // the transition to start from it's previous "in-progress" weight to the newly set value.
  //
  // Shield will still send traffic to services with "weight: 0" if no other upstream services
  // are capable of handling the request
  "weight": 0
}

{% endhighlight %}

### Service Type Settings

**Config Value**: `swagger1`

The upstream service exposes it's documentation using the Swagger 1 format.  The `serviceLocation` setting must be
an `HTTP` or `HTTPS` URI with no auth, path, query, or fragment.  Communication with the service is done via either HTTP
or HTTPS, as indicated by the protocol in the `serviceLocation`.


**Config Value**: `swagger2`

The upstream service exposes it's documentation using the Swagger 2 format.  The `serviceLocation` setting must be
an `HTTP` or `HTTPS` URI with no auth, path, query, or fragment.  Communication with the service is done via either HTTP
or HTTPS, as indicated by the protocol in the `serviceLocation`.

**Config Value**: `lambda`

The upstream service exposes it's documentation using the Swagger 2 format.  The `serviceLocation` setting must be
the `ARN` of the lambda function to invoke.

Requests are sent to the upstream service as a JSON object, formatted as follows:

    {
        "method": String,
        "uri": String,
        "headers": [
            ["header1", String],
            ["header2", String],
            etc...
        ],
        "body": base64string
    }

Explanation:

+ ```method```: The http method of the request. (i.e ```GET```,  ```POST```, ```PUT```, etc.)
+ ```uri```: The endpoint called by the client.
+ ```headers```: A list of all the headers, each value stored as lsit of 2 strings.
+ ```body```: (optional) The body of the request, formatted as a base64 encoded string. (Note: regardless of type, the body will be passed along as a string. It is up to the Lambda microservice to decode and convert it to JSON, XML, etc.)

Responses returned by the upstream service should also be a JSON object, formatted as follows:

    {
        "status": Int,
        "headers": [
            ["header1", String],
            ["header2", String],
            etc...
        ],
        "body": base64string
    }

Explanation:

+ ```status```: The three digit http status code.
+ ```headers```: A list of all the headers, each value stored as list of 2 strings.
+ ```body```: (optional) The body of the request, formatted as a base64 encoded string. (Note: regardless of type, the Lambda microservice should format the body as an encoded string for Shield to handle it properly.)

Shield uses the AWS Java SDK for communicating with AWS Services.  See the documentation on how to set up Shield with
[AWS Credentials](http://docs.aws.amazon.com/java-sdk/latest/developer-guide/credentials.html).
