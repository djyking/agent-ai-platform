# Build context contains only the accepted support.jar and this Dockerfile.
# Supply a verified digest for JAVA_RUNTIME_IMAGE in the release build.
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17.0.16_8-jre-jammy
FROM ${JAVA_RUNTIME_IMAGE}
RUN groupadd --gid 10001 harness && useradd --uid 10001 --gid harness --no-create-home harness
WORKDIR /opt/harness
COPY --chown=10001:10001 support.jar /opt/harness/support.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/opt/harness/support.jar"]
