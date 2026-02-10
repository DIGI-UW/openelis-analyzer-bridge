#!/bin/sh
if [ -n "${SPRING_PROFILE}" ];then
	JAVA_OPTS="${JAVA_OPTS} -Dspring.profiles.active=${SPRING_PROFILE}"
fi

exec java ${JAVA_OPTS} -jar /app/openelis-analyzer-bridge.jar
