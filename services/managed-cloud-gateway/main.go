package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unicode/utf8"
)

const (
	publicModelID     = "hymt2-1.8b-q4"
	promptPrefix      = "Translate the following text into Chinese. Note that you should only output the translated result without any additional explanation:\n\n"
	defaultMaxBody    = int64(32 * 1024)
	defaultMaxOutput  = int64(1024 * 1024)
	defaultMaxChars   = 6000
	defaultRatePerMin = 120
	defaultInFlight   = 8
)

type config struct {
	listenAddress     string
	upstreamChatURL   string
	upstreamHealthURL string
	upstreamModel     string
	upstreamAPIKey    string
	maxRequestBytes   int64
	maxResponseBytes  int64
	maxInputChars     int
	ratePerMinute     int
	maxInFlight       int
	trustProxyHeaders bool
	requestTimeout    time.Duration
}

type chatMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type publicChatRequest struct {
	Model         string        `json:"model"`
	Messages      []chatMessage `json:"messages"`
	Stream        bool          `json:"stream"`
	Temperature   float64       `json:"temperature"`
	TopK          int           `json:"top_k"`
	TopP          float64       `json:"top_p"`
	RepeatPenalty float64       `json:"repeat_penalty"`
	Seed          int           `json:"seed"`
	MaxTokens     int           `json:"max_tokens"`
}

type upstreamChatResponse struct {
	ID      string           `json:"id"`
	Created int64            `json:"created"`
	Choices []upstreamChoice `json:"choices"`
	Usage   json.RawMessage  `json:"usage,omitempty"`
}

type upstreamChoice struct {
	Index        int         `json:"index"`
	Message      chatMessage `json:"message"`
	FinishReason string      `json:"finish_reason,omitempty"`
}

type modelList struct {
	Object string      `json:"object"`
	Data   []modelInfo `json:"data"`
}

type modelInfo struct {
	ID      string `json:"id"`
	Object  string `json:"object"`
	OwnedBy string `json:"owned_by"`
}

type gateway struct {
	cfg       config
	client    *http.Client
	limiter   *minuteLimiter
	inFlight  chan struct{}
	requests  atomic.Uint64
	succeeded atomic.Uint64
	failed    atomic.Uint64
	rejected  atomic.Uint64
}

type minuteLimiter struct {
	mu      sync.Mutex
	limit   int
	entries map[string]minuteEntry
}

type minuteEntry struct {
	window time.Time
	count  int
}

func main() {
	cfg, err := configFromEnvironment()
	if err != nil {
		log.Fatal(err)
	}
	g := newGateway(cfg, &http.Client{Timeout: cfg.requestTimeout})
	server := &http.Server{
		Addr:              cfg.listenAddress,
		Handler:           g.routes(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      cfg.requestTimeout + 5*time.Second,
		IdleTimeout:       60 * time.Second,
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-stop
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(ctx); err != nil {
			log.Printf("gateway shutdown: %v", err)
		}
	}()

	log.Printf("managed gateway listening on %s with model %s", cfg.listenAddress, publicModelID)
	if err := server.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}

func configFromEnvironment() (config, error) {
	cfg := config{
		listenAddress:     envString("LISTEN_ADDRESS", ":8080"),
		upstreamChatURL:   envString("UPSTREAM_CHAT_URL", "http://127.0.0.1:8081/v1/chat/completions"),
		upstreamHealthURL: envString("UPSTREAM_HEALTH_URL", "http://127.0.0.1:8081/health"),
		upstreamModel:     envString("UPSTREAM_MODEL", "Hy-MT2-1.8B-Q4_K_M"),
		upstreamAPIKey:    strings.TrimSpace(os.Getenv("UPSTREAM_API_KEY")),
		maxRequestBytes:   int64(envInt("MAX_REQUEST_BYTES", int(defaultMaxBody))),
		maxResponseBytes:  int64(envInt("MAX_RESPONSE_BYTES", int(defaultMaxOutput))),
		maxInputChars:     envInt("MAX_INPUT_CHARS", defaultMaxChars),
		ratePerMinute:     envInt("RATE_LIMIT_PER_MINUTE", defaultRatePerMin),
		maxInFlight:       envInt("MAX_IN_FLIGHT", defaultInFlight),
		trustProxyHeaders: envBool("TRUST_PROXY_HEADERS", false),
		requestTimeout:    time.Duration(envInt("REQUEST_TIMEOUT_SECONDS", 60)) * time.Second,
	}
	for name, value := range map[string]string{
		"UPSTREAM_CHAT_URL":   cfg.upstreamChatURL,
		"UPSTREAM_HEALTH_URL": cfg.upstreamHealthURL,
	} {
		parsed, err := url.ParseRequestURI(value)
		if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
			return config{}, fmt.Errorf("%s must be an absolute HTTP(S) URL", name)
		}
	}
	if cfg.maxRequestBytes < 1024 || cfg.maxResponseBytes < 1024 || cfg.maxInputChars < 1 {
		return config{}, errors.New("request, response, and input limits must be positive")
	}
	if cfg.ratePerMinute < 1 || cfg.maxInFlight < 1 || cfg.requestTimeout < time.Second {
		return config{}, errors.New("rate, concurrency, and timeout limits must be positive")
	}
	return cfg, nil
}

