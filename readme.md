Shield
======

[https://retailmenot.github.io/shield/](https://retailmenot.github.io/shield/)

A deployable API Gateway for your service oriented architecture.


## Running From Source

* Set up an application.conf in `src/main/resources/application.conf`.  This file is in the .gitignore, so make sure it 
doesn't get checked in.


        logger.root = DEBUG
        shield {
          domain-watcher: StaticDomainWatcher
          domains: [
            {
              domain-name: "api.example.org"
              middleware-chain: []
              middleware: {}
              listeners: []
              listener-config: {}
              kvstores: {}
              ignoreExtensions: []
              log: {
                request-headers: []
                response-headers: []
              }
              upstream-weighting: {
                step-count: 10
                step-duration: 3 seconds
              }

              upstream-watcher: StaticUpstreamWatcher
              upstreams: [
                {"serviceType": "swagger2", "serviceLocation": "https://example.com"}
              ]
            }
          ]
        }
    
* Run Shield
    * Execute `sbt run` from the command line
    * Create a new run configuration in your IDE with `shield.Boot` as the main class

## Versioning
To set the version number of the project at build/packaging/run time, do the following:

    sbt "set version := \"0.3.0\"" <task>

## Building A Release

Shield relies on the [SBT Native Packager](https://github.com/sbt/sbt-native-packager) plugin to create a release 
package.  To create the release, run:

   sbt clean test universal:packageBin

## Documentation
All documentation should be in either this readme.md or in the /docs folder.  For instructions on how to view the docs site
locally, visit this [GitHub article](https://help.github.com/articles/setting-up-your-github-pages-site-locally-with-jekyll/).

# License

This project is licensed under the terms of the MIT license.  See LICENSE.txt for the full terms.

