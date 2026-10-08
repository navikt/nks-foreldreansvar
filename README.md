# Hello Nais

A tiny Go application for the [Hello Nais tutorial](https://doc.nais.io/tutorials/hello-nais/). It shows a counter you can increment with a button. For now, the count lives in memory and resets when the app restarts. A later exercise can move the count to Postgres and make that difference visible.

This template contains the application and its Dockerfile. You create the Nais manifest and GitHub Actions workflow yourself in the tutorial.

## Run locally

With Go 1.27 or newer:

```sh
go run .
```

Open http://localhost:8080 and click the button. The health endpoint is `GET /healthz`.

```sh
go test ./...
```

## Run in Docker

```sh
docker build -t hello .
docker run --rm -p 8080:8080 hello
```

The final image is `scratch`: it contains only the statically linked application binary (including the page and logo) and runs as a non-root user.
