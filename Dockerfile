FROM rust:latest AS subtile-ocr-builder
RUN apt-get update && apt-get install -y --no-install-recommends \
    libleptonica-dev \
    libtesseract-dev \
    clang \
    && rm -rf /var/lib/apt/lists/*
RUN cargo install subtile-ocr

FROM gradle:jdk25 AS builder
WORKDIR /home/app
COPY . .
RUN ./gradlew bootJar

FROM eclipse-temurin:25
WORKDIR /home/app
EXPOSE 8080
RUN apt-get update && apt-get install -y --no-install-recommends \
    mkvtoolnix \
    tesseract-ocr \
    tesseract-ocr-all \
    ffmpeg \
    libva-drm2 \
    mesa-va-drivers \
    i965-va-driver \
    intel-media-va-driver \
    && rm -rf /var/lib/apt/lists/*
COPY --from=subtile-ocr-builder /usr/local/cargo/bin/subtile-ocr /usr/bin/subtile-ocr
COPY --from=builder /home/app/server/build/libs/server-*.jar /home/app/server.jar
CMD ["java", "-jar", "/home/app/server.jar"]
