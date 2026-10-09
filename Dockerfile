FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN ./gradlew --no-daemon check shadowJar

FROM gcr.io/distroless/java21:nonroot
# Ktor resolves the working directory at startup and crashes if it doesn't exist.
WORKDIR /app
COPY --from=build /app/build/libs/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
