# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a AS build

ARG SBT_VERSION=1.10.7

RUN apt-get update \
 && apt-get install --no-install-recommends -y curl gnupg git ca-certificates \
 && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
      | tar -xz -C /opt \
 && rm -rf /var/lib/apt/lists/*

ENV PATH="/opt/sbt/bin:${PATH}"

COPY security/jdk21-blocked.certs /tmp/jdk21-blocked.certs
RUN set -eu; \
    shipped="$JAVA_HOME/lib/security/blocked.certs"; \
    sed 1d "$shipped" | sort > /tmp/shipped.fp; \
    sed 1d /tmp/jdk21-blocked.certs | sort > /tmp/override.fp; \
    if [ -n "$(comm -23 /tmp/shipped.fp /tmp/override.fp)" ]; then \
      echo "this JDK blocks certificates security/jdk21-blocked.certs does not: remove the override"; \
      exit 1; \
    fi; \
    cp /tmp/jdk21-blocked.certs "$shipped"; \
    grep -qx 42C4ACCD12C4F59597925E1A08A7A90102608543809308D50EC427E118BA1493 "$shipped"; \
    rm /tmp/jdk21-blocked.certs /tmp/shipped.fp /tmp/override.fp

WORKDIR /build

COPY bin/sync-contracts bin/sync-contracts
RUN sh bin/sync-contracts

COPY project/build.properties project/plugins.sbt project/
COPY build.sbt VERSION ./
RUN sbt -batch update

COPY src src
RUN sbt -batch assembly \
 && cd /tmp \
 && jar xf /build/target/scala-3.3.4/kinetix-notification-service.jar META-INF/MANIFEST.MF \
 && grep -q '^Main-Class: com.kinetix.notification.Main' META-INF/MANIFEST.MF

FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a AS runtime

RUN apt-get update \
 && apt-get upgrade -y --no-install-recommends \
 && rm -rf /var/lib/apt/lists/*

COPY security/jdk21-blocked.certs /tmp/jdk21-blocked.certs
RUN set -eu; \
    shipped="$JAVA_HOME/lib/security/blocked.certs"; \
    sed 1d "$shipped" | sort > /tmp/shipped.fp; \
    sed 1d /tmp/jdk21-blocked.certs | sort > /tmp/override.fp; \
    if [ -n "$(comm -23 /tmp/shipped.fp /tmp/override.fp)" ]; then \
      echo "this JDK blocks certificates security/jdk21-blocked.certs does not: remove the override"; \
      exit 1; \
    fi; \
    cp /tmp/jdk21-blocked.certs "$shipped"; \
    grep -qx 42C4ACCD12C4F59597925E1A08A7A90102608543809308D50EC427E118BA1493 "$shipped"; \
    rm /tmp/jdk21-blocked.certs /tmp/shipped.fp /tmp/override.fp

RUN groupadd --system kinetix && useradd --system --gid kinetix --home /app kinetix

WORKDIR /app

COPY --from=build /build/target/scala-3.3.4/kinetix-notification-service.jar /app/service.jar

ARG SERVICE_VERSION=unknown
ENV KINETIX_SERVICE_VERSION=${SERVICE_VERSION}

USER kinetix

EXPOSE 50057
EXPOSE 8004

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60"

ENTRYPOINT ["java", "-jar", "/app/service.jar"]
