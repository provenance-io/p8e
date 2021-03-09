FROM openjdk:11

LABEL org.opencontainers.image.source=https://github.com/provenance-io/p8e

ADD ./build/libs/*.jar /service.jar
ADD ./docker/docker-entrypoint.sh /docker-entrypoint.sh
ADD ./docker/libyjpagent.so /libyjpagent.so

RUN GRPC_HEALTH_PROBE_VERSION=v0.1.0-alpha.1 && \
    wget -qO/bin/grpc_health_probe https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/${GRPC_HEALTH_PROBE_VERSION}/grpc_health_probe-linux-amd64 && \
    chmod +x /bin/grpc_health_probe

EXPOSE 8080/tcp

ENTRYPOINT ./docker-entrypoint.sh /service.jar
