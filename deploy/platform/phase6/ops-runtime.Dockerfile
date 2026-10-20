# Context: this Dockerfile and exactly one accepted artifact named app.jar.
# Both build arguments are mandatory; use the reviewed Temurin 17 JRE digest.
ARG JAVA_RUNTIME_IMAGE
FROM ${JAVA_RUNTIME_IMAGE}
ARG JAVA_RUNTIME_IMAGE
ARG APP_JAR_SHA256

WORKDIR /app
COPY app.jar /app/app.jar
RUN set -eu; \
    case "${JAVA_RUNTIME_IMAGE:-}" in \
        eclipse-temurin:17-jre-jammy@sha256:*|eclipse-temurin:17.*-jre-jammy@sha256:*) ;; \
        *) printf '%s\n' 'JAVA_RUNTIME_IMAGE must pin a Temurin 17 JRE Jammy digest' >&2; exit 1 ;; \
    esac; \
    base_sha="${JAVA_RUNTIME_IMAGE##*@sha256:}"; \
    case "$base_sha" in \
        ''|*[!0-9a-fA-F]*) printf '%s\n' 'Invalid runtime image SHA256' >&2; exit 1 ;; \
    esac; \
    [ "${#base_sha}" -eq 64 ] || { printf '%s\n' 'Runtime image SHA256 must have 64 hex characters' >&2; exit 1; }; \
    expected_sha="${APP_JAR_SHA256:-}"; \
    case "$expected_sha" in \
        ''|*[!0-9a-fA-F]*) printf '%s\n' 'Invalid APP_JAR_SHA256' >&2; exit 1 ;; \
    esac; \
    [ "${#expected_sha}" -eq 64 ] || { printf '%s\n' 'APP_JAR_SHA256 must have 64 hex characters' >&2; exit 1; }; \
    printf '%s  %s\n' "$expected_sha" '/app/app.jar' | sha256sum --check --strict -; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl; \
    rm -rf /var/lib/apt/lists/*; \
    useradd --create-home --uid 10001 opsagent; \
    mkdir -p /app/data/uploads; \
    chown -R opsagent:opsagent /app

# Existing Compose healthchecks specify each service's curl endpoint.
# Existing uploads volumes must separately be writable by UID 10001.
USER opsagent
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