func newGateway(cfg config, client *http.Client) *gateway {
	return &gateway{
		cfg:      cfg,
		client:   client,
		limiter:  &minuteLimiter{limit: cfg.ratePerMinute, entries: make(map[string]minuteEntry)},
		inFlight: make(chan struct{}, cfg.maxInFlight),
	}
}

func (g *gateway) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", g.handleHealth)
	mux.HandleFunc("/readyz", g.handleReady)
	mux.HandleFunc("/metrics", g.handleMetrics)
	mux.HandleFunc("/v1/models", g.handleModels)
	mux.HandleFunc("/v1/chat/completions", g.handleChat)
	return securityHeaders(mux)
}

func (g *gateway) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "model": publicModelID})
}

func (g *gateway) handleReady(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, g.cfg.upstreamHealthURL, nil)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "upstream_unavailable")
		return
	}
	if g.cfg.upstreamAPIKey != "" {
		req.Header.Set("Authorization", "Bearer "+g.cfg.upstreamAPIKey)
	}
	response, err := g.client.Do(req)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "upstream_unavailable")
		return
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		writeError(w, http.StatusServiceUnavailable, "upstream_unavailable")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "ready", "model": publicModelID})
}

func (g *gateway) handleModels(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	writeJSON(w, http.StatusOK, modelList{
		Object: "list",
		Data:   []modelInfo{{ID: publicModelID, Object: "model", OwnedBy: "ScreenTranslation"}},
	})
}

func (g *gateway) handleMetrics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")
	fmt.Fprintf(w, "screen_translation_gateway_requests_total %d\n", g.requests.Load())
	fmt.Fprintf(w, "screen_translation_gateway_succeeded_total %d\n", g.succeeded.Load())
	fmt.Fprintf(w, "screen_translation_gateway_failed_total %d\n", g.failed.Load())
	fmt.Fprintf(w, "screen_translation_gateway_rejected_total %d\n", g.rejected.Load())
}

