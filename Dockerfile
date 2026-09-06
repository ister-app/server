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
    curl \
    ca-certificates \
    tesseract-ocr \
    tesseract-ocr-all \
    hunspell \
    hunspell-en-us \
    hunspell-nl \
    ffmpeg \
    libva-drm2 \
    mesa-va-drivers \
    i965-va-driver \
    intel-media-va-driver \
    && rm -rf /var/lib/apt/lists/*
# Better tesseract models than the distro's tessdata_fast packages (which the server keeps as
# fallback for every other language). Override at build time: --build-arg TESSDATA_BEST_LANGS="eng nld deu".
ARG TESSDATA_BEST_LANGS="eng nld deu fra spa ita por"
RUN mkdir -p /usr/share/tesseract/tessdata-best \
    && for lang in $TESSDATA_BEST_LANGS; do \
         curl -fsSL -o /usr/share/tesseract/tessdata-best/$lang.traineddata \
           https://github.com/tesseract-ocr/tessdata_best/raw/main/$lang.traineddata || exit 1; \
       done
ENV SUBTITLE_OCR_TESSDATA_DIR=/usr/share/tesseract/tessdata-best
COPY --from=subtile-ocr-builder /usr/local/cargo/bin/subtile-ocr /usr/bin/subtile-ocr
COPY --from=builder /home/app/server/build/libs/server-*.jar /home/app/server.jar
CMD ["java", "-jar", "/home/app/server.jar"]
