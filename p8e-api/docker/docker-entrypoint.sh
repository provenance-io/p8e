#!/bin/sh
set -e

if [ "$JMX_ENABLED" = true ]; then
    #JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.port=7198 -Dcom.sun.management.jmxremote.rmi.port=7199 -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false -Djava.rmi.server.hostname=localhost -Dcom.sun.management.jmxremote.local.only=false -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:7200"

     JAVA_OPTS="$JAVA_OPTS -agentpath:/libyjpagent.so"
fi

java $JAVA_OPTS -jar $1 --spring.profiles.active="$SPRING_PROFILES_ACTIVE"
