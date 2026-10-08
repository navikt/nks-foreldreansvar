FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod main.go index.html logo.svg ./
RUN CGO_ENABLED=0 go build -trimpath -o /hello .

FROM scratch
COPY --from=build /hello /hello
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/hello"]
