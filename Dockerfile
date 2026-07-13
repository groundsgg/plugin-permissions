# syntax=docker/dockerfile:1
#
# Builds the plugin-permissions Velocity-plugin JAR and packages it for the
# platform-test environment. The output image carries the JAR at
# /jar/plugin.jar — the shape the `plugin-velocity-jar` Helm chart expects.
#
# Pushed as `ghcr.io/groundsgg/plugin-permissions:edge` (main) or with a
# semantic version tag by .github/workflows/docker-gradle-build-push.yml.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# GitHub Packages credentials for the Grounds Gradle convention plugins.
# The token is supplied as a BuildKit secret so it is not stored in a layer.
ARG GITHUB_USER

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./

COPY common/ common/
COPY minestom/ minestom/
COPY velocity/ velocity/

RUN --mount=type=secret,id=github_token,required=true \
    /bin/sh -euc '\
      : "${GITHUB_USER:?GITHUB_USER build arg is required}"; \
      token="$(cat /run/secrets/github_token)"; \
      ./gradlew --no-daemon :velocity:build \
        -Pgithub.user="${GITHUB_USER}" \
        -Pgithub.token="${token}" \
    '

RUN mkdir -p /out && \
    cp "$(ls -S /src/velocity/build/libs/*.jar | head -n1)" /out/plugin.jar

FROM alpine:3
RUN mkdir -p /jar
COPY --from=build /out/plugin.jar /jar/plugin.jar
# No ENTRYPOINT: the plugin-velocity-jar chart copies the JAR from this image.
