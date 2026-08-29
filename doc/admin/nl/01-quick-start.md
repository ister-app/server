---
description: Probeer de Ister-mediaserver in een minuut of tien — een copy-paste Docker-Compose-quick-start met gebundelde Keycloak, een gegenereerde testvideo en een eerste scan.
---

# Quick start

Dit hoofdstuk brengt je in een minuut of tien van niets naar een draaiende server waarop je kunt
inloggen, met één gescande serie — op basis van de referentie-`docker-compose.yml` uit de
repository-root. Alles zit erin, inclusief een development-Keycloak met een kant-en-klare realm.
Zelf breng je alleen een TMDB-sleutel mee (optioneel) en, voor een testvideo, ffmpeg.

Dit is een **uitprobeer**-opstelling: development-identity-provider, standaardwachtwoorden,
wildcard-redirect-URI's. Bevalt het, dan beschrijft [Installatie](02-installation.md) hoe je er
iets blijvends van maakt.

## 1. Vereisten

- **Docker of Podman met Compose v2.** Het compose-bestand gebruikt
  `depends_on: condition: service_completed_successfully`, en dat begrijpt het oude Compose v1
  niet. `docker compose version` (of `podman-compose --version`) moet werken.
- **Een TMDB-API-sleutel (optioneel maar aangeraden).** Maak een account op
  [themoviedb.org](https://www.themoviedb.org/) en kopieer het **API Read Access Token** uit
  [Settings → API](https://www.themoviedb.org/settings/api). Zonder sleutel draait alles, maar
  blijven series en films zonder beschrijvingen en artwork.
- **ffmpeg op de host (optioneel)** — hieronder alleen gebruikt om een testvideo te genereren.
  Sla dit over als je de server op echte media richt.

## 2. Haal de stack binnen en configureer hem

Kloon de repository (of download alleen `docker-compose.yml` en `keycloak/Ister-realm.json`,
met dezelfde relatieve indeling):

```shell
git clone https://github.com/ister-app/server.git
cd server
```

Open `docker-compose.yml` en plak je TMDB-token in `APP_ISTER_SERVER_TMDB_APIKEY`
(of laat hem leeg om metadata over te slaan).

Eén regel hostconfiguratie is nodig: de OIDC-issuer-URL moet er voor de servercontainer en voor
je browser hetzelfde uitzien, en beide gebruiken `http://keycloak:8060`. Laat die hostnaam in
`/etc/hosts` naar localhost wijzen:

```shell
echo "127.0.0.1 keycloak" | sudo tee -a /etc/hosts
```

## 3. Maak een testserie (optioneel)

De scanner herkent media aan map- en bestandsnamen
([naamgevingsconventies](08-naming-conventions.md)). Genereer een testaflevering van drie
seconden:

```shell
mkdir -p "media/shows/Test Show (2024)/Season 01"
ffmpeg -f lavfi -i color=size=1280x720:rate=25:color=yellow \
  -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 \
  -map 0 -map 1 -metadata:s:a:0 language=eng -t 3 \
  "media/shows/Test Show (2024)/Season 01/Test Show - s01e01.mkv"
```

Echte media werkt precies zo: alles onder `./media/shows/` wordt gescand als de library
`shows`.

## 4. Starten

```shell
docker compose up -d
```

De eerste start trekt de images binnen, draait de Flyway-migraties, importeert de
Keycloak-realm en start de server. Controleer of de server op is (de management-endpoints op
poort 8081 zijn alleen binnen het compose-netwerk bereikbaar; wil je die vanaf de host curlen,
voeg dan `- "8081:8081"` toe aan de ports van de server):

```shell
curl -s http://localhost:8080/.well-known/ister
```

Een JSON-antwoord betekent dat de server draait.

De gebundelde realm bevat één testaccount, **`ister` / `ister`**, met zowel de rol `user` als
`admin`. De Keycloak-beheerconsole zelf staat op
[http://keycloak:8060](http://keycloak:8060) (`admin` / `admin`).

## 5. Scannen en controleren — zonder client

Haal een token op voor de testgebruiker (de client `ister` heeft direct-access grants aan, dus
een password-grant vanuit curl werkt):

```shell
TOKEN=$(curl -s -d 'grant_type=password&client_id=ister&username=ister&password=ister' \
  http://keycloak:8060/realms/Ister/protocol/openid-connect/token | \
  python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
```

Start een scan van alle libraries (een mutation die alleen admins mogen aanroepen):

```shell
curl -s http://localhost:8080/graphql \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"mutation { scanLibraries }"}'
```

Scannen is asynchroon — het loopt via RabbitMQ, en met een TMDB-sleutel kost het ophalen van
metadata een paar seconden extra. Vraag daarna op wat er gevonden is:

```shell
curl -s http://localhost:8080/graphql \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"query { shows { totalElements content { name releaseYear } } }"}'
```

`"totalElements":1` met je testserie in `content` betekent dat de hele pijplijn — scanner,
RabbitMQ, database, authenticatie — werkt.

## 6. Kijken met de player

Installeer een [Ister-player-app](https://github.com/ister-app/player), richt hem op
`http://localhost:8080` en log in als `ister` / `ister` via de gebundelde Keycloak. Afspelen
transcodeert on-the-fly naar HLS; de eerste segmenten van een stream laten een paar seconden op
zich wachten.

## En dan verder

- Echte media en de andere library-typen (films, muziek, boeken, comics, podcasts) —
  [Libraries en media-indeling](04-libraries-and-media-layout.md)
- Deze stack houden: vastgepinde image-tags, back-ups, healthmonitoring —
  [Installatie](02-installation.md) en [Onderhoud](07-maintenance-and-troubleshooting.md)
- De development-Keycloak vervangen door je eigen OIDC-provider, en hoe de rol `admin` werkt —
  [Gebruikers, delen en toegang](09-users-sharing-and-access.md)
- Alles wat je kunt instellen — [Configuratie](03-configuration.md)
