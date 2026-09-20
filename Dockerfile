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
COPY --from=builder /home/app/server/build/libs/server-*.jar /home/app/server.jar
CMD ["java", "-jar", "/home/app/server.jar"]
