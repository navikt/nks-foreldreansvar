package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestVisits(t *testing.T) {
	handler := newHandler()

	get := func(want string) {
		t.Helper()
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/", nil))
		if got := response.Code; got != http.StatusOK {
			t.Errorf("GET /: status = %d, want %d", got, http.StatusOK)
		}
		if got, expected := response.Body.String(), "<strong>"+want+"</strong>"; !strings.Contains(got, expected) {
			t.Errorf("GET /: body = %q, want to contain %q", got, expected)
		}
	}

	get("0")
	for range 2 {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest(http.MethodPost, "/visit", nil))
		if got := response.Code; got != http.StatusSeeOther {
			t.Errorf("POST /visit: status = %d, want %d", got, http.StatusSeeOther)
		}
		if got := response.Header().Get("Location"); got != "/" {
			t.Errorf("POST /visit: Location = %q, want %q", got, "/")
		}
	}
	get("2")
	get("2") // Reading the page does not change the count.

	response := httptest.NewRecorder()
	newHandler().ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/", nil))
	if got, want := response.Body.String(), "<strong>0</strong>"; !strings.Contains(got, want) {
		t.Errorf("GET / (new handler): body = %q, want to contain %q", got, want)
	}
}

func TestLogo(t *testing.T) {
	response := httptest.NewRecorder()
	newHandler().ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/logo.svg", nil))
	if got := response.Code; got != http.StatusOK {
		t.Errorf("GET /logo.svg: status = %d, want %d", got, http.StatusOK)
	}
	if got := response.Header().Get("Content-Type"); !strings.Contains(got, "image/svg+xml") {
		t.Errorf("GET /logo.svg: Content-Type = %q, want image/svg+xml", got)
	}
}

func TestHealth(t *testing.T) {
	response := httptest.NewRecorder()
	newHandler().ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if got := response.Code; got != http.StatusNoContent {
		t.Errorf("GET /healthz: status = %d, want %d", got, http.StatusNoContent)
	}
}
