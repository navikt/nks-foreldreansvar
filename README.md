# nks-foreldreansvar

A Kotlin application using [Ktor](https://ktor.io) with the Netty engine. It uses Java 21, Gradle with Groovy build files, JUnit 5, Kotlinter and Shadow.

For now the app exposes the health endpoints Nais needs and a protected test endpoint, `GET /api/ping`. All `/api/**` routes require an Entra ID machine-to-machine token (see [Authentication](#authentication)). The app has a client for [PDL](#pdl), which no endpoint uses yet. Kafka and observability will be added later.

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
  pdl/               PDL client: config, Texas token client, GraphQL client, models and errors
src/main/resources/pdl/foreldreansvar.graphql   The GraphQL query sent to PDL
src/test/kotlin/     Tests using ktor-server-test-host, mock-oauth2-server and Ktor MockEngine
src/test/resources/pdl/  Recorded PDL-style responses used by the PDL tests
local/               Config for running mock-oauth2-server locally
.nais/app.yaml       Nais deployment configuration
```

## Authentication

Salesforce reaches this app through [saas-proxy](https://github.com/navikt/saas-proxy) (see below). The call to us is machine-to-machine (no user involved): saas-proxy gets an access token for our app from Entra ID (Azure AD) with the *client credentials* flow and sends it as `Authorization: Bearer <token>`. The token's caller (`azp`) is therefore saas-proxy, not Salesforce.

### What Nais does

`.nais/app.yaml` enables `azure.application`. Nais then creates an app registration in Entra ID for us and injects these env vars:

| Variable | Used for |
|---|---|
| `AZURE_APP_CLIENT_ID` | Our client ID. Tokens must have it as audience (`aud`). |
| `AZURE_OPENID_CONFIG_ISSUER` | Expected issuer (`iss`). |
| `AZURE_OPENID_CONFIG_JWKS_URI` | Public keys used to check the token signature. |
| `AZURE_APP_PRE_AUTHORIZED_APPS` | JSON list of clients allowed in `accessPolicy.inbound`. |

`accessPolicy.inbound` says who may call us. Entra ID refuses to issue tokens for our app to anyone not listed there.

> **Note:** The inbound rule allows `application: saas-proxy`, `namespace: platforce`. Salesforce does not call us directly, see [How Salesforce reaches the app](#how-salesforce-reaches-the-app).

### What the app checks

`Auth.kt` uses Ktor's `Authentication` plugin with `jwt`. A request to `/api/**` is accepted only if:

1. The signature is valid (key from JWKS) and the token has not expired.
2. `iss` equals `AZURE_OPENID_CONFIG_ISSUER`.
3. `aud` equals `AZURE_APP_CLIENT_ID`.
4. `idtyp` is `app`, meaning a machine-to-machine token and not a user token.
5. `roles` contains `access_as_application`, the role Nais gives to clients in the access policy.
6. `azp` (the calling client's ID) is in `AZURE_APP_PRE_AUTHORIZED_APPS`. This limits callers to the access policy without hardcoding saas-proxy's client ID.

Otherwise the app returns `401 Unauthorized`. `/internal/**` is always open, because Nais health checks call it without a token.

`GET /api/ping` returns `pong`. It is a temporary endpoint for verifying auth, and will be replaced by the real PDL endpoints.

### How Salesforce reaches the app

Salesforce runs outside Nav's network and cannot call apps in the cluster directly. It goes through [saas-proxy](https://github.com/navikt/saas-proxy) (team platforce):

```
Salesforce --(Entra ID token for saas-proxy)--> saas-proxy --(new Entra ID token for us)--> nks-foreldreansvar
```

1. Salesforce gets a token for saas-proxy and calls `https://saas-proxy.ekstern.dev.nav.no/<our path>`, e.g. `/api/ping`, with the header `target-app: nks-foreldreansvar` (and optionally `target-namespace: teamnks`).
2. saas-proxy checks that the method and path are in its whitelist (`src/main/resources/whitelist/dev.json` in the saas-proxy repo, under `teamnks` → `nks-foreldreansvar`).
3. saas-proxy fetches a new token for our app *as itself* and forwards the request in-cluster to `http://nks-foreldreansvar.teamnks`.
4. We validate the token as described above. `azp` is saas-proxy's client ID.

For this to work, three things must be in place:

- **Our app** (`.nais/app.yaml`): inbound rule for `application: saas-proxy`, `namespace: platforce`. Both apps run in dev-gcp, so no `cluster` is needed.
- **saas-proxy** (`.nais/dev.yaml`): outbound rule for `application: nks-foreldreansvar`, `namespace: teamnks`.
- **saas-proxy** (`whitelist/dev.json`): the endpoints Salesforce may call, e.g. `"GET /api/.*"`.

The last two are changed by pull request to the saas-proxy repo.

### Calling the app in dev

A client in the access policy (in practice saas-proxy) requests a token from the dev tenant with the scope `api://dev-gcp.teamnks.nks-foreldreansvar/.default`:

```sh
curl -s -X POST "https://login.microsoftonline.com/trygdeetaten.no/oauth2/v2.0/token" \
  -d grant_type=client_credentials \
  -d client_id=<client id> -d client_secret=<client secret> \
  -d scope=api://dev-gcp.teamnks.nks-foreldreansvar/.default
```

## PDL

The app reads parental responsibility (*foreldreansvar*) from [PDL](https://pdl-docs.ansatt.nav.no/) with `PdlClient.hentForeldreansvar(ident, historikk)`. No endpoint uses it yet.

### Token: Texas

PDL requires an Entra ID machine-to-machine token. We get it from **Texas**, a token sidecar that Nais starts next to every app with `azure.application.enabled: true`. Nais sets `NAIS_TOKEN_ENDPOINT`, and the app POSTs `identity_provider=entra_id` and `target=dev-fss:pdl:pdl-api` to it ([Nais docs](https://doc.nais.io/auth/entra-id/how-to/consume-m2m/)).

Why Texas instead of calling Entra ID ourselves: Texas holds the client credentials and caches tokens until shortly before they expire, so the app needs no secret, no token cache and no OAuth library. This is the current Nais standard. If PDL answers HTTP 401, the client asks Texas once more with `skip_cache=true` and retries once.

### Network and access

- PDL runs in dev-fss (on-prem). From dev-gcp we reach it through `https://pdl-api.dev-fss-pub.nais.io/graphql`, which is allowed with `accessPolicy.outbound.external` in `.nais/app.yaml`. `outbound.rules` only covers apps in the same GCP environment, so no rule is needed.
- Each request sends the header `Behandlingsnummer: B118`, the registered purpose for our lookups.
- PDL must also grant our app access (behandlingsnummer and their own access list). That is a manual request to the PDL team, not something this repo controls. Until it is done, PDL answers `unauthorized`.

### Environment variables

| Variable | Set by | Dev value |
| --- | --- | --- |
| `PDL_URL` | `.nais/app.yaml` | `https://pdl-api.dev-fss-pub.nais.io/graphql` |
| `PDL_TARGET` | `.nais/app.yaml` | `dev-fss:pdl:pdl-api` |
| `PDL_BEHANDLINGSNUMMER` | `.nais/app.yaml` | `B118` |
| `NAIS_TOKEN_ENDPOINT` | Nais (Texas) | injected |

The app does not start if any of them is missing.

### Errors and personal data

All failures are thrown as `PdlException`: `NotFound`, `Unauthorized`, `BadRequest`, `TokenUnavailable`, `Timeout` (10 s per request) or `Unexpected` (HTTP 5xx, unknown GraphQL error codes). PDL answers GraphQL errors with HTTP 200 and an `errors` array; the client maps `extensions.code` to these types.

Idents are personal data. They are sent only as GraphQL variables and are never logged or put in exception messages. Logs contain only HTTP status and PDL error codes.

### Changing the query

1. Edit `src/main/resources/pdl/foreldreansvar.graphql` (for example by copying a query from the PDL playground).
2. Update the classes in `pdl/Foreldreansvar.kt` so they match the fields in the query. Unknown fields are ignored, so removing a field from a class is safe.
3. If you add or rename variables, update the `variables` map in `PdlClient.hentForeldreansvar`.
4. Update `src/test/resources/pdl/foreldreansvar-ok.json` and run `./gradlew check`.

## Run locally

With Java 17 or newer installed to run Gradle 9 (the Gradle wrapper downloads Gradle automatically). The app compiles with a Java 21 toolchain; if Java 21 is not installed, Gradle downloads it automatically.

The app will not start without the `AZURE_*` and PDL env vars; it stops with an error naming the missing variable. Locally, use [mock-oauth2-server](https://github.com/navikt/mock-oauth2-server) as a fake Entra ID. `local/mock-oauth2-server.json` makes it issue saas-proxy-like tokens for client `saas-proxy-local`:

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
./gradlew run
```

```sh
curl http://localhost:8080/internal/isalive
curl -i http://localhost:8080/api/ping   # 401

TOKEN=$(curl -s -X POST http://localhost:8081/azuread/token \
  -d grant_type=client_credentials -d client_id=saas-proxy-local -d client_secret=secret \
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

Inside a container, `localhost` is the container itself, so it cannot reach a mock-oauth2-server at `localhost:8081`. To run both in Docker, put them on the same Docker network and use the mock's container name in the URLs (e.g. `http://mock-oauth:8080/azuread`). The token's issuer must match too, so request the token with `-H "Host: mock-oauth:8080"`.

The multi-stage build runs checks and packages the application and resources into an executable JAR. The final image uses distroless Java 21 and runs as a non-root user.

Pushes to `main` build and publish the Docker image, then deploy using `.nais/app.yaml`. Nais uses `/internal/isalive` (liveness) and `/internal/isready` (readiness) as health probes.
