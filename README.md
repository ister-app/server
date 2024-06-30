# Ister server

## Update reflection files

- Delete JSON files from: `server/src/main/resources/META-INF/native-image`. 
- Start the app with `./gradlew bootRun`
- In the frontend start rescan
- Go to the folowing urls:
  - http://localhost:8080/graphiql
  - http://localhost:8080/swagger-ui/index.html
  - http://localhost:8081/actuator
  - http://localhost:8081/actuator/health
  - http://localhost:8081/actuator/prometheus
  - http://localhost:8081/actuator/metrics

## Create test video

```shell
ffmpeg -f lavfi -i color=size=1280x720:rate=25:color=yellow -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -map 0 -map 1 -map 2 -metadata:s:v:0 language=deu -metadata:s:a:0 language=nld -t 3 output.mkv
```

## Native reflection

```shell
 ~/.jdks/graalvm-ce-17/bin/java -agentlib:native-image-agent=config-merge-dir=server/src/main/resources/META-INF/native-image -jar server/build/libs/server-0.0.1-SNAPSHOT.jar
```