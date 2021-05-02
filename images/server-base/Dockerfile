FROM shopstic/bin-docker:20.10.6 as bin-docker
FROM shopstic/bin-dumb-init:1.2.2 as bin-dumb-init

FROM shopstic/curl-tar-unzip:1.0.1 as fdb-clients

ENV FDB_VERSION "6.2.30"

RUN \
  curl -Lko /tmp/fdb-clients.deb https://www.foundationdb.org/downloads/${FDB_VERSION}/ubuntu/installers/foundationdb-clients_${FDB_VERSION}-1_amd64.deb

FROM openjdk:11.0.11-jre-slim-buster

SHELL ["/bin/bash", "-c"]

COPY --from=bin-dumb-init / /
COPY --from=bin-docker / /
COPY --from=fdb-clients /tmp/fdb-clients.deb /tmp/fdb-clients.deb

RUN \
  apt-get install /tmp/fdb-clients.deb && \
  rm -f /tmp/fdb-clients.deb

RUN \
  sed -i 's/#networkaddress.cache.ttl=-1/networkaddress.cache.ttl=5/g' /usr/local/openjdk-11/conf/security/java.security && \
  sed -i 's/networkaddress.cache.negative.ttl=10/networkaddress.cache.negative.ttl=1/g' /usr/local/openjdk-11/conf/security/java.security

ENTRYPOINT ["/usr/bin/dumb-init", "--"]
