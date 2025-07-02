# Ister server

## Run local for development

Start the database and rabbitmq with:

```shell
podman-compose -f docker-compose-local.yml up database rabbitMQ
```

Then you can run the application:

```shell
./gradlew bootRun
```

## Create test video

```shell
ffmpeg -f lavfi -i color=size=1280x720:rate=25:color=yellow -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -map 0 -map 1 -map 2 -metadata:s:v:0 language=deu -metadata:s:a:0 language=nld -t 3 output.mkv
```
