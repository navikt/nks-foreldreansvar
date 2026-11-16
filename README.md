# nks-foreldreansvar

[![Build and deploy](https://github.com/navikt/nks-foreldreansvar/actions/workflows/main.yaml/badge.svg)](https://github.com/navikt/nks-foreldreansvar/actions/workflows/main.yaml)

Backend for foreldreansvar in Nav Kontaktsenter's Salesforce solution. It looks up parental responsibility (*foreldreansvar*) in [PDL](https://pdl-docs.ansatt.nav.no/) and keeps Salesforce updated when it changes.

Kotlin, [Ktor](https://ktor.io), Java 21, Gradle. Runs on [Nais](https://doc.nais.io) (dev-gcp).

## What it does

- **API for Salesforce** (via saas-proxy, Entra ID machine-to-machine token):
  - `POST /api/foreldreansvar/barn`: the children a parent has foreldreansvar for.
  - `POST /api/foreldreansvar/verifiser`: whether a parent can represent a child.
  - `POST /api/foreldreansvar/ansvarlige`: everyone with foreldreansvar for a child.
- **PDL events:** reads `pdl.leesah-v1`, looks up the current foreldreansvar for changed children and closes the open henvendelser (threads) of parents who no longer have it. See [Salesforce](#salesforce).
- Persons with adressebeskyttelse (kode 6/7) are always excluded.
- Children who are 18 or older, or whose age is unknown, count as not covered by foreldreansvar (`Myndighet.kt`). Turning 18 is not a PDL event, so the PDL event sync does not close threads for those children.

Idents are never written to the normal log (stdout), in any environment. Log lines with idents use the `TEAM_LOGS` marker (`TeamLogs.kt`) and only go to [Nais Team Logs](https://doc.nais.io/observability/logging/how-to/team-logs/); locally they are dropped.

Errors use [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) problem details. Health and metrics: `/internal/isalive`, `/internal/isready`, `/internal/metrics`.

Design and operations are documented internally in teamnks' Confluence.

## Salesforce

When a parent loses foreldreansvar, their open threads (`Thread__c`) on the child are closed. Access on the external site is already blocked by the live checks (`/verifiser`, `/barn`); this is clean-up. The app uses Salesforce's standard REST API, so there is no Apex of our own. Per batch of up to 50 children (`salesforce/SalesforceThreadCloser.kt`):

1. **Find** open threads with SOQL (`GET /services/data/v{version}/query`, following `nextRecordsUrl`): `CRM_isActive__c = true`, `CRM_Thread_Type__c = SALESFORCE_THREAD_TYPE`, child `CRM_Account__r.INT_PersonIdent__c` in the batch. The parent is `CRM_Contact__r.Account.INT_PersonIdent__c`.
2. **Decide** in the app (`ThreadsToClose.kt`): close a thread if its parent is not among the child's current ansvarlige. No ansvarlige closes all. Threads without a parent ident stay open and are only counted.
3. **Close** with sObject Collections (`PATCH /services/data/v{version}/composite/sobjects`, up to 200 per request, `allOrNone`): `CRM_isActive__c = false` and `CRM_Closed_By__c = SALESFORCE_CLOSED_BY`, the same fields as the close button in Salesforce. If any thread fails, the poll is retried; that is safe because closed threads are not found again.

| `SALESFORCE_ENABLED` | `SALESFORCE_LUKK_ENABLED` | What happens |
| --- | --- | --- |
| `false` | (ignored) | Salesforce is not called. Only counts are logged. |
| `true` | `false` | Steps 1 and 2: logs what would be closed, changes nothing. For testing in dev. |
| `true` | `true` | All steps: threads are closed. |

Environment variables (`.nais/app.yaml`): `SALESFORCE_LOGIN_URL`, `SALESFORCE_API_VERSION` (e.g. `62.0`), `SALESFORCE_THREAD_TYPE`, `SALESFORCE_CLOSED_BY` (only needed when closing). The Nais secret `nks-foreldreansvar-salesforce` holds `SALESFORCE_CLIENT_ID`, `SALESFORCE_USERNAME` and `SALESFORCE_PRIVATE_KEY` (JWT bearer flow; the certificate goes on the connected app).

The integration user needs:

- **Read:** `Thread__c` (`Id`, `CRM_isActive__c`, `CRM_Thread_Type__c`, `CRM_Account__c`, `CRM_Contact__c`), `Contact.AccountId` and `Account.INT_PersonIdent__c`.
- **Edit:** `Thread__c.CRM_isActive__c` and `Thread__c.CRM_Closed_By__c`.
- **Sharing:** the REST API respects sharing, so the user must see every thread: *View All* and *Modify All* on `Thread__c`, or a matching sharing rule.

Before setting `SALESFORCE_ENABLED` to `true`, add the instance host (from the token's `instance_url`) to `accessPolicy.outbound.external` and uncomment `envFrom` in `.nais/app.yaml`.

Logging: stdout only has counts. [Team Logs](https://doc.nais.io/observability/logging/how-to/team-logs/) get one line per closed (or would-be closed) thread with thread Id, child and parent ident and hendelseId. Metrics (no idents), tagged `dry_run`: `foreldreansvar_threads_found_total`, `foreldreansvar_threads_closed_total` and `foreldreansvar_threads_without_parent_total`; HTTP calls in `salesforce_requests_seconds{operation=token|query|close}`.

## Structure

```text
src/main/kotlin/no/nav/nks/foreldreansvar/
  Bootstrap.kt, Application.kt   startup, plugins and routes
  Auth.kt, AzureConfig.kt        Entra ID token validation
  *Api.kt, ApiRoutes.kt          the /api endpoints
  pdl/                           PDL client
  leesah/                        Kafka consumer for PDL events
  salesforce/                    closes threads in Salesforce
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
# There is no Kafka locally. Salesforce is not called unless SALESFORCE_ENABLED=true.
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
