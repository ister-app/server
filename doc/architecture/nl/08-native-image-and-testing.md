# Native image en testen

## GraalVM native image is het productie-artefact

Het Docker-image bevat een GraalVM native image (`./gradlew nativeCompile`, `Dockerfile.native`),
en dat legt beperkingen op aan hoe je code schrijft:

- **Gebruik nooit `@ConditionalOnProperty` voor een feature die op runtime togglebaar moet zijn.**
  Bean-conditions worden geëvalueerd en bevroren op het *build*-moment van het native image; een
  conditionele bean wordt uit het productie-image gebakken en geen property krijgt hem terug. Check
  de vlag in plaats daarvan **binnen** de bean — de Typesense-handlers zijn het referentievoorbeeld
  ([hoofdstuk 6](06-search.md)).
- **Reflectie- en resource-hints worden met de hand bijgehouden**, per module onder
  `src/main/resources/META-INF/native-image/`. Niets genereert ze; er eentje vergeten merk je pas
  in het native image, niet in JVM-runs.
- Een properties-bestand dat via `spring.config.import` geïmporteerd wordt heeft een entry nodig in
  de `resource-config.json` van zijn module (zie `search/src/main/resources/META-INF/native-image/`).
- **AWT werkt in het native image, maar alleen met handmatig aangeleverde JNI-hints.** PDFBox
  raakt AWT zelfs voor een simpele paginatelling (de statische init van `PDDocument` warmt het
  kleursubsysteem op), en de AWT-native-libraries van de JDK zoeken klassen, velden en methodes op
  via JNI op runtime — elk daarvan moet in `disk/.../native-image/jni-config.json` staan, anders
  faalt de lookup en sneuvelt de class-initializer voor de rest van het procesleven. De
  charset-vlag `-H:+AddAllCharsets` (server `build.gradle`) hoort bij hetzelfde verhaal: PDFBox'
  `BaseParser` wil Windows-1252, dat het image standaard weglaat. De gegenereerde `libawt*.so`-
  bestanden komen naast de binary terecht in `build/native/nativeCompile` en reizen mee
  (`Dockerfile.native` kopieert de hele map).

## Databaseschema-discipline

Prod draait `hibernate.ddl-auto=validate`: een entity-wijziging zonder bijpassende
Flyway-migratie laat de startup falen. Migraties staan in
`database/src/main/resources/db/migration/V<n>__<name>.sql` (laatste: `V24`), zijn
**forward-only** en worden ook als apart image uitgeleverd (`Dockerfile.migrations`). Bewerk nooit
een migratie die ergens al toegepast is.

## Testen

- Unit-tests zijn JUnit 5 + Mockito; Mockito draait als `-javaagent`, aangesloten in de
  root-`build.gradle`.
- `jimfs` (in-memory filesystem) draagt de disk-/bestandspad-tests.
- **ffmpeg moet op `PATH` staan** — de transcoder-tests shellen ernaar uit, en CI installeert het
  vóór de build.
- Integratietests zijn **geen** aparte source set of task. Ze draaien onder de normale `test`-task,
  heten `*IntegrationTest` en gebruiken Testcontainers PostgreSQL (en RabbitMQ voor de
  dead-letter-flow). Ze zijn geannoteerd met `@Testcontainers(disabledWithoutDocker = true)`, dus
  **ze worden stilletjes overgeslagen als er geen container-runtime bereikbaar is** — een groene
  `./gradlew test` bewijst niet dat ze gedraaid hebben. Lokaal:

  ```bash
  systemctl --user start podman.socket
  DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock ./gradlew test
  ```

Handige commando's:

```bash
./gradlew build                                      # alle modules bouwen
./gradlew :core:test                                 # één module
./gradlew :worker:test --tests "app.ister.worker.…"  # één klasse
```

## CI en kwaliteitsbewaking

CI draait `./gradlew build check jacocoTestReport sonar`. Er is geen aparte linter of formatter —
de kwaliteitsbewaking is SonarCloud (`org.sonarqube`) plus Jacoco-coverage, allebei onderdeel van
`check`.

## Commits en releases

Commit-subjects moeten [Conventional Commits](https://www.conventionalcommits.org/) zijn
(`feat(scope): …`, `fix: …`, `!`/`BREAKING CHANGE:` voor breaking); een `commit-lint`-job laat de
PR anders falen, en `.github/workflows/release.yml` leidt er zowel de versiebump als de release
notes uit af — zie `CONTRIBUTING.md`.

Releases worden nachtelijks en automatisch gemaakt. **Bump nooit met de hand `version` in
`build.gradle`**: `main` draagt altijd een `-SNAPSHOT`, en alleen de getagde release-commit draagt
een schone versie. `docker-publish.yml` tagt elke push naar main met de gradle-projectversie naast
`main`; de server- en migrations-images krijgen dezelfde tag, zodat downstream-pins in de pas
blijven lopen.
