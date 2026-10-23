FROM eclipse-temurin:17.0.16_8-jre-jammy
WORKDIR /opt/harness
RUN groupadd --gid 10001 harness && useradd --uid 10001 --gid harness --no-create-home harness
COPY --chown=10001:10001 platform.jar /opt/harness/platform.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Dfile.encoding=UTF-8 -Duser.timezone=UTC"
ENTRYPOINT ["java", "-jar", "/opt/harness/platform.jar"]
