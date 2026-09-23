# syntax=docker/dockerfile:1

# kinetix-notification-service
#
# Two stages: sbt builds a fat jar, and the runtime image carries a JRE and that jar. The build
# stage holds sbt, coursier's cache and the whole Scala compiler; none of it belongs in the image
# that runs in production.

FROM eclipse-temurin:21-jdk AS build

ARG SBT_VERSION=1.10.7

RUN apt-get update \
 && apt-get install --no-install-recommends -y curl gnupg git ca-certificates \
 && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
      | tar -xz -C /opt \
 && rm -rf /var/lib/apt/lists/*

ENV PATH="/opt/sbt/bin:${PATH}"

WORKDIR /build

# The contracts come from the contract repository at a pinned tag, never from a copy in this
# repository. Fetched before the sources are copied so that a source-only change reuses this layer.
# It also narrows them to the three packages this service speaks, so the directory sbt compiles
# from exists before sbt is ever started.
COPY bin/sync-contracts bin/sync-contracts
RUN sh bin/sync-contracts

# Dependencies next, for the same reason: build.sbt changes far less often than src/ does.
COPY project/build.properties project/plugins.sbt project/
COPY build.sbt VERSION ./
RUN sbt -batch update

COPY src src
RUN sbt -batch assembly

FROM eclipse-temurin:21-jre AS runtime

# Not root. A service that is compromised should not also be the user that can rewrite its own
# image's filesystem.
RUN groupadd --system kinetix && useradd --system --gid kinetix --home /app kinetix

WORKDIR /app

COPY --from=build /build/target/scala-3.3.4/kinetix-notification-service.jar /app/service.jar

# What kinetix_build_info reports on the metrics endpoint. Baked from the build argument so that a
# running container can say which commit it is, which is the question asked during an incident.
ARG SERVICE_VERSION=unknown
ENV KINETIX_SERVICE_VERSION=${SERVICE_VERSION}

USER kinetix

# 50051 warehouse, 50052 identity, 50053 matching, 50054 pricing, 50055 order, 50056 payment.
# 8004 is HTTP: /health, /health/ready and /metrics only. The notification surface is gRPC.
EXPOSE 50057
EXPOSE 8004

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/service.jar"]
