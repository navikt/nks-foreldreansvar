# nks-foreldreansvar

[![Build and deploy](https://github.com/navikt/nks-foreldreansvar/actions/workflows/main.yaml/badge.svg)](https://github.com/navikt/nks-foreldreansvar/actions/workflows/main.yaml)

Backend for foreldreansvar in Nav Kontaktsenter's Salesforce solution. It looks up parental responsibility (*foreldreansvar*) in [PDL](https://pdl-docs.ansatt.nav.no/) and keeps Salesforce updated when it changes.

Kotlin, [Ktor](https://ktor.io), Java 21, Gradle. Runs on [Nais](https://doc.nais.io) (dev-gcp).

## What it does

- **API for Salesforce** (via saas-proxy, Entra ID machine-to-machine token):
  - `POST /api/foreldreansvar/barn`: the children a parent has foreldreansvar for.
  - `POST /api/foreldreansvar/verifiser`: whether a parent can represent a child.
  - `POST /api/foreldreansvar/ansvarlige`: everyone with foreldreansvar for a child.
- **PDL events:** reads `pdl.leesah-v1` and sends the current foreldreansvar for changed children to Salesforce.
- Persons with adressebeskyttelse (kode 6/7) are always excluded.

Errors use [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) problem details. Health and metrics: `/internal/isalive`, `/internal/isready`, `/internal/metrics`.

Design and operations are documented internally in teamnks' Confluence.

## Structure

```text
src/main/kotlin/no/nav/nks/foreldreansvar/
  Bootstrap.kt, Application.kt   startup, plugins and routes
  Auth.kt, AzureConfig.kt        Entra ID token validation
  *Api.kt, ApiRoutes.kt          the /api endpoints
  pdl/                           PDL client
  leesah/                        Kafka consumer for PDL events
  salesforce/                    Salesforce client
src/main/resources/pdl/          GraphQL queries
.nais/app.yaml                   Nais configuration
```

## Run locally

Needs Java 17+ to run Gradle; the wrapper downloads Gradle and a Java 21 toolchain. The app stops at startup if a required env var is missing. Use [mock-oauth2-server](https://github.com/navikt/mock-oauth2-server) as a fake Entra ID (`local/mock-oauth2-server.json`):

```sh
docker run --rm -p 8081:8080 -e JSON_CONFIG="$(cat local/mock-oauth2-server.json)" \
  ghcr.io/navikt/mock-oauth2-server:6.0.5
```

In another terminal:

```sh
export AZURE_APP_CLIENT_ID=nks-foreldreansvar-local
export AZURE_OPENID_CONFIG_ISSUER=http://localhost:8081/azuread
export AZURE_OPENID_CONFIG_JWKS_URI=http://localhost:8081/azuread/jwks
export AZURE_APP_PRE_AUTHORIZED_APPS='[{"name":"local:platforce:saas-proxy","clientId":"saas-proxy-local"}]'
# PDL is not called at startup, so dummy values are enough to run the app locally.
export PDL_URL=http://localhost:9999/graphql PDL_TARGET=local:pdl:pdl-api PDL_BEHANDLINGSNUMMER=B118
export NAIS_TOKEN_ENDPOINT=http://localhost:9999/token
# There is no Kafka locally. Salesforce is off unless SALESFORCE_ENABLED=true.
export LEESAH_ENABLED=false
# Readable logs instead of JSON.
export LOG_FORMAT=plain
./gradlew run
```

```sh
curl http://localhost:8080/internal/isalive
curl http://localhost:8080/internal/metrics
curl -i -X POST http://localhost:8080/api/foreldreansvar/barn   # 401

TOKEN=$(curl -s -X POST http://localhost:8081/azuread/token \
  -d grant_type=client_credentials -d client_id=saas-proxy-local -d client_secret=secret \
  -d scope=api://nks-foreldreansvar-local/.default | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "{}" http://localhost:8080/api/foreldreansvar/barn   # 400: auth passed, ident missing
```

Locally there is no PDL, so a valid ident gives `502`. Use the tests to see real data.

```sh
./gradlew check
```

`check` runs tests and Kotlin lint checks. Use `./gradlew formatKotlin` to format Kotlin sources.

## Build an executable JAR

```sh
./gradlew shadowJar
java -jar build/libs/app.jar   # needs the AZURE_* env vars, see above
```

## Run in Docker

```sh
docker build -t nks-foreldreansvar .
docker run --rm -p 8080:8080 -e AZURE_APP_CLIENT_ID -e AZURE_OPENID_CONFIG_ISSUER \
  -e AZURE_OPENID_CONFIG_JWKS_URI -e AZURE_APP_PRE_AUTHORIZED_APPS nks-foreldreansvar
```

## Deploy

The workflow runs only when started manually: **Actions > Build and deploy > Run workflow** on `main`. It builds the image and deploys `.nais/app.yaml` to dev-gcp.
