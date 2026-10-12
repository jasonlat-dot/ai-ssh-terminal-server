# Uses Docker's bundled BuildKit frontend; no extra frontend image pull needed.
ARG BUILD_IMAGE=maven:3.10.0-eclipse-temurin-25-noble
ARG RUNTIME_IMAGE=eclipse-temurin:25-jdk-noble

FROM ${BUILD_IMAGE} AS build
ARG MAVEN_PROXY_ENABLED=false
ARG MAVEN_PROXY_HOST=127.0.0.1
ARG MAVEN_PROXY_PORT=7890
WORKDIR /workspace
# Dockerfile.dockerignore excludes local application*.yml and all secrets/data.
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -s docs/dev-ops/docker-compose/maven-settings.xml \
      -pl ai-ssh-terminal-server-app -am package -Dmaven.test.skip=true

FROM ${RUNTIME_IMAGE} AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 shellpilot \
    && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin shellpilot \
    && mkdir -p /app/config /data \
    && chown -R 10001:10001 /app /data \
    && test -x /opt/java/openjdk/bin/keytool
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/ai-ssh-terminal-server-app/target/ai-ssh-terminal-server-app.jar /app/app.jar
COPY --from=build --chown=10001:10001 /workspace/docs/dev-ops/docker-compose/config/application.yml /app/config/application.yml
USER 10001:10001
ENV AI_SSH_TERMINAL_HOME=/data \
    SPRING_CONFIG_LOCATION=file:/app/config/application.yml
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
