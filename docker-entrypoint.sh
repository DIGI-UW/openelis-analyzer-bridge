#!/bin/sh
# SPRING_PROFILES_ACTIVE selects the profile. SPRING_PROFILE is its old name, honoured when it is
# the only one set.
if [ -n "${SPRING_PROFILE}" ]; then
	if [ -z "${SPRING_PROFILES_ACTIVE}" ]; then
		echo "SPRING_PROFILE is deprecated; set SPRING_PROFILES_ACTIVE instead" >&2
		export SPRING_PROFILES_ACTIVE="${SPRING_PROFILE}"
	else
		echo "SPRING_PROFILE is ignored because SPRING_PROFILES_ACTIVE is set" >&2
	fi
fi

exec java ${JAVA_OPTS} -jar /app/openelis-analyzer-bridge.jar
