FROM java:8

MAINTAINER Shield Project

RUN apt-get update && \
    apt-get -y install \
        apt-transport-https \
        && \
    echo "deb https://dl.bintray.com/sbt/debian /" | tee -a /etc/apt/sources.list.d/sbt.list && \
    apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 642AC823 && \
    apt-get update && \
    apt-get -y install \
        sbt \
        gzip \
        tar \
        zip \
        libc6 \
        && \
    mkdir -p /opt/shield && \
    # default to a shared directory so there's no cache-miss when specifying a different user to run the container
    echo "-sbt-dir /etc/sbt" >> /usr/share/sbt-launcher-packaging/conf/sbtopts && \
    echo "-sbt-boot /etc/sbt/boot" >> /usr/share/sbt-launcher-packaging/conf/sbtopts && \
    echo "-ivy /etc/ivy" >> /usr/share/sbt-launcher-packaging/conf/sbtopts && \
    echo "-no-colors" >> /usr/share/sbt-launcher-packaging/conf/sbtopts

VOLUME /opt/shield

WORKDIR /opt/shield

ADD . /opt/shield

# download sbt and all our deps so they're captured in the container
# then, make the shared directories read/write friendly for all users
RUN sbt compile && \
    rm -rf /opt/shield/* && \
    chmod -R 0777 /etc/sbt && \
    chmod -R 0777 /etc/ivy

CMD ["sbt", "clean", "test", "universal:packageBin"]
