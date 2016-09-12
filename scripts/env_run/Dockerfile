FROM java:8

MAINTAINER Shield Project

ARG artifact_version=0.3

RUN apt-get update && \
    apt-get -y install libc6

ADD target/universal/shield-${artifact_version}.zip /tmp/shield.zip

RUN unzip /tmp/shield.zip -d /opt && \
    rm /tmp/shield.zip && \
    mv /opt/shield-${artifact_version} /opt/shield

EXPOSE 8080

CMD ["/opt/shield/bin/shield"]
