# nks-foreldreansvar

A Kotlin application using [Ktor](https://ktor.io) with the Netty engine. It uses Java 21, Gradle with Groovy build files, JUnit 5, Kotlinter and Shadow.

For now the app only exposes the health endpoints Nais needs. Auth, PDL, Kafka, configuration and observability will be added later.

### Why Ktor and Jackson?

- **Ktor** is a lightweight Kotlin web framework that is widely used in Nav. An app is built from a `module()` function, where you install *plugins* (such as JSON and later auth) and define routes.
- **Jackson** (`ktor-serialization-jackson`) converts between JSON and Kotlin classes. It is the most common choice in Nav libraries and examples, and it works with ordinary `data class`es without extra annotations or compiler plugins.

## Structure

```text
src/main/kotlin/no/nav/nks/foreldreansvar/
  Bootstrap.kt       main(): starts the Netty server on port 8080
  Application.kt     Application.module(): installs plugins (JSON) and routes
  InternalRoutes.kt  /internal/isalive and /internal/isready
src/test/kotlin/     Tests using ktor-server-test-host
.nais/app.yaml       Nais deployment configuration
```

## Run locally

With Java 17 or newer installed to run Gradle 9 (the Gradle wrapper downloads Gradle automatically). The app compiles with a Java 21 toolchain; if Java 21 is not installed, Gradle downloads it automatically:

```sh
./gradlew run
curl http://localhost:8080/internal/isalive
curl http://localhost:8080/internal/isready
```

> Gradle 8.11 cannot run on Java 25. If you have several JDKs installed, point `JAVA_HOME` to Java 21.

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

Pushes to `main` build and publish the Docker image, then deploy using `.nais/app.yaml`. Nais uses `/internal/isalive` (liveness) and `/internal/isready` (readiness) as health probes.
