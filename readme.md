Shield
======

An HTTP router/cacher/loadbalancer/ratelimiter all-in-one.


To get started:

* Set up an application.conf in `src/main/resources/application.conf`.  This file is in the .gitignore, so make sure it 
doesn't get checked in.


    logger.root = DEBUG

    shield {
      upstreams = [
        {serviceType: "swagger2", serviceLocation: "https://example.org"}
        {serviceType: "swagger2", serviceLocation: "https://example.com", weight: 0}
      ]
    }

## Access logs
Listeners have the ability to perform additional processing of completed requests.  Shield currently has listeners to
output access logs to an elastic search cluster via the KibanaBuilder which can be added to your config with

    listeners = [
        {id: kibana, builder: KibanaBuilder}
    ]

The Kibana log forwarder is now set to receive completed requests and process them but still requires configuration so
that access logs can be forwarded to the elastic search service, given an index prefix, etc.  The required configuration
can all be set in the listener-config under the id used from above, in this case 'kibana'.

    listener-config {
        kibana {
            buffer-size: 1000
            max-outstanding: 32
            host: "http://example-search-domain.us-west-1.es.amazonaws.com:80"
            index-prefix: "logstash"
            type: "access_log"
            AWSSigning {
                active: true
                region: us-west-1
                service: es
            }
        }
    }

If you are using Amazons Elastic Search Service(ESS) for storing and viewing access logs you may wish to sign requests
sent to the service which would allow you to restrict the access policy of the ESS without losing the ability to forward
logs to it.  Request signing can be enabled by adding the optional AWSSigning config to the KibanaForwarder being used.
If active you are required to supply a 'region' and 'service' parameter.

Signing requests to AWS requires your access key and secret access keys.  These are retrieved at shield startup using
the [DefaultAWSCredentialsProviderChain](http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/DefaultAWSCredentialsProviderChain.html) provided
by the AWS SDK.  Shield must have access to these keys through one of the methods used by DefaultAWSCredentialsProviderChain:

1. Environment Variables - `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` ( **Recommended** since they are recognized by all the
 AWS SDKs and CLI except for .NET), or `AWS_ACCESS_KEY` and `AWS_SECRET_KEY` (only recognized by Java SDK)
2. Java System Properties - `aws.accessKeyId` and `aws.secretKey`
3. Credential profiles file at the default location (`~/.aws/credentials`) shared by all AWS SDKs and the AWS CLI
4. Instance profile credentials delivered through the Amazon EC2 metadata service


### Logging headers
By default the `User-Agent` and  `Remote-Address` request headers are added to the access logs.  The default headers
can be added to or changed in the application.conf file.
See [HOCON's Self-referential substitutions](https://github.com/typesafehub/config/blob/master/HOCON.md#self-referential-substitutions) for
more information on configuration.

Override existing defaults:

    shield {
        log {
            request-headers:  ["X-Forwarded-For", "X-Forwarded-Proto"]
            response-headers: ["X-Cache"]
        }
    }

Adding additional headers to defaults:

    shield {
        log {
            request-headers: ${?shield.log.request-headers} ["Cache-Control", "Accept-Encoding"]
        }
    }

Alternative to adding single additional header:

    shield {
        log {
            request-headers += "Cache-Control"
        }
    }

## Handling extensions
By ignoring extensions during routing you are able to route all extensions without the need to specify each extension
as its own path in the upstream swagger documentation.

Routing can be handled without taking extensions into account by adding the Shield configuration option `ignoreExtensions`
which specifies which extensions should be discarded from incoming requests.

    shield {
        ignoreExtensions: [ "json" ]
    }

## Packaging
To set the version number of the project at build/packaging/run time, do the following:

    sbt "set version := \"0.3.0\"" <task>

## Documentation
All documentation should be in either this readme.md or in the /docs folder.  For instructions on how to view the docs site
locally, go to https://help.github.com/articles/setting-up-your-github-pages-site-locally-with-jekyll/

# License

This project is licensed under the terms of the MIT license.  See LICENSE.txt for the full terms.

