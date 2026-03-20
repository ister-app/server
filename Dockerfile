FROM fedora:43 AS ffmpeg
WORKDIR /home/app
ADD https://johnvansickle.com/ffmpeg/builds/ffmpeg-git-amd64-static.tar.xz /tmp
RUN tar -xvf /tmp/ffmpeg-git-amd64-static.tar.xz --strip-components 1 --directory /usr/bin

FROM gradle:jdk25 AS builder
WORKDIR /home/app
COPY . .
RUN ./gradlew bootJar

FROM eclipse-temurin:25
WORKDIR /home/app
EXPOSE 8080
COPY --from=ffmpeg /usr/bin/ffmpeg /usr/bin/ffprobe /usr/bin/
COPY --from=builder /home/app/server/build/libs/server-*.jar /home/app/server.jar
CMD ["java", "-jar", "/home/app/server.jar"]
