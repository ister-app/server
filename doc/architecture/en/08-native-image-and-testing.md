# Native image and testing

## GraalVM native image is the production artifact

The Docker image ships a GraalVM native image (`./gradlew nativeCompile`, `Dockerfile.native`), and
that constrains how code is written:

- **Never use `@ConditionalOnProperty` for a runtime-toggleable feature.** Bean conditions are
  evaluated and frozen at native-image *build* time; a conditional bean is baked out of the
  production image and no property can bring it back. Check the flag **inside** the bean instead —
  the Typesense handlers are the reference example ([chapter 6](06-search.md)).
- **Reflection and resource hints are hand-maintained** per module under
  `src/main/resources/META-INF/native-image/`. Nothing generates them; forgetting one surfaces only
  in the native image, not in JVM runs.
- A properties file imported via `spring.config.import` needs an entry in its module's
  `resource-config.json` (see `search/src/main/resources/META-INF/native-image/`).
- **AWT works in the native image, but only with hand-fed JNI hints.** PDFBox touches AWT even for
  a plain page count (`PDDocument`'s static init warms up the color subsystem), and the JDK's AWT
  native libraries look up classes, fields and methods through JNI at run time — every one of them
  must be listed in `disk/.../native-image/jni-config.json` or the lookup fails and the class
  initializer dies for the lifetime of the process. The charset flag `-H:+AddAllCharsets`
  (server `build.gradle`) is part of the same story: PDFBox's `BaseParser` wants Windows-1252,
  which the image drops by default. The generated `libawt*.so` files land next to the binary in
  `build/native/nativeCompile` and ship with it (`Dockerfile.native` copies the whole directory).

## Database schema discipline

Prod runs `hibernate.ddl-auto=validate`: an entity change without a matching Flyway migration fails
startup. Migrations live in `database/src/main/resources/db/migration/V<n>__<name>.sql` (latest:
`V28`), are **forward-only**, and are also shipped as a separate image (`Dockerfile.migrations`).
Never edit a migration that has been applied anywhere.

## Testing

- Unit tests are JUnit 5 + Mockito; Mockito runs as a `-javaagent`, wired in the root
  `build.gradle`.
- `jimfs` (in-memory filesystem) backs the disk/file-path tests.
- **ffmpeg must be on `PATH`** — the transcoder tests shell out to it, and CI installs it before the
  build.
- Integration tests are **not** a separate source set or task. They run under the normal `test`
  task, are named `*IntegrationTest`, and use Testcontainers PostgreSQL (and RabbitMQ for the
  dead-letter flow). They are annotated `@Testcontainers(disabledWithoutDocker = true)`, so **they
  silently skip when no container runtime is reachable** — a green `./gradlew test` does not prove
  they ran. Locally:

  ```bash
  systemctl --user start podman.socket
  DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock ./gradlew test
  ```

Useful commands:

```bash
./gradlew build                                      # build all modules
./gradlew :core:test                                 # one module
./gradlew :worker:test --tests "app.ister.worker.…"  # one class
```

## CI and quality gate

CI runs `./gradlew build check jacocoTestReport sonar`. There is no separate linter or formatter —
quality gating is SonarCloud (`org.sonarqube`) plus Jacoco coverage, both part of `check`.

## Commits and releases

Commit subjects must be [Conventional Commits](https://www.conventionalcommits.org/) (`feat(scope):
…`, `fix: …`, `!`/`BREAKING CHANGE:` for breaking); a `commit-lint` job fails the PR otherwise, and
`.github/workflows/release.yml` derives both the version bump and the release notes from them — see
`CONTRIBUTING.md`.

Releases are cut nightly and automatically. **Never bump `version` in `build.gradle` by hand**:
`main` always carries a `-SNAPSHOT`, and only the tagged release commit carries a clean version.
`docker-publish.yml` tags every main push with the gradle project version next to `main`; the server
and migrations images get the same tag so downstream pins stay in lockstep.


