FROM adoptopenjdk:11.0.11_9-jdk-hotspot-focal@sha256:2b8296b8341cf0c6f2aa259afbea0bca0dfd8b5b34b2f7c965c2d8eb87a78257

RUN \
  sed -i 's/#networkaddress.cache.ttl=-1/networkaddress.cache.ttl=5/g' /opt/java/openjdk/conf/security/java.security && \
  sed -i 's/networkaddress.cache.negative.ttl=10/networkaddress.cache.negative.ttl=1/g' /opt/java/openjdk/conf/security/java.security

COPY --from=shopstic/bin-dumb-init:1.2.2 / /
COPY --from=foundationdb/foundationdb:6.3.15 /usr/lib/libfdb_c.so /usr/lib/libfdb_c.so
COPY --from=foundationdb/foundationdb:6.3.15 /usr/lib/fdb/multiversion/libfdb_c_6.2.25.so /usr/lib/fdb/multiversion/libfdb_c_6.2.25.so

ENTRYPOINT ["/usr/bin/dumb-init", "--"]
