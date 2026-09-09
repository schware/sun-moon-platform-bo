# Build stage — full JDK, produces build/install/<name>/{bin,lib}
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Wrapper + build scripts first, so dependency resolution is cached
# independently of source edits. The kernel's build script comes along here
# too: it is an included build, so Gradle needs it to resolve anything
# (docs/adr/0014).
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY core/gradle ./core/gradle
COPY core/build.gradle.kts core/settings.gradle.kts ./core/
# The wrapper is 755 in git, but a build context copied from a Windows
# checkout loses the bit — set it explicitly rather than depend on the host.
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null

COPY core/src ./core/src
COPY src ./src
# Tests run in CI/locally, not in the image build: the live-HTTP tests bind
# real ports, which is a poor fit for a sandboxed build step.
RUN ./gradlew --no-daemon installDist -x test

# Runtime stage — JRE only, no Gradle, no sources
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin bo
COPY --from=build --chown=bo:bo /build/build/install/sun-moon-platform-bo ./
USER bo

# BO's slot in the family-wide port scheme (docs/adr/0013). Publish it to
# the LAN only — BO is internal, and Docker's -p bypasses UFW, so bind an
# interface explicitly rather than trusting the firewall to hold it back.
EXPOSE 8080

ENTRYPOINT ["./bin/sun-moon-platform-bo"]
