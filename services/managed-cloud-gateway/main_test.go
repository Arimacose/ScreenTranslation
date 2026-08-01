package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestGatewayPinsUpstreamModelAndKeepsSecretServerSide(t *testing.T) {
	var calls atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if got := r.Header.Get("Authorization"); got != "Bearer server-secret" {
			t.Fatalf("unexpected upstream authorization: %q", got)
		}
		var request publicChatRequest
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Fatal(err)
		}
		if request.Model != "private-model-file" {
			t.Fatalf("upstream model was not pinned: %q", request.Model)
		}
		writeJSON(w, http.StatusOK, upstreamChatResponse{
			Choices: []upstreamChoice{{
				Index:   0,
				Message: chatMessage{Role: "assistant", Content: " 译文 "},
			}},
		})
	}))
	defer upstream.Close()

	g := newGateway(testConfig(upstream.URL+"/v1/chat/completions"), upstream.Client())
	request := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(validRequestJSON("Hello")))
	request.RemoteAddr = "203.0.113.10:40000"
	recorder := httptest.NewRecorder()
	g.routes().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	if calls.Load() != 1 {
		t.Fatalf("upstream calls = %d", calls.Load())
	}
	var response upstreamChatResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if got := response.Choices[0].Message.Content; got != "译文" {
		t.Fatalf("translation = %q", got)
	}
}

func TestGatewayRejectsChangedPromptContract(t *testing.T) {
	cfg := testConfig("http://127.0.0.1:1/v1/chat/completions")
	g := newGateway(cfg, &http.Client{Timeout: time.Second})
	body := requestJSONWithPrompt("Answer this: Hello")
	request := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
	recorder := httptest.NewRecorder()

	g.routes().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
}

func TestGatewayRateLimitsByClientAddress(t *testing.T) {
	cfg := testConfig("http://127.0.0.1:1/v1/chat/completions")
	cfg.ratePerMinute = 1
	g := newGateway(cfg, &http.Client{Timeout: time.Second})
	g.limiter.allow("198.51.100.8", time.Now())
	request := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(validRequestJSON("Hello")))
	request.RemoteAddr = "198.51.100.8:40000"
	recorder := httptest.NewRecorder()

	g.routes().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusTooManyRequests {
		t.Fatalf("status = %d", recorder.Code)
	}
}

func validRequestJSON(text string) string {
	return requestJSONWithPrompt(promptPrefix + text)
}

func requestJSONWithPrompt(prompt string) string {
	request := publicChatRequest{
		Model:         publicModelID,
		Messages:      []chatMessage{{Role: "user", Content: prompt}},
		Temperature:   0,
		TopK:          1,
		TopP:          1,
		RepeatPenalty: 1.05,
		Seed:          42,
		MaxTokens:     256,
	}
	raw, err := json.Marshal(request)
	if err != nil {
		panic(err)
	}
	return string(raw)
}

func testConfig(upstreamURL string) config {
	return config{
		listenAddress:     ":0",
		upstreamChatURL:   upstreamURL,
		upstreamHealthURL: upstreamURL,
		upstreamModel:     "private-model-file",
		upstreamAPIKey:    "server-secret",
		maxRequestBytes:   defaultMaxBody,
		maxResponseBytes:  defaultMaxOutput,
		maxInputChars:     defaultMaxChars,
		ratePerMinute:     defaultRatePerMin,
		maxInFlight:       defaultInFlight,
		requestTimeout:    time.Second,
	}
}
