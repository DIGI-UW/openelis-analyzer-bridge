##
# Build Stage
#
FROM maven:3.9.7-eclipse-temurin-21-jammy AS build
##
# Copy Source Code and Build Dependencies
#
ADD ./astm-http-lib/pom.xml /build/astm-http-lib/pom.xml
WORKDIR /build/astm-http-lib
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
  mvn dependency:go-offline
ADD ./astm-http-lib/src /build/astm-http-lib/src

##
# Build Project
#
WORKDIR /build
ADD ./pom.xml /build/pom.xml
ADD ./src /build/src

# Build library and main project in a single RUN to share .m2 repository
# This ensures the locally installed library is available for the main project build
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
  cd /build/astm-http-lib && mvn clean install -DskipTests && \
  cd /build && mvn clean package -DskipTests

##
# Run Stage
#
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl gcompat

RUN addgroup -S astm --gid 9257 && adduser -S astm -s /bin/bash -u 9257 -G astm
RUN mkdir /app
RUN chown astm:astm /app


#Deploy the jar into java image
COPY --from=build /build/target/*.jar /app/astm-http-bridge.jar

ADD healthcheck.sh /app/healthcheck.sh
ADD docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chown astm:astm /app/docker-entrypoint.sh; \
  chmod 770 /app/docker-entrypoint.sh;  \
  chown astm:astm /app/astm-http-bridge.jar; \
  chmod 770 /app/astm-http-bridge.jar; \
  chown astm:astm /app/healthcheck.sh; \
  chmod 770 /app/healthcheck.sh;

USER astm:astm

ENTRYPOINT [ "/app/docker-entrypoint.sh" ]
