package main

import (
	"embed"
	"html/template"
	"log"
	"net/http"
	"sync/atomic"
	"time"
)

//go:embed index.html logo.svg
var files embed.FS

const port = "8080"

func main() {
	server := &http.Server{
		Addr:              ":" + port,
		Handler:           newHandler(),
		ReadHeaderTimeout: 5 * time.Second,
	}
	log.Printf("listening on port %s", port)
	log.Fatal(server.ListenAndServe())
}

func newHandler() http.Handler {
	var count atomic.Int64
	page := template.Must(template.ParseFS(files, "index.html"))
	mux := http.NewServeMux()
	mux.Handle("GET /logo.svg", http.FileServer(http.FS(files)))

	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		if err := page.Execute(w, count.Load()); err != nil {
			log.Printf("render page: %v", err)
		}
	})
	mux.HandleFunc("POST /visit", func(w http.ResponseWriter, r *http.Request) {
		count.Add(1)
		http.Redirect(w, r, "/", http.StatusSeeOther)
	})
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})
	return mux
}
