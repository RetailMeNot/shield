#/bin/bash

if [ -z "$1" ]
  then
    echo "Usage: $0 host"
    echo ""
    echo "PUTs the logstash index template on the selected host.  Host should be"
    echo "the url of the elasticsearch service."
    echo ""
    echo "Example: $0 https://example.org"
    echo "  This will PUT the template to https://example.org/_template/logstash"
    exit 1
fi

curl -XPUT $1/_template/logstash -d '{
        "aliases": {},
        "mappings": {
            "access_log": {
                "_all": {
                    "enabled": false
                },
                "properties": {
                    "@timestamp": {
                        "format": "dateOptionalTime",
                        "type": "date"
                    },
                    "cache_status": {
                        "index": "not_analyzed",
                        "type": "string"
                    },
                    "method": {
                        "index": "not_analyzed",
                        "type": "string"
                    },
                    "middleware_time": {
                        "properties": {
                            "proxy": {
                                "type": "long"
                            },
                            "request.authentication": {
                                "type": "long"
                            },
                            "request.localhttpcache": {
                                "type": "long"
                            },
                            "request.localratelimit": {
                                "type": "long"
                            },
                            "request.sharedhttpcache": {
                                "type": "long"
                            },
                            "request.sharedratelimit": {
                                "type": "long"
                            },
                            "response.cache-header": {
                                "type": "long"
                            },
                            "response.cache-stale-rescue": {
                                "type": "long"
                            }
                        }
                    },
                    "overall_time": {
                        "type": "long"
                    },
                    "path": {
                        "index": "not_analyzed",
                        "type": "string"
                    },
                    "request_headers": {
                        "properties": {
                            "pid": {
                                "type": "string",
                                "index": "not_analyzed"
                            },
                            "Remote-Address": {
                                "type": "string",
                                "index": "not_analyzed"
                            },
                            "User-Agent": {
                                "type": "string",
                                "index": "not_analyzed"
                            }
                        }
                    },
                    "responding_host": {
                        "index": "not_analyzed",
                        "type": "string"
                    },
                    "responding_service": {
                        "index": "not_analyzed",
                        "type": "string"
                    },
                    "response_size": {
                        "type": "long"
                    },
                    "response_status": {
                        "type": "long"
                    },
                    "shield_host": {
                        "index": "not_analyzed",
                        "type": "string"
                    },
                    "template": {
                        "index": "not_analyzed",
                        "type": "string"
                    }
                }
            }
        },
        "order": 0,
        "settings": {
            "number_of_shards": 10
        },
        "template": "logstash-*"
}'
