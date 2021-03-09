FROM openjdk:11

LABEL org.opencontainers.image.source=https://github.com/provenance-io/p8e

ADD ./build/libs/*.jar /service.jar
ADD ./docker/docker-entrypoint.sh /docker-entrypoint.sh
ADD ./docker/libyjpagent.so /libyjpagent.so

EXPOSE 8080/tcp

ENTRYPOINT ./docker-entrypoint.sh /service.jar
