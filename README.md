# nks-foreldreansvar

A Kotlin application using http4k and Netty, following the stack used by `sf-pubsub`: Java 21, Gradle with Groovy build files, JUnit 5, Kotlinter, and Shadow.

The existing Hello Nais page shows a counter you can increment with a button. The count lives in memory, is separate for each application instance, and resets when the app restarts.

## Structure

```text
src/main/kotlin/no/nav/nks/foreldreansvar/
  Bootstrap.kt       Server startup and shutdown
  Application.kt     HTTP routes and in-memory counter
src/main/resources/  Page and logo
src/test/kotlin/     HTTP handler tests
.nais/app.yaml       Nais deployment configuration
```

## Run locally

With Java 21 installed (the Gradle wrapper downloads Gradle automatically):

```sh
./gradlew run
```

Open http://localhost:8080 and click the button. The health endpoint is `GET /healthz`.

```sh
./gradlew check
```

`check` runs tests and Kotlin lint checks. Use `./gradlew formatKotlin` to format Kotlin sources.

## Build an executable JAR

```sh
./gradlew shadowJar
java -jar build/libs/app.jar
```

## Run in Docker

```sh
docker build -t nks-foreldreansvar .
docker run --rm -p 8080:8080 nks-foreldreansvar
```

The multi-stage build runs checks and packages the application and resources into an executable JAR. The final image uses distroless Java 21 and runs as a non-root user.

Pushes to `main` build and publish the Docker image, then deploy using `.nais/app.yaml`. Both Nais health probes use `/healthz`.
