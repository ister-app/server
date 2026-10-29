FROM gradle:jdk25 AS builder
WORKDIR /home/app
COPY . .
RUN ./gradlew bootJar

FROM eclipse-temurin:25
WORKDIR /home/app
EXPOSE 8080
RUN apt-get update && apt-get install -y --no-install-recommends \
    mkvtoolnix \
    curl \
    ca-certificates \
    ffmpeg \
    libva-drm2 \
    mesa-va-drivers \
    i965-va-driver \
    intel-media-va-driver \
    && rm -rf /var/lib/apt/lists/*
# Runs as UID/GID 1000, not root. The Ubuntu base already ships a user `ubuntu` on
# 1000; it is replaced so the name matches. /cache and /tmp/ister are the default
# mount points (docker-compose.yml), created here so a named volume starts out
# owned by that user. A bind mount keeps the owner it has on the host: chown it to 1000.
RUN userdel --remove ubuntu \
    && useradd --uid 1000 --user-group --home-dir /home/app --no-create-home ister \
    && mkdir -p /cache /tmp/ister \
    && chown ister:ister /home/app /cache /tmp/ister
COPY --from=builder /home/app/server/build/libs/server-*.jar /home/app/server.jar
USER 1000:1000
CMD ["java", "-jar", "/home/app/server.jar"]
