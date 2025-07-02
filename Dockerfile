FROM fedora:42 AS ffmpeg
WORKDIR /home/app
ADD https://johnvansickle.com/ffmpeg/builds/ffmpeg-git-amd64-static.tar.xz /tmp
RUN tar -xvf /tmp/ffmpeg-git-amd64-static.tar.xz --strip-components 1 --directory /usr/bin

FROM gradle:jdk21 AS builder
WORKDIR /home/app
COPY . .
RUN ./gradlew bootJar

FROM openjdk:21
WORKDIR /home/app
EXPOSE 8080
COPY --from=ffmpeg /usr/bin/ffmpeg /usr/bin/ffprobe /usr/bin/
COPY --from=builder /home/app/server/build/libs/server-0.0.1-SNAPSHOT.jar /home/app
CMD ["java", "-jar", "/home/app/server-0.0.1-SNAPSHOT.jar"]
