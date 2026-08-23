# syntax=docker/dockerfile:1
#
# Builds the Velocity and Paper plugin JARs. Velocity remains at /jar/plugin.jar
# for the `plugin-velocity-jar` Helm chart; Paper is published at /jar/paper.jar.
# no entrypoint. A Velocity release lists the plugin by name and fetches it
# at startup into /app/plugins.
#
# The data image lets server images consume release JARs without Maven credentials.
#
# Pushed as `ghcr.io/groundsgg/plugin-permissions:edge` (main) / `:<semver>`
# (tag) by .github/workflows/docker-gradle-build-push.yml.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# GitHub Packages credentials for the gg.grounds.velocity-conventions plugin
# and the transitive grounds dependencies. The token comes from the
# `github_token` build secret — never a build-arg, which would leave it in
# the layer history.
ARG GITHUB_USER

# Gradle wrapper + root config first, so the dependency cache stays warm
# across source-only changes.
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./

# `settings.gradle.kts` includes all platform modules, so their build files have
# to be present for configuration to succeed even though nothing here builds
# them.
COPY common/ common/
COPY velocity/ velocity/
COPY minestom/ minestom/
COPY paper/ paper/

# `:velocity:build` and `:paper:shadowJar` produce the runtime JARs. The default
# `build` task also produces a thin plugin JAR and a sources JAR; the fat one
# is the one Velocity can actually load.
RUN --mount=type=secret,id=github_token,required=true \
    /bin/sh -euc '\
      : "${GITHUB_USER:?GITHUB_USER build arg is required}"; \
      token="$(cat /run/secrets/github_token)"; \
      ./gradlew --no-daemon :velocity:build :paper:shadowJar \
        -Pgithub.user="${GITHUB_USER}" \
        -Pgithub.token="${token}" \
    '

# Resolve the fat JAR by size — the convention plugin pins no stable
# classifier, so a glob would match either too few files or too many.
RUN mkdir -p /out && \
    cp "$(ls -S /src/velocity/build/libs/*.jar | head -n1)" /out/plugin.jar && \
    cp /src/paper/build/libs/plugin-permissions-paper.jar /out/paper.jar

FROM alpine:3
RUN mkdir -p /jar
COPY --from=build /out/plugin.jar /jar/plugin.jar
COPY --from=build /out/paper.jar /jar/paper.jar
# No ENTRYPOINT — the plugin-velocity-jar chart's init-container `cp`s
# /jar/plugin.jar out and its main container (busybox httpd) serves it. This
# image only carries data.
