FROM ghcr.io/graalvm/native-image-community:24 AS graalvm
WORKDIR /home/app
RUN microdnf install -y xz jq zip findutils \
    && microdnf clean all
ADD https://johnvansickle.com/ffmpeg/builds/ffmpeg-git-amd64-static.tar.xz /tmp
RUN tar -xvf /tmp/ffmpeg-git-amd64-static.tar.xz --strip-components 1 --directory /usr/bin
COPY . .
RUN ./gradlew nativeCompile

FROM fedora:39
WORKDIR /home/app
RUN dnf install -y fontconfig && dnf clean -y all
EXPOSE 8080
COPY --from=graalvm /usr/bin/ffmpeg /usr/bin/ffprobe /usr/bin/
COPY --from=graalvm /home/app/server/build/native/nativeCompile /home/app
ENTRYPOINT ["/home/app/server"]
