# syntax=docker/dockerfile:1
#
# Builds the plugin-permissions Velocity-plugin JAR and packages it the way
# the `plugin-velocity-jar` Helm chart expects: the JAR at /jar/plugin.jar,
# no entrypoint. A Velocity release lists the plugin by name and fetches it
# at startup into /app/plugins.
#
# The minestom half needs none of this — it is consumed as a Maven artifact
# and is already compiled into the server images. This image exists only
# because a Velocity plugin is loaded as a file rather than linked.
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

# `settings.gradle.kts` includes minestom, so its build files have to be
# present for configuration to succeed even though nothing here builds it.
COPY common/ common/
COPY velocity/ velocity/
COPY minestom/ minestom/

# `:velocity:build` runs the convention plugin's shadowJar. The default
# `build` task also produces a thin plugin JAR and a sources JAR; the fat one
# is the one Velocity can actually load.
RUN --mount=type=secret,id=github_token,required=true \
    /bin/sh -euc '\
      : "${GITHUB_USER:?GITHUB_USER build arg is required}"; \
      token="$(cat /run/secrets/github_token)"; \
      ./gradlew --no-daemon :velocity:build \
        -Pgithub.user="${GITHUB_USER}" \
        -Pgithub.token="${token}" \
    '

# Resolve the fat JAR by size — the convention plugin pins no stable
# classifier, so a glob would match either too few files or too many.
RUN mkdir -p /out && \
    cp "$(ls -S /src/velocity/build/libs/*.jar | head -n1)" /out/plugin.jar

FROM alpine:3
RUN mkdir -p /jar
COPY --from=build /out/plugin.jar /jar/plugin.jar
# No ENTRYPOINT — the plugin-velocity-jar chart's init-container `cp`s
# /jar/plugin.jar out and its main container (busybox httpd) serves it. This
# image only carries data.
