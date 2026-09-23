FROM gradle:8.10-jdk17 AS plugin-builder

WORKDIR /plugin
COPY plugin/ ./
RUN gradle --no-daemon build

FROM eclipse-temurin:17-jre

WORKDIR /waterdog

# Docker CLI only (no daemon) - needed by DockerWorldProvisioner when
# PROVISIONER=docker (see docker/docker-compose.yml, which mounts the host's
# Docker socket into this container). Unused, harmless, when PROVISIONER=ecs.
ARG DOCKER_CLI_VERSION=29.8.1

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && curl -fL -o Waterdog.jar https://github.com/WaterdogPE/WaterdogPE/releases/download/latest/Waterdog.jar \
    && curl -fL "https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_CLI_VERSION}.tgz" \
        | tar -xz --strip-components=1 -C /usr/local/bin docker/docker \
    && apt-get purge -y curl \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /waterdog/plugins
COPY --from=plugin-builder /plugin/build/libs/waterdog-control.jar /waterdog/plugins/waterdog-control.jar

EXPOSE 19132/udp
EXPOSE 8081/tcp

ENTRYPOINT ["java", \
    "-Dio.netty.tryReflectionSetAccessible=true", \
    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED", \
    "-Xms512M", "-Xmx1G", \
    "-jar", "Waterdog.jar"]