func (g *gateway) handleChat(w http.ResponseWriter, r *http.Request) {
	g.requests.Add(1)
	if r.Method != http.MethodPost {
		g.reject(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	if !g.limiter.allow(g.clientIdentity(r), time.Now()) {
		w.Header().Set("Retry-After", "60")
		g.reject(w, http.StatusTooManyRequests, "rate_limited")
		return
	}
	select {
	case g.inFlight <- struct{}{}:
		defer func() { <-g.inFlight }()
	default:
		g.reject(w, http.StatusServiceUnavailable, "service_busy")
		return
	}

	request, err := decodePublicRequest(w, r, g.cfg.maxRequestBytes)
	if err != nil {
		g.reject(w, http.StatusBadRequest, "invalid_request")
		return
	}
	if err := validatePublicRequest(request, g.cfg.maxInputChars); err != nil {
		g.reject(w, http.StatusBadRequest, "invalid_translation_contract")
		return
	}

	response, status, err := g.forward(r.Context(), request.Messages[0].Content)
	if err != nil {
		g.failed.Add(1)
		writeError(w, status, "upstream_failure")
		return
	}
	g.succeeded.Add(1)
	writeJSON(w, http.StatusOK, response)
}

func (g *gateway) forward(ctx context.Context, prompt string) (upstreamChatResponse, int, error) {
	payload := publicChatRequest{
		Model:         g.cfg.upstreamModel,
		Messages:      []chatMessage{{Role: "user", Content: prompt}},
		Stream:        false,
		Temperature:   0,
		TopK:          1,
		TopP:          1,
		RepeatPenalty: 1.05,
		Seed:          42,
		MaxTokens:     256,
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return upstreamChatResponse{}, http.StatusBadGateway, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, g.cfg.upstreamChatURL, bytes.NewReader(body))
	if err != nil {
		return upstreamChatResponse{}, http.StatusBadGateway, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	if g.cfg.upstreamAPIKey != "" {
		req.Header.Set("Authorization", "Bearer "+g.cfg.upstreamAPIKey)
	}
	response, err := g.client.Do(req)
	if err != nil {
		return upstreamChatResponse{}, http.StatusBadGateway, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		status := http.StatusBadGateway
		if response.StatusCode == http.StatusTooManyRequests {
			status = http.StatusTooManyRequests
		} else if response.StatusCode == http.StatusServiceUnavailable {
			status = http.StatusServiceUnavailable
		}
		return upstreamChatResponse{}, status, fmt.Errorf("upstream status %d", response.StatusCode)
	}
	raw, err := readBounded(response.Body, g.cfg.maxResponseBytes)
	if err != nil {
		return upstreamChatResponse{}, http.StatusBadGateway, err
	}
	var decoded upstreamChatResponse
	if err := json.Unmarshal(raw, &decoded); err != nil {
		return upstreamChatResponse{}, http.StatusBadGateway, err
	}
	if len(decoded.Choices) == 0 || strings.TrimSpace(decoded.Choices[0].Message.Content) == "" {
		return upstreamChatResponse{}, http.StatusBadGateway, errors.New("upstream response has no translation")
	}
	decoded.Choices[0].Message.Content = strings.TrimSpace(decoded.Choices[0].Message.Content)
	if decoded.Created == 0 {
		decoded.Created = time.Now().Unix()
	}
	return decoded, http.StatusOK, nil
}

func decodePublicRequest(w http.ResponseWriter, r *http.Request, maxBytes int64) (publicChatRequest, error) {
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	var request publicChatRequest
	if err := decoder.Decode(&request); err != nil {
		return publicChatRequest{}, err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return publicChatRequest{}, errors.New("request contains trailing JSON")
	}
	return request, nil
}

func validatePublicRequest(request publicChatRequest, maxChars int) error {
	if request.Model != publicModelID || request.Stream || request.Temperature != 0 ||
		request.TopK != 1 || request.TopP != 1 || request.RepeatPenalty != 1.05 ||
		request.Seed != 42 || request.MaxTokens != 256 {
		return errors.New("request parameters do not match the managed contract")
	}
	if len(request.Messages) != 1 || request.Messages[0].Role != "user" {
		return errors.New("managed requests require one user message")
	}
	prompt := request.Messages[0].Content
	if !utf8.ValidString(prompt) || !strings.HasPrefix(prompt, promptPrefix) {
		return errors.New("managed prompt prefix is invalid")
	}
	text := strings.TrimPrefix(prompt, promptPrefix)
	if strings.TrimSpace(text) == "" || utf8.RuneCountInString(text) > maxChars {
		return errors.New("translation input is empty or too large")
	}
	return nil
}

func (g *gateway) clientIdentity(r *http.Request) string {
	if g.cfg.trustProxyHeaders {
		if forwarded := strings.TrimSpace(strings.Split(r.Header.Get("X-Forwarded-For"), ",")[0]); forwarded != "" {
			return forwarded
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	return r.RemoteAddr
}

func (g *gateway) reject(w http.ResponseWriter, status int, code string) {
	g.rejected.Add(1)
	writeError(w, status, code)
}

func (l *minuteLimiter) allow(identity string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	window := now.Truncate(time.Minute)
	entry := l.entries[identity]
	if entry.window != window {
		entry = minuteEntry{window: window}
	}
	if entry.count >= l.limit {
		l.entries[identity] = entry
		return false
	}
	entry.count++
	l.entries[identity] = entry
	if len(l.entries) > 10000 {
		for key, value := range l.entries {
			if value.window.Before(window) {
				delete(l.entries, key)
			}
		}
	}
	return true
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(value); err != nil {
		log.Printf("encode response: %v", err)
	}
}

func writeError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]any{
		"error": map[string]string{"code": code, "message": http.StatusText(status)},
	})
}

func readBounded(reader io.Reader, limit int64) ([]byte, error) {
	raw, err := io.ReadAll(io.LimitReader(reader, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(raw)) > limit {
		return nil, errors.New("upstream response exceeds configured limit")
	}
	return raw, nil
}

func envString(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func envInt(name string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		log.Fatalf("%s must be an integer", name)
	}
	return parsed
}

func envBool(name string, fallback bool) bool {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		log.Fatalf("%s must be a boolean", name)
	}
	return parsed
}
