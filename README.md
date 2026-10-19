# nks-foreldreansvar

A Kotlin application using [Ktor](https://ktor.io) with the Netty engine. It uses Java 21, Gradle with Groovy build files, JUnit 5, Kotlinter and Shadow.

The app exposes the health endpoints Nais needs, `POST /api/foreldreansvar/barn` and `POST /api/foreldreansvar/verifiser` (foreldreansvar and persons from [PDL](#pdl), see [API](#api)). All `/api/**` routes require an Entra ID machine-to-machine token (see [Authentication](#authentication)). Kafka and observability will be added later.

### Why Ktor and Jackson?

- **Ktor** is a lightweight Kotlin web framework that is widely used in Nav. An app is built from a `module()` function, where you install *plugins* (such as JSON and auth) and define routes.
- **Jackson** (`ktor-serialization-jackson`) converts between JSON and Kotlin classes. It is the most common choice in Nav libraries and examples, and it works with ordinary `data class`es without extra annotations or compiler plugins.

## Structure

```text
src/main/kotlin/no/nav/nks/foreldreansvar/
  Bootstrap.kt       main(): reads config and starts the Netty server on port 8080
  Application.kt     Application.module(): installs plugins (JSON, error handling, auth) and routes
  AzureConfig.kt     Entra ID settings read from env vars injected by Nais
  Auth.kt            JWT validation of Entra ID machine-to-machine tokens
  InternalRoutes.kt  /internal/isalive and /internal/isready (open)
  ApiRoutes.kt       /api/** routes (require a token)
  BarnApi.kt         Request/response classes and ident validation for POST /api/foreldreansvar/barn
  VerifiserApi.kt    Request/response classes and the check for POST /api/foreldreansvar/verifiser
  ErrorHandling.kt   Maps exceptions to RFC 7807 problem-details responses
  pdl/               PDL client: config, Texas token client, GraphQL client, models and errors
src/main/resources/pdl/  The GraphQL queries sent to PDL (foreldreansvar, person-bolk)
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

### How Salesforce reaches the app

Salesforce runs outside Nav's network and cannot call apps in the cluster directly. It goes through [saas-proxy](https://github.com/navikt/saas-proxy) (team platforce):

```
Salesforce --(Entra ID token for saas-proxy)--> saas-proxy --(new Entra ID token for us)--> nks-foreldreansvar
```

1. Salesforce gets a token for saas-proxy and calls `https://saas-proxy.ekstern.dev.nav.no/<our path>`, e.g. `/api/foreldreansvar/barn`, with the header `target-app: nks-foreldreansvar` (and optionally `target-namespace: teamnks`).
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

## API

### `POST /api/foreldreansvar/barn`

Returns the children the parent has parental responsibility (*foreldreansvar*) for right now, with name and birth data from PDL. Salesforce will call it from `NKS_ForeldreansvarService` in [crm-nks-integration](https://github.com/navikt/crm-nks-integration).

Request (`Content-Type: application/json`):

```json
{ "ident": "01020312345" }
```

`ident` is the parent's ident. It is required and must be exactly 11 digits. We do not check the mod11 checksum: synthetic test idents do not always pass it, and PDL validates the ident itself.

Response `200 OK`:

```json
{
  "barn": [
    { "ident": "10101012345", "fornavn": "Ola", "etternavn": "Nordmann", "foedselsdato": "2015-03-01", "foedselsaar": 2015, "alder": 10 },
    { "ident": "20202012345", "fornavn": null, "etternavn": null, "foedselsdato": null, "foedselsaar": 2018, "alder": null }
  ]
}
```

- We look up the parent's foreldreansvar with `historikk = false`, take each `ansvarssubjekt` once (without the parent's own ident), and look the children up with `hentPersonBolk`.
- `fornavn`/`etternavn` come from the current name. `foedselsdato` and `foedselsaar` are passed on as PDL returns them. Missing values are `null`. `alder` is whole years from `foedselsdato` to today (Europe/Oslo). It is `null` if `foedselsdato` is missing, since `foedselsaar` alone could give an age that is one year too high.
- Children PDL does not return with `code: ok` (e.g. `not_found`) are left out. Only the codes are logged.
- No children gives `200` with an empty list. If the parent does not exist in PDL, the response is `404`.
- For now we return all children, including children with adressebeskyttelse and dead children.
- The order follows the parent's foreldreansvar.
- The response classes (`BarnApi.kt`) are separate from the PDL classes (`pdl/`), so changing a PDL query does not silently change the contract with Salesforce.

### `POST /api/foreldreansvar/verifiser`

Answers whether a parent can represent a child. Salesforce calls it with `NKS_ForeldreansvarService.verifiser(forelderIdent, barnIdent)`.

Request (`Content-Type: application/json`):

```json
{ "forelderIdent": "01020312345", "barnIdent": "10101012345" }
```

Both fields are required and must be exactly 11 digits (same rules as `ident` for `barn`).

Response `200 OK`:

```json
{ "kanRepresentere": true }
```

- We look up the parent's foreldreansvar the same way as `POST /api/foreldreansvar/barn` (`historikk = false`). `kanRepresentere` is `true` if `barnIdent` is an `ansvarssubjekt` in that list, otherwise `false`.
- Both `true` and `false` are `200`. If the parent does not exist in PDL, the response is `404` (see [Errors](#errors)), so an unknown ident is not mixed up with a parent without foreldreansvar for the child.
- For now we only check the list. `ansvar` values, age, death, adressebeskyttelse and fallbacks when `ansvarlig` is empty are not considered yet.

### Errors

Errors use [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) *problem details* with `Content-Type: application/problem+json`:

```json
{ "type": "about:blank", "title": "Not Found", "status": 404, "detail": "Person not found in PDL" }
```

| Status | When |
|---|---|
| `400` | `ident`, `forelderIdent` or `barnIdent` missing, blank or not 11 digits; body is not valid JSON; or PDL says the request is invalid (`bad_request`) |
| `401` | Missing or invalid token (see [Authentication](#authentication)) |
| `404` | The parent does not exist in PDL |
| `500` | We could not get a PDL token from Texas (our own sidecar, so an internal error), or another unexpected error |
| `502` | PDL denied *our* access, or answered with an error or something we could not read. This is 502 and not 401/403 because the caller did nothing wrong |
| `504` | PDL did not answer within 10 seconds |

The mapping is in `ErrorHandling.kt`, using Ktor's `StatusPages` plugin: routes just throw, and one place decides the HTTP response. `detail` is always a fixed text, never the exception message, because some exceptions (such as JSON parse errors) can contain the request body, which contains idents. Logs only contain the exception type and status.

Note: `NKS_ForeldreansvarService.verifiser()` returns the response for `200` and `404`, but throws `ForeldreansvarException` (and logs the response) for other statuses.

### Test end to end from Salesforce (dev)

The whole chain is Salesforce → saas-proxy → nks-foreldreansvar → PDL. saas-proxy allows `POST /api/foreldreansvar/barn` and `POST /api/foreldreansvar/verifiser`, and crm-nks-integration has `NKS_ForeldreansvarService.barn(ident)` and `verifiser(forelderIdent, barnIdent)`.

1. Log in to a Salesforce dev/scratch org where crm-nks-integration is deployed with the `FORELDREANSVAR_API` configuration.
2. Open the Developer Console → *Debug* → *Open Execute Anonymous Window*, or use `sf apex run`.
3. Paste the script below and replace the ident with a **synthetic** ident from [Dolly](https://dolly.ekstern.dev.nav.no/) or [Tenor](https://testdata.skatteetaten.no/web/testnorge/) that has foreldreansvar. Never use real idents.
4. Run it with *Open Log* checked and filter on *Debug Only*.

```apex
try {
    HttpResponse response = NKS_ForeldreansvarService.barn('<synthetic parent ident>');
    System.debug('Status: ' + response.getStatusCode());
    System.debug('Body: ' + response.getBody()); // {"barn":[{"ident":...,"fornavn":...,"etternavn":...,"foedselsdato":...,"foedselsaar":...,"alder":...}]}
} catch (NKS_ForeldreansvarService.ForeldreansvarException e) {
    // Thrown for statuses other than 2xx and 404. The response is logged in Application_Log__c with the LoggId.
    System.debug('Failed: ' + e.getMessage());
}
```

To test `verifiser`, use a synthetic parent and one of the parent's children:

```apex
try {
    HttpResponse response = NKS_ForeldreansvarService.verifiser('<synthetic parent ident>', '<synthetic child ident>');
    System.debug('Status: ' + response.getStatusCode());
    System.debug('Body: ' + response.getBody()); // {"kanRepresentere":true} or {"kanRepresentere":false}
} catch (NKS_ForeldreansvarService.ForeldreansvarException e) {
    System.debug('Failed: ' + e.getMessage());
}
```

Expected results:

| Result | Meaning |
|---|---|
| `Status: 200` and a `barn` list | The whole chain works |
| `Status: 404` | Chain works, but PDL does not know the ident |
| `Failed: ...`, log shows `401` | saas-proxy's token was rejected by us (access policy / pre-authorized apps) |
| `Failed: ...`, log shows `403` or `404` from saas-proxy | The path is not in saas-proxy's whitelist |
| `Failed: ...`, log shows `502` | PDL denied our access (behandlingsnummer, PDL access list) or failed |
| `Failed: ...`, log shows `500` | Texas could not give us a token, see the app logs |

Check the app logs in [Grafana/Loki](https://grafana.nav.cloud.nais.io) or with `kubectl logs -n teamnks -l app=nks-foreldreansvar`.

## PDL

The app reads parental responsibility (*foreldreansvar*) from [PDL](https://pdl-docs.ansatt.nav.no/) with `PdlClient.hentForeldreansvar(ident, historikk)`, and names and birth data for several persons with `PdlClient.hentPersonBolk(identer, historikk)`. `POST /api/foreldreansvar/barn` uses both; `POST /api/foreldreansvar/verifiser` uses `hentForeldreansvar`.

`hentPersonBolk` only works with a system (M2M) token, which is what we get from Texas. It will not work if we switch to TokenX/on-behalf-of tokens. Results with a `code` other than `ok` are left out, and only the codes are logged.

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

### Changing the queries

The queries are in `src/main/resources/pdl/`: `foreldreansvar.graphql` (classes in `pdl/Foreldreansvar.kt`) and `person-bolk.graphql` (classes in `pdl/PersonBolk.kt`).

1. Edit the `.graphql` file (for example by copying a query from the PDL playground).
2. Update the matching classes so they match the fields in the query. Unknown fields are ignored, so removing a field from a class is safe.
3. If you add or rename variables, update the `variables` map in `PdlClient.hentForeldreansvar` or `PdlClient.hentPersonBolk`.
4. Update the test responses in `src/test/resources/pdl/` and run `./gradlew check`.

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
curl -i -X POST http://localhost:8080/api/foreldreansvar/barn   # 401

TOKEN=$(curl -s -X POST http://localhost:8081/azuread/token \
  -d grant_type=client_credentials -d client_id=saas-proxy-local -d client_secret=secret \
  -d scope=api://nks-foreldreansvar-local/.default | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "{}" http://localhost:8080/api/foreldreansvar/barn   # 400: auth passed, ident missing
```

Locally there is no Texas or PDL, so `POST /api/foreldreansvar/barn` with a valid ident fails with `502` (nothing answers on the dummy Texas and PDL URLs). Use the tests or the [Salesforce test](#test-end-to-end-from-salesforce-dev) to see real data.

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
