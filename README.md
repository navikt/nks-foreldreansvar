# nks-foreldreansvar

A Kotlin application using [Ktor](https://ktor.io) with the Netty engine. It uses Java 21, Gradle with Groovy build files, JUnit 5, Kotlinter and Shadow.

For now the app exposes the health endpoints Nais needs and a protected test endpoint, `GET /api/ping`. All `/api/**` routes require an Entra ID machine-to-machine token (see [Authentication](#authentication)). PDL, Kafka and observability will be added later.

### Why Ktor and Jackson?

- **Ktor** is a lightweight Kotlin web framework that is widely used in Nav. An app is built from a `module()` function, where you install *plugins* (such as JSON and auth) and define routes.
- **Jackson** (`ktor-serialization-jackson`) converts between JSON and Kotlin classes. It is the most common choice in Nav libraries and examples, and it works with ordinary `data class`es without extra annotations or compiler plugins.

## Structure

```text
src/main/kotlin/no/nav/nks/foreldreansvar/
  Bootstrap.kt       main(): reads config and starts the Netty server on port 8080
  Application.kt     Application.module(): installs plugins (JSON, auth) and routes
  AzureConfig.kt     Entra ID settings read from env vars injected by Nais
  Auth.kt            JWT validation of Entra ID machine-to-machine tokens
  InternalRoutes.kt  /internal/isalive and /internal/isready (open)
  ApiRoutes.kt       /api/** routes (require a token)
src/test/kotlin/     Tests using ktor-server-test-host and mock-oauth2-server
local/               Config for running mock-oauth2-server locally
.nais/app.yaml       Nais deployment configuration
```

## Authentication

Salesforce calls this app machine-to-machine (no user involved). It gets an access token from Entra ID (Azure AD) with the *client credentials* flow and sends it as `Authorization: Bearer <token>`.

### What Nais does

`.nais/app.yaml` enables `azure.application`. Nais then creates an app registration in Entra ID for us and injects these env vars:

| Variable | Used for |
|---|---|
| `AZURE_APP_CLIENT_ID` | Our client ID. Tokens must have it as audience (`aud`). |
| `AZURE_OPENID_CONFIG_ISSUER` | Expected issuer (`iss`). |
| `AZURE_OPENID_CONFIG_JWKS_URI` | Public keys used to check the token signature. |
| `AZURE_APP_PRE_AUTHORIZED_APPS` | JSON list of clients allowed in `accessPolicy.inbound`. |

`accessPolicy.inbound` says who may call us. Entra ID refuses to issue tokens for our app to anyone not listed there.

> **TODO:** The Salesforce rule in `.nais/app.yaml` is a placeholder (`todo-salesforce-*`) until we have confirmed which app registration Salesforce uses. Other Nav Salesforce integrations use `application: salesforce`, `namespace: teamcrm`, `cluster: dev-external`. Until then no one can get a token for this app in dev.

### What the app checks

`Auth.kt` uses Ktor's `Authentication` plugin with `jwt`. A request to `/api/**` is accepted only if:

1. The signature is valid (key from JWKS) and the token has not expired.
2. `iss` equals `AZURE_OPENID_CONFIG_ISSUER`.
3. `aud` equals `AZURE_APP_CLIENT_ID`.
4. `idtyp` is `app`, meaning a machine-to-machine token and not a user token.
5. `roles` contains `access_as_application`, the role Nais gives to clients in the access policy.
6. `azp` (the calling client's ID) is in `AZURE_APP_PRE_AUTHORIZED_APPS`. This limits callers to the access policy without hardcoding Salesforce's client ID.

Otherwise the app returns `401 Unauthorized`. `/internal/**` is always open, because Nais health checks call it without a token.

`GET /api/ping` returns `pong`. It is a temporary endpoint for verifying auth, and will be replaced by the real PDL endpoints.

### Calling the app in dev

A client in the access policy requests a token from the dev tenant with the scope `api://dev-gcp.teamnks.nks-foreldreansvar/.default`:

```sh
curl -s -X POST "https://login.microsoftonline.com/trygdeetaten.no/oauth2/v2.0/token" \
  -d grant_type=client_credentials \
  -d client_id=<client id> -d client_secret=<client secret> \
  -d scope=api://dev-gcp.teamnks.nks-foreldreansvar/.default
```

## Run locally

With Java 17 or newer installed to run Gradle 9 (the Gradle wrapper downloads Gradle automatically). The app compiles with a Java 21 toolchain; if Java 21 is not installed, Gradle downloads it automatically.

The app will not start without the `AZURE_*` env vars; it stops with an error naming the missing variable. Locally, use [mock-oauth2-server](https://github.com/navikt/mock-oauth2-server) as a fake Entra ID. `local/mock-oauth2-server.json` makes it issue Salesforce-like tokens for client `salesforce-local`:

```sh
docker run --rm -p 8081:8080 -e JSON_CONFIG="$(cat local/mock-oauth2-server.json)" \
  ghcr.io/navikt/mock-oauth2-server:6.0.5
```

In another terminal:

```sh
export AZURE_APP_CLIENT_ID=nks-foreldreansvar-local
export AZURE_OPENID_CONFIG_ISSUER=http://localhost:8081/azuread
export AZURE_OPENID_CONFIG_JWKS_URI=http://localhost:8081/azuread/jwks
export AZURE_APP_PRE_AUTHORIZED_APPS='[{"name":"local:salesforce","clientId":"salesforce-local"}]'
./gradlew run
```

```sh
curl http://localhost:8080/internal/isalive
curl -i http://localhost:8080/api/ping   # 401

TOKEN=$(curl -s -X POST http://localhost:8081/azuread/token \
  -d grant_type=client_credentials -d client_id=salesforce-local -d client_secret=secret \
  -d scope=api://nks-foreldreansvar-local/.default | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/ping   # pong
```

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

The multi-stage build runs checks and packages the application and resources into an executable JAR. The final image uses distroless Java 21 and runs as a non-root user.

Pushes to `main` build and publish the Docker image, then deploy using `.nais/app.yaml`. Nais uses `/internal/isalive` (liveness) and `/internal/isready` (readiness) as health probes.
