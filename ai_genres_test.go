package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestOpenAIAdapterAndResponseFormatFallback(t *testing.T) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/chat/completions" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer sk-test-openai-key" {
			t.Fatalf("missing auth header: %q", r.Header.Get("Authorization"))
		}
		body, _ := io.ReadAll(r.Body)
		var req map[string]any
		_ = json.Unmarshal(body, &req)
		n := calls.Add(1)
		if n == 1 {
			if _, ok := req["response_format"]; !ok {
				t.Fatalf("expected response_format on first request")
			}
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":{"message":"'response_format' type json_object is not supported"}}`))
			return
		}
		if _, ok := req["response_format"]; ok {
			t.Fatalf("expected response_format to be omitted on fallback retry")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{\"results\":[{\"i\":1,\"genres\":[\"Thrash Metal\",\"Metal\"]}]}"}}]}`))
	}))
	defer srv.Close()

	cfg, err := resolveAIConfig(AISettings{
		Provider: "openai",
		BaseURL:  srv.URL,
		APIKey:   "sk-test-openai-key",
		Model:    "gpt-4o-mini",
	})
	if err != nil {
		t.Fatal(err)
	}
	p := newHTTPAIClient(cfg)
	p.retryBase = time.Millisecond

	raw, err := p.ClassifyBatch(context.Background(), "sys", "usr")
	if err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 2 {
		t.Fatalf("expected 2 requests (with fallback), got %d", calls.Load())
	}
	out, err := parseAIResponse(raw)
	if err != nil || len(out) != 1 || joinGenres(validateAIGenres(out[0], false)) != "Thrash Metal" {
		t.Fatalf("unexpected output: out=%+v err=%v", out, err)
	}
}

func TestGeminiAdapterHeaderAndThoughts(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/models/gemini-3.8-flash:generateContent" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		if r.URL.RawQuery != "" {
			t.Fatalf("expected empty query string (key must stay in header), got %q", r.URL.RawQuery)
		}
		if r.Header.Get("x-goog-api-key") != "gem-secret-key" {
			t.Fatalf("missing x-goog-api-key header: %q", r.Header.Get("x-goog-api-key"))
		}
		body, _ := io.ReadAll(r.Body)
		var req struct {
			GenerationConfig struct {
				ThinkingConfig struct {
					ThinkingLevel string `json:"thinkingLevel"`
				} `json:"thinkingConfig"`
			} `json:"generationConfig"`
		}
		if err := json.Unmarshal(body, &req); err != nil {
			t.Fatal(err)
		}
		if req.GenerationConfig.ThinkingConfig.ThinkingLevel != "LOW" {
			t.Fatalf("expected thinkingLevel=LOW for gemini-3.8-flash, got %q", req.GenerationConfig.ThinkingConfig.ThinkingLevel)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"candidates": [{
				"content": {
					"parts": [
						{"thought": true, "text": "thinking notes that should be ignored"},
						{"text": "{\"results\":[{\"i\":1,\"genres\":[\"Anime\",\"J-Rock\"]}]}"}
					]
				}
			}]
		}`))
	}))
	defer srv.Close()

	cfg, err := resolveAIConfig(AISettings{
		Provider: "gemini",
		BaseURL:  srv.URL,
		APIKey:   "gem-secret-key",
		Model:    "gemini-3.8-flash",
	})
	if err != nil {
		t.Fatal(err)
	}
	p := newHTTPAIClient(cfg)
	raw, err := p.ClassifyBatch(context.Background(), "sys", "usr")
	if err != nil {
		t.Fatal(err)
	}
	out, err := parseAIResponse(raw)
	if err != nil || len(out) != 1 || joinGenres(validateAIGenres(out[0], false)) != "Anime|J-Rock" {
		t.Fatalf("unexpected output: out=%+v err=%v", out, err)
	}
}

func TestAnthropicAdapter(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/messages" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		if r.Header.Get("x-api-key") != "ant-secret-key" {
			t.Fatalf("missing x-api-key: %q", r.Header.Get("x-api-key"))
		}
		if r.Header.Get("anthropic-version") != "2023-06-01" {
			t.Fatalf("unexpected anthropic-version: %q", r.Header.Get("anthropic-version"))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"content": [
				{"type": "text", "text": "{\"results\":["},
				{"type": "text", "text": "{\"i\":1,\"genres\":[\"Video Game Music\",\"Orchestral\"]}]}"}
			]
		}`))
	}))
	defer srv.Close()

	cfg, err := resolveAIConfig(AISettings{
		Provider: "anthropic",
		BaseURL:  srv.URL,
		APIKey:   "ant-secret-key",
	})
	if err != nil {
		t.Fatal(err)
	}
	p := newHTTPAIClient(cfg)
	raw, err := p.ClassifyBatch(context.Background(), "sys", "usr")
	if err != nil {
		t.Fatal(err)
	}
	out, err := parseAIResponse(raw)
	if err != nil || len(out) != 1 || joinGenres(validateAIGenres(out[0], false)) != "Video Game Music|Orchestral" {
		t.Fatalf("unexpected output: out=%+v err=%v", out, err)
	}
}

func TestAIRetryOnRateLimitAndStopOnAuth(t *testing.T) {
	var rateCalls atomic.Int32
	rateSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := rateCalls.Add(1)
		if n < 3 {
			w.WriteHeader(http.StatusTooManyRequests)
			_, _ = w.Write([]byte(`{"error":{"message":"slow down"}}`))
			return
		}
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{\"results\":[{\"i\":1,\"genres\":[\"Jazz\"]}]}"}}]}`))
	}))
	defer rateSrv.Close()

	cfg, _ := resolveAIConfig(AISettings{Provider: "openai", BaseURL: rateSrv.URL, APIKey: "sk-1234", Model: "gpt-4o-mini"})
	p := newHTTPAIClient(cfg)
	p.retryBase = 2 * time.Millisecond
	if _, err := p.ClassifyBatch(context.Background(), "sys", "usr"); err != nil {
		t.Fatalf("expected 429 retry to succeed: %v", err)
	}
	if rateCalls.Load() != 3 {
		t.Fatalf("expected 3 attempts, got %d", rateCalls.Load())
	}

	const secretKey = "sk-super-secret-token-98765"
	var authCalls atomic.Int32
	authSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authCalls.Add(1)
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":{"message":"Incorrect API key provided: ` + secretKey + `"}}`))
	}))
	defer authSrv.Close()

	cfg2, _ := resolveAIConfig(AISettings{Provider: "openai", BaseURL: authSrv.URL, APIKey: secretKey, Model: "gpt-4o-mini"})
	p2 := newHTTPAIClient(cfg2)
	p2.retryBase = 2 * time.Millisecond
	_, err := p2.ClassifyBatch(context.Background(), "sys", "usr")
	if err == nil {
		t.Fatal("expected auth error")
	}
	if authCalls.Load() != 1 {
		t.Fatalf("expected 0 retries on 401, got %d attempts", authCalls.Load())
	}
	if strings.Contains(err.Error(), secretKey) {
		t.Fatalf("secret key leaked in error: %s", err.Error())
	}
	var ae *AIError
	if !errors.As(err, &ae) || ae.Kind != "auth" {
		t.Fatalf("expected AIError kind=auth, got %T %v", err, err)
	}
}

func TestGeminiQuotaLimitZeroAndTestNoRetry(t *testing.T) {
	var quotaCalls atomic.Int32
	quotaSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		quotaCalls.Add(1)
		w.WriteHeader(http.StatusTooManyRequests)
		_, _ = w.Write([]byte(`{"error":{"message":"Quota exceeded for quota metric 'Generate Content API requests per minute', limit: 0"}}`))
	}))
	defer quotaSrv.Close()

	cfg, _ := resolveAIConfig(AISettings{Provider: "gemini", BaseURL: quotaSrv.URL, APIKey: "AQ.AbcTestKey", Model: "gemini-3.8-flash"})
	p := newHTTPAIClient(cfg)
	p.retryBase = time.Second
	_, err := p.ClassifyBatch(context.Background(), "sys", "usr")
	if err == nil {
		t.Fatal("expected quota error")
	}
	if quotaCalls.Load() != 1 {
		t.Fatalf("expected 1 attempt on limit: 0 quota 429, got %d", quotaCalls.Load())
	}
	var ae *AIError
	if !errors.As(err, &ae) || ae.Kind != "quota" {
		t.Fatalf("expected AIError kind=quota, got %T %v", err, err)
	}

	var testCalls atomic.Int32
	rateSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		testCalls.Add(1)
		w.WriteHeader(http.StatusTooManyRequests)
		_, _ = w.Write([]byte(`{"error":{"message":"You exceeded your current quota"}}`))
	}))
	defer rateSrv.Close()

	p2 := newHTTPAIClient(cfg)
	p2.cfg.baseURL = rateSrv.URL
	p2.retryBase = 10 * time.Second
	res := testAIProvider(context.Background(), p2)
	if res.OK {
		t.Fatalf("expected testAIProvider to fail on 429")
	}
	if testCalls.Load() != 1 {
		t.Fatalf("expected testAIProvider to make 1 attempt (no retry sleep), got %d", testCalls.Load())
	}
	if !strings.Contains(res.Message, "429") {
		t.Fatalf("expected actual 429 message in test result, got %q", res.Message)
	}
}

func TestGeminiGoogleSearchGroundingAndFallback(t *testing.T) {
	var calls atomic.Int32
	var sawTools atomic.Bool
	var sawTestTools atomic.Bool

	// 1. WebSearch=true, GeminiGrounding=false must NOT attach tools
	noGroundSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var req map[string]any
		_ = json.Unmarshal(body, &req)
		if _, hasTools := req["tools"]; hasTools {
			t.Fatalf("did not expect tools when GeminiGrounding=false")
		}
		_, _ = w.Write([]byte(`{"candidates":[{"content":{"parts":[{"text":"{\"results\":[{\"i\":1,\"genres\":[\"J-Rock\"]}]}"}]}}]}`))
	}))
	defer noGroundSrv.Close()
	cfgNoGround, err := resolveAIConfig(AISettings{
		Provider:        "gemini",
		BaseURL:         noGroundSrv.URL,
		APIKey:          "AQ.TestSearchKey",
		Model:           "gemini-3.5-flash-lite",
		WebSearch:       true,
		GeminiGrounding: false,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := newHTTPAIClient(cfgNoGround).ClassifyBatch(context.Background(), "sys", "usr"); err != nil {
		t.Fatal(err)
	}

	// 2. GeminiGrounding=true sends tools and falls back on 400 (responseMimeType) or 429 limit:0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := calls.Add(1)
		body, _ := io.ReadAll(r.Body)
		var req map[string]any
		_ = json.Unmarshal(body, &req)
		_, hasTools := req["tools"]
		genCfg, _ := req["generationConfig"].(map[string]any)
		_, hasMIME := genCfg["responseMimeType"]

		switch n {
		case 1:
			if !hasTools || !hasMIME {
				t.Fatalf("expected tools (%v) and responseMimeType (%v) on attempt 1", hasTools, hasMIME)
			}
			sawTools.Store(true)
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":{"message":"Tool use with responseMimeType is unsupported"}}`))
		case 2:
			if !hasTools || hasMIME {
				t.Fatalf("expected attempt 2 to keep tools (%v) and drop responseMimeType (%v)", hasTools, hasMIME)
			}
			w.WriteHeader(http.StatusTooManyRequests)
			_, _ = w.Write([]byte(`{"error":{"message":"Quota exceeded for search_grounding, limit: 0"}}`))
		default:
			if hasTools || !hasMIME {
				t.Fatalf("expected attempt 3 to drop tools (%v) and restore responseMimeType (%v)", hasTools, hasMIME)
			}
			_, _ = w.Write([]byte(`{"candidates":[{"content":{"parts":[{"text":"{\"results\":[{\"i\":1,\"genres\":[\"Anime\",\"J-Rock\"]}]}"}]}}]}`))
		}
	}))
	defer srv.Close()

	cfg, err := resolveAIConfig(AISettings{
		Provider:        "gemini",
		BaseURL:         srv.URL,
		APIKey:          "AQ.TestSearchKey",
		Model:           "gemini-3.5-flash-lite",
		WebSearch:       true,
		GeminiGrounding: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	p := newHTTPAIClient(cfg)
	raw, err := p.ClassifyBatch(context.Background(), "sys", "usr")
	if err != nil {
		t.Fatal(err)
	}
	if !sawTools.Load() || calls.Load() != 3 {
		t.Fatalf("expected tools on attempt 1 + 2 fallback retries, sawTools=%v calls=%d", sawTools.Load(), calls.Load())
	}
	if out, err := parseAIResponse(raw); err != nil || len(out) != 1 || joinGenres(validateAIGenres(out[0], false)) != "Anime|J-Rock" {
		t.Fatalf("unexpected parsed result: out=%+v err=%v", out, err)
	}

	// 3. testAIProvider must disable webSearch & geminiGrounding even when enabled
	p.cfg.webSearch = true
	p.cfg.geminiGrounding = true
	testSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var req map[string]any
		_ = json.Unmarshal(body, &req)
		if _, hasTools := req["tools"]; hasTools {
			sawTestTools.Store(true)
		}
		_, _ = w.Write([]byte(`{"candidates":[{"content":{"parts":[{"text":"{\"results\":[{\"i\":1,\"genres\":[\"Thrash Metal\"]}]}"}]}}]}`))
	}))
	defer testSrv.Close()
	p.cfg.baseURL = testSrv.URL
	res := testAIProvider(context.Background(), p)
	if !res.OK || sawTestTools.Load() {
		t.Fatalf("testAIProvider ok=%v sawTestTools=%v msg=%q", res.OK, sawTestTools.Load(), res.Message)
	}
}

func TestGeminiRetryInfoAndDailyQuota(t *testing.T) {
	p := newHTTPAIClient(resolvedAIConfig{providerID: "gemini", kind: "gemini"})

	body1 := []byte(`{
		"error": {
			"code": 429,
			"message": "You exceeded your current quota.",
			"details": [
				{"@type": "type.googleapis.com/google.rpc.QuotaFailure", "violations": [{"quotaId": "GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "quotaValue": "15"}]},
				{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "12.5s"}
			]
		}
	}`)
	ae1 := p.classifyHTTPFailure(http.StatusTooManyRequests, http.Header{}, body1)
	if ae1.Kind != "rate_limit" || ae1.RetryAfter < 13*time.Second {
		t.Fatalf("expected rate_limit with RetryAfter >= 13s, got %+v", ae1)
	}

	body2 := []byte(`{"error":{"code":429,"message":"Please retry in 8.2s."}}`)
	ae2 := p.classifyHTTPFailure(http.StatusTooManyRequests, http.Header{}, body2)
	if ae2.Kind != "rate_limit" || ae2.RetryAfter < 9*time.Second {
		t.Fatalf("expected message fallback RetryAfter >= 9s, got %+v", ae2)
	}

	var dailyCalls atomic.Int32
	dailySrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		dailyCalls.Add(1)
		w.WriteHeader(http.StatusTooManyRequests)
		_, _ = w.Write([]byte(`{
			"error": {
				"code": 429,
				"message": "Daily quota exceeded.",
				"details": [
					{"@type": "type.googleapis.com/google.rpc.QuotaFailure", "violations": [{"quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier", "quotaValue": "20"}]}
				]
			}
		}`))
	}))
	defer dailySrv.Close()

	cfg, _ := resolveAIConfig(AISettings{Provider: "gemini", BaseURL: dailySrv.URL, APIKey: "AQ.DailyKey", Model: "gemini-3.5-flash-lite"})
	p3 := newHTTPAIClient(cfg)
	p3.retryBase = time.Second
	_, err := p3.ClassifyBatch(context.Background(), "sys", "usr")
	var ae3 *AIError
	if !errors.As(err, &ae3) || ae3.Kind != "quota" || dailyCalls.Load() != 1 {
		t.Fatalf("expected immediate quota error in 1 call, err=%v calls=%d", err, dailyCalls.Load())
	}
}

func TestParseAndValidateAIGenres(t *testing.T) {
	fenced := "```json\n{\"results\":[{\"i\":1,\"genres\":[\"jrock\",\"Anime\",\"Invented Genre\",\"Rock\"],\"custom\":[\"Vocaloid\",\"unknown\",\"Another Very Long Genre Name That Exceeds Forty Characters\",\"Denpa\"]}]}\n```"
	out, err := parseAIResponse(fenced)
	if err != nil || len(out) != 1 {
		t.Fatalf("parse fenced failed: %+v err=%v", out, err)
	}
	if got := joinGenres(validateAIGenres(out[0], false)); got != "J-Rock|Anime" {
		t.Fatalf("allowCustom=false got %q", got)
	}
	if got := joinGenres(validateAIGenres(out[0], true)); got != "J-Rock|Anime|Vocaloid|Denpa" {
		t.Fatalf("allowCustom=true got %q", got)
	}

	bare := `Sure, here is the JSON: [{"i":1,"genres":[]}]`
	out2, err := parseAIResponse(bare)
	if err != nil || len(out2) != 1 {
		t.Fatalf("parse bare failed: %+v err=%v", out2, err)
	}
	if got := validateAIGenres(out2[0], false); len(got) != 0 {
		t.Fatalf("expected empty list, got %v", got)
	}
}

type fakeAIProvider struct {
	fn func(batch []TrackGenreInput) (string, error)
}

func (f *fakeAIProvider) ClassifyBatch(_ context.Context, _, userPrompt string) (string, error) {
	raw := strings.TrimPrefix(userPrompt, "Classify these songs:\n")
	var items []TrackGenreInput
	if err := json.Unmarshal([]byte(raw), &items); err != nil {
		return "", err
	}
	return f.fn(items)
}

func waitDetectorDone(t *testing.T, g *GenreDetector) GenreStatus {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		st := g.Status()
		if !st.Running {
			return st
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("genre detector timed out")
	return GenreStatus{}
}

func TestGenreDetectorAIEndToEndAndResume(t *testing.T) {
	data := t.TempDir()
	t.Setenv("XDG_DATA_HOME", data)
	st, err := OpenStore()
	if err != nil {
		t.Fatal(err)
	}
	a := &App{store: st, tracks: map[string]Track{}}
	root := st.EnsureRoot(filepath.Join(data, "music"))

	// Mixed composer tracks + user-locked track + tag-locked track + heuristic track
	tracks := []Track{
		{ID: "t1", RootKey: root, RelPath: "1.mp3", Artist: "Sawano", Title: "Battle Suite", Album: "Game OST", AddedAt: 60},
		{ID: "t2", RootKey: root, RelPath: "2.mp3", Artist: "Sawano", Title: "City Lights", Album: "Pop Single", AddedAt: 50},
		{ID: "t3", RootKey: root, RelPath: "3.mp3", Artist: "Mystery", Title: "Piano Version", Album: "", AddedAt: 40},
		{ID: "t4", RootKey: root, RelPath: "4.mp3", Artist: "OldArtist", Title: "Track 4", Album: "Old", Genre: "Rock", GenreSource: "online", AddedAt: 30},
		{ID: "t5", RootKey: root, RelPath: "5.mp3", Artist: "HandArtist", Title: "Track 5", Genre: "Jazz", GenreSource: "user", AddedAt: 20},
		{ID: "t6", RootKey: root, RelPath: "6.mp3", Artist: "TagArtist", Title: "Track 6", Genre: "Metal", GenreSource: "tag", AddedAt: 10},
	}
	for _, tr := range tracks {
		if _, err := st.UpsertTrack(tr); err != nil {
			t.Fatal(err)
		}
	}
	_ = st.SetTracksGenre([]string{"t4"}, "Rock", "online")
	_ = st.SetTracksGenre([]string{"t5"}, "Jazz", "user")

	var sentBatches [][]string
	failBatch2 := true

	g := NewGenreDetector(a)
	g.enricher = nil
	g.onlineLookup = func(_, _ string) ([]string, string, error) {
		return nil, "", nil
	}
	g.aiFactory = func(_ context.Context, _ AISettings) (AIProvider, func(), int, error) {
		p := &fakeAIProvider{
			fn: func(items []TrackGenreInput) (string, error) {
				var titles []string
				for _, it := range items {
					titles = append(titles, it.Title)
				}
				sentBatches = append(sentBatches, titles)

				if len(sentBatches) == 2 && failBatch2 {
					return "", &AIError{StatusCode: 401, Kind: "auth", Message: "bad key"}
				}
				var res []TrackGenreOutput
				for _, it := range items {
					switch it.Title {
					case "Battle Suite":
						res = append(res, TrackGenreOutput{Index: it.Index, Genres: []string{"Video Game Music", "Orchestral"}})
					case "City Lights":
						res = append(res, TrackGenreOutput{Index: it.Index, Genres: []string{"J-Pop"}})
					case "Piano Version":
						res = append(res, TrackGenreOutput{Index: it.Index, Genres: []string{}})
					case "Track 4":
						res = append(res, TrackGenreOutput{Index: it.Index, Genres: []string{"Alternative Rock"}})
					}
				}
				b, _ := json.Marshal(map[string]any{"results": res})
				return string(b), nil
			},
		}
		return p, func() {}, 2, nil
	}

	// Run "all" -> targets t1, t2, t3, t4 (never t5 user or t6 tag); batch 2 fails
	if msg := g.StartAI("all"); msg != "" {
		t.Fatal(msg)
	}
	stat := waitDetectorDone(t, g)
	if stat.Error != "bad key" {
		t.Fatalf("expected auth error in status, got %+v", stat)
	}
	if stat.PendingAI != 2 {
		t.Fatalf("expected 2 pending tracks after batch 2 failure, got %d", stat.PendingAI)
	}

	// Verify batch 1 was persisted and different per song for same composer "Sawano"
	tr1, _ := st.TrackByID("t1")
	tr2, _ := st.TrackByID("t2")
	if tr1.Genre != "Video Game Music|Orchestral" || tr1.GenreSource != "ai" {
		t.Fatalf("t1 = %+v", tr1)
	}
	if tr2.Genre != "J-Pop" || tr2.GenreSource != "ai" {
		t.Fatalf("t2 = %+v", tr2)
	}
	// Verify batch 2 track t4 still keeps its previous online genre when batch 2 failed!
	tr4, _ := st.TrackByID("t4")
	if tr4.Genre != "Rock" || tr4.GenreSource != "online" {
		t.Fatalf("t4 was wiped prematurely: %+v", tr4)
	}
	// Verify user & tag tracks untouched
	tr5, _ := st.TrackByID("t5")
	tr6, _ := st.TrackByID("t6")
	if tr5.Genre != "Jazz" || tr5.GenreSource != "user" || tr6.Genre != "Metal" || tr6.GenreSource != "tag" {
		t.Fatalf("user/tag modified: t5=%+v t6=%+v", tr5, tr6)
	}

	// Resume now with working provider -> only remaining 2 tracks should be sent!
	sentBatches = nil
	failBatch2 = false
	if msg := g.StartAI("resume"); msg != "" {
		t.Fatal(msg)
	}
	stat = waitDetectorDone(t, g)
	if stat.Error != "" || stat.PendingAI != 0 {
		t.Fatalf("expected clean completion after resume, got %+v", stat)
	}
	if len(sentBatches) != 1 || len(sentBatches[0]) != 2 {
		t.Fatalf("expected single resume batch of 2 tracks, got %+v", sentBatches)
	}
	// t3 returned [] from AI -> should fall through to Piano heuristic
	tr3, _ := st.TrackByID("t3")
	if tr3.Genre != "Piano" || tr3.GenreSource != "heuristic" {
		t.Fatalf("t3 heuristic fallback failed: %+v", tr3)
	}
	tr4, _ = st.TrackByID("t4")
	if tr4.Genre != "Alternative Rock" || tr4.GenreSource != "ai" {
		t.Fatalf("t4 resume update failed: %+v", tr4)
	}
	// Ensure t1/t2 per-song AI genres weren't merged together by applyGenres()
	tr1, _ = st.TrackByID("t1")
	tr2, _ = st.TrackByID("t2")
	if tr1.Genre != "Video Game Music|Orchestral" || tr2.Genre != "J-Pop" {
		t.Fatalf("AI per-song genres overwritten by applyGenres: t1=%+v t2=%+v", tr1, tr2)
	}
}

func TestLocalAIManagerFreshStatusAssetSelectionAndTarExtract(t *testing.T) {
	data := t.TempDir()
	t.Setenv("XDG_DATA_HOME", data)

	mgr := NewLocalAIManager(nil)
	st := mgr.Status()
	if st.RuntimeReady || len(st.InstalledModels) != 0 || st.DiskUsageBytes != 0 {
		t.Fatalf("unexpected fresh local status: %+v", st)
	}
	if _, err := os.Stat(aiDir()); !os.IsNotExist(err) {
		t.Fatalf("Status() must not create aiDir, stat err=%v", err)
	}

	assets := []ghAsset{
		{Name: "llama-b5000-bin-ubuntu-x64.tar.gz", DownloadURL: "https://example.com/ubuntu-cpu.tar.gz"},
		{Name: "llama-b5000-bin-ubuntu-vulkan-x64.tar.gz", DownloadURL: "https://example.com/ubuntu-vulkan.tar.gz"},
		{Name: "llama-b5000-bin-ubuntu-vulkan-x64.tar.gz.sha256", DownloadURL: "https://example.com/sha"},
		{Name: "llama-b5000-bin-win-cpu-x64.zip", DownloadURL: "https://example.com/win-cpu.zip"},
		{Name: "llama-b5000-bin-win-vulkan-x64.zip", DownloadURL: "https://example.com/win-vulkan.zip"},
	}

	a, v, err := selectLlamaReleaseAsset("linux", "amd64", false, assets)
	if err != nil || v != "cpu" || !strings.Contains(a.Name, "ubuntu-x64") {
		t.Fatalf("linux cpu mismatch: a=%+v v=%q err=%v", a, v, err)
	}
	a, v, err = selectLlamaReleaseAsset("linux", "amd64", true, assets)
	if err != nil || v != "vulkan" || !strings.Contains(a.Name, "ubuntu-vulkan") {
		t.Fatalf("linux vulkan mismatch: a=%+v v=%q err=%v", a, v, err)
	}
	a, v, err = selectLlamaReleaseAsset("windows", "amd64", false, assets)
	if err != nil || v != "cpu" || !strings.Contains(a.Name, "win-cpu") {
		t.Fatalf("windows cpu mismatch: a=%+v v=%q err=%v", a, v, err)
	}
	a, v, err = selectLlamaReleaseAsset("windows", "amd64", true, assets)
	if err != nil || v != "vulkan" || !strings.Contains(a.Name, "win-vulkan") {
		t.Fatalf("windows vulkan mismatch: a=%+v v=%q err=%v", a, v, err)
	}
	// Fallback when Vulkan asset is absent
	a, v, err = selectLlamaReleaseAsset("linux", "amd64", true, assets[:1])
	if err != nil || v != "cpu" || !strings.Contains(a.Name, "ubuntu-x64") {
		t.Fatalf("linux vulkan fallback mismatch: a=%+v v=%q err=%v", a, v, err)
	}

	// Test extractTarGz symlink preservation and path-traversal rejection
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	writeFile := func(name string, mode int64, content string) {
		_ = tw.WriteHeader(&tar.Header{Name: name, Mode: mode, Size: int64(len(content)), Typeflag: tar.TypeReg})
		_, _ = tw.Write([]byte(content))
	}
	writeFile("bin/llama-server", 0o755, "srv")
	writeFile("bin/libllama.so.0", 0o644, "lib")
	_ = tw.WriteHeader(&tar.Header{Name: "bin/libllama.so", Mode: 0o777, Typeflag: tar.TypeSymlink, Linkname: "libllama.so.0"})
	writeFile("../escape.txt", 0o644, "bad")
	_ = tw.WriteHeader(&tar.Header{Name: "bin/badlink", Mode: 0o777, Typeflag: tar.TypeSymlink, Linkname: "../../../etc/passwd"})
	_ = tw.Close()
	_ = gz.Close()

	arc := filepath.Join(data, "test.tar.gz")
	_ = os.WriteFile(arc, buf.Bytes(), 0o644)
	dst := filepath.Join(data, "out")
	if err := extractTarGz(arc, dst); err != nil {
		t.Fatal(err)
	}
	srvBin := findLlamaServerBinary(dst)
	if srvBin == "" {
		t.Fatal("llama-server not found after extraction")
	}
	if fi, err := os.Stat(srvBin); err != nil || fi.Mode()&0o100 == 0 {
		t.Fatalf("expected executable bit preserved on llama-server: err=%v mode=%v", err, fi.Mode())
	}
	if target, err := os.Readlink(filepath.Join(dst, "bin", "libllama.so")); err != nil || target != "libllama.so.0" {
		t.Fatalf("symlink not preserved: target=%q err=%v", target, err)
	}
	if _, err := os.Stat(filepath.Join(data, "escape.txt")); !os.IsNotExist(err) {
		t.Fatal("path traversal file should have been skipped")
	}
	if _, err := os.Lstat(filepath.Join(dst, "bin", "badlink")); !os.IsNotExist(err) {
		t.Fatal("escaping symlink should have been skipped")
	}
}

func TestContextEnricherYouTubeITunesWikiAndCache(t *testing.T) {
	data := t.TempDir()
	t.Setenv("XDG_DATA_HOME", data)
	st, err := OpenStore()
	if err != nil {
		t.Fatal(err)
	}

	var ytSearchHits, ytPlayerHits, itunesHits, wikiHits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/yt/search":
			ytSearchHits.Add(1)
			_, _ = w.Write([]byte(`{
				"contents": {"sectionListRenderer": {"contents": [{"videoRenderer": {
					"videoId": "abcdefghijk",
					"title": {"runs": [{"text": "Pinking (Official Audio)"}]},
					"ownerText": {"runs": [{"text": "Shikimori - Topic"}]}
				}}]}}
			}`))
		case "/yt/player":
			ytPlayerHits.Add(1)
			body, _ := io.ReadAll(r.Body)
			if strings.Contains(string(body), "bocchi12345") {
				_, _ = w.Write([]byte(`{
					"videoDetails": {
						"title": "Seishun Complex (Bocchi the Rock! OP)",
						"keywords": ["Official", "Bocchi the Rock!", "Kessoku Band", "J-Rock", "Anime OP"],
						"shortDescription": "Provided to YouTube by Aniplex\nhttps://example.com/promo\nBocchi the Rock! opening theme song by Kessoku Band.\nSubscribe for more!"
					}
				}`))
			} else {
				_, _ = w.Write([]byte(`{
					"videoDetails": {
						"title": "Pinking",
						"keywords": ["Music", "Indie Pop", "Soft Pop"],
						"shortDescription": "https://spotify.com/foo\nDreamy bedroom soft pop track.\n℗ 2024 Indie Label"
					}
				}`))
			}
		case "/itunes":
			itunesHits.Add(1)
			term := strings.ToLower(r.URL.Query().Get("term"))
			if strings.Contains(term, "kessoku") {
				_, _ = w.Write([]byte(`{"results":[{"artistName":"Kessoku Band","trackName":"Seishun Complex","collectionName":"Kessoku Band","primaryGenreName":"Anime","releaseDate":"2022-10-09T07:00:00Z"}]}`))
			} else {
				_, _ = w.Write([]byte(`{"results":[{"artistName":"Shikimori","trackName":"Pinking","collectionName":"Pinking - Single","primaryGenreName":"Pop","releaseDate":"2024-03-01T00:00:00Z"}]}`))
			}
		case "/wiki":
			wikiHits.Add(1)
			gs := strings.ToLower(r.URL.Query().Get("gsrsearch"))
			if strings.Contains(gs, "pinking") {
				// Non-music article that should be rejected by relevance guard
				_, _ = w.Write([]byte(`{"query":{"pages":{"1":{"title":"Pinking shears","extract":"Pinking shears are scissors, the blades of which are sawtoothed instead of straight."}}}}`))
				return
			}
			if strings.Contains(gs, "kessoku band") {
				_, _ = w.Write([]byte(`{"query":{"pages":{"2":{"title":"Kessoku Band","extract":"Kessoku Band is a Japanese rock band from the anime Bocchi the Rock!."}}}}`))
				return
			}
			_, _ = w.Write([]byte(`{"query":{"pages":{}}}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	a := &App{store: st, tracks: map[string]Track{}, set: defaultSettings()}
	root := st.EnsureRoot(filepath.Join(data, "music"))
	tr1 := Track{ID: "yt:bocchi12345", YtID: "bocchi12345", RootKey: root, RelPath: "bocchi.m4a", Artist: "Kessoku Band", Title: "Seishun Complex", AddedAt: 20}
	tr2 := Track{ID: "local:pinking", RootKey: root, RelPath: "pinking.mp3", Artist: "Shikimori", Title: "Pinking", Year: 2024, AddedAt: 10}
	if _, err := st.UpsertTrack(tr1); err != nil {
		t.Fatal(err)
	}
	if _, err := st.UpsertTrack(tr2); err != nil {
		t.Fatal(err)
	}

	enricher := NewContextEnricher()
	enricher.ytPlayerURL = srv.URL + "/yt/player"
	enricher.ytSearchURL = srv.URL + "/yt/search"
	enricher.ytWatchURL = ""
	enricher.itunesURL = srv.URL + "/itunes"
	enricher.wikiURL = srv.URL + "/wiki"

	var capturedInputs []TrackGenreInput
	g := NewGenreDetector(a)
	g.enricher = enricher
	g.onlineLookup = func(_, _ string) ([]string, string, error) { return nil, "", nil }
	g.aiFactory = func(_ context.Context, _ AISettings) (AIProvider, func(), int, error) {
		p := &fakeAIProvider{
			fn: func(items []TrackGenreInput) (string, error) {
				capturedInputs = append(capturedInputs, items...)
				var res []TrackGenreOutput
				for _, it := range items {
					if it.Artist == "Kessoku Band" {
						res = append(res, TrackGenreOutput{Index: it.Index, Genres: []string{"Anime", "J-Rock"}})
					} else {
						res = append(res, TrackGenreOutput{Index: it.Index, Genres: []string{"Soft Pop", "Indie Pop"}})
					}
				}
				b, _ := json.Marshal(map[string]any{"results": res})
				return string(b), nil
			},
		}
		return p, func() {}, 10, nil
	}

	if msg := g.StartAI("missing"); msg != "" {
		t.Fatal(msg)
	}
	stat := waitDetectorDone(t, g)
	if stat.Error != "" {
		t.Fatalf("unexpected detector error: %+v", stat)
	}
	if len(capturedInputs) != 2 {
		t.Fatalf("expected 2 enriched AI inputs, got %d", len(capturedInputs))
	}

	byTitle := map[string]TrackGenreInput{}
	for _, in := range capturedInputs {
		byTitle[in.Title] = in
	}
	bocchiIn := byTitle["Seishun Complex"]
	if !strings.Contains(bocchiIn.Hints, "Bocchi the Rock!") ||
		!strings.Contains(bocchiIn.Hints, "iTunes: Anime") ||
		!strings.Contains(bocchiIn.Hints, "Japanese rock band") ||
		strings.Contains(bocchiIn.Hints, "https://") ||
		strings.Contains(bocchiIn.Hints, "Subscribe") {
		t.Fatalf("unexpected Bocchi hints: %q", bocchiIn.Hints)
	}
	pinkIn := byTitle["Pinking"]
	if pinkIn.Year != 2024 ||
		!strings.Contains(pinkIn.Hints, "Dreamy bedroom soft pop track.") ||
		!strings.Contains(pinkIn.Hints, "Soft Pop") ||
		strings.Contains(pinkIn.Hints, "Pinking shears") ||
		strings.Contains(pinkIn.Hints, "spotify.com") {
		t.Fatalf("unexpected Pinking input/hints: %+v", pinkIn)
	}

	// Second EnrichTracks call must hit SQLite cache and trigger zero new HTTP requests.
	prevYTP, prevYTS, prevIT, prevW := ytPlayerHits.Load(), ytSearchHits.Load(), itunesHits.Load(), wikiHits.Load()
	cached := enricher.EnrichTracks(context.Background(), st, []Track{tr1, tr2}, nil)
	if len(cached) != 2 || cached[tr1.ID] == "" || cached[tr2.ID] == "" {
		t.Fatalf("expected cached hints, got %+v", cached)
	}
	if ytPlayerHits.Load() != prevYTP || ytSearchHits.Load() != prevYTS || itunesHits.Load() != prevIT || wikiHits.Load() != prevW {
		t.Fatalf("expected 0 new HTTP requests on cached run")
	}
}

func TestEnricherWatchFallbackJPFallbackNoEmptyCacheAndHeuristicMerge(t *testing.T) {
	data := t.TempDir()
	t.Setenv("XDG_DATA_HOME", data)
	st, err := OpenStore()
	if err != nil {
		t.Fatal(err)
	}

	var jpCalls, emptyRetryCalls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/yt/player":
			w.WriteHeader(http.StatusBadRequest)
		case "/watch":
			w.Header().Set("Content-Type", "text/html")
			_, _ = w.Write([]byte(`<html><script>var ytInitialPlayerResponse = {"videoDetails":{"title":"WHITE NIGHT","keywords":["Honkai Star Rail","HOYO-MIX","J-Pop"],"shortDescription":"Penacony theme song\nSubscribe"},"microformat":{"playerMicroformatRenderer":{"category":"Gaming"}}};</script></html>`))
		case "/itunes":
			w.Header().Set("Content-Type", "application/json")
			if r.URL.Query().Get("country") == "JP" {
				jpCalls.Add(1)
				_, _ = w.Write([]byte(`{"results":[{"artistName":"San-Z, HOYO-MIX","trackName":"Stars Align","collectionName":"ZZZ","primaryGenreName":"Soundtrack","releaseDate":"2024-07-04T00:00:00Z"}]}`))
				return
			}
			_, _ = w.Write([]byte(`{"results":[]}`))
		case "/itunes-empty":
			emptyRetryCalls.Add(1)
			w.WriteHeader(http.StatusInternalServerError)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	a := &App{store: st, tracks: map[string]Track{}, set: defaultSettings()}
	root := st.EnsureRoot(filepath.Join(data, "music"))
	trWatch := Track{ID: "yt:whitenight1", YtID: "whitenight1", RootKey: root, RelPath: "wn.m4a", Artist: "HOYO-MIX", Title: "WHITE NIGHT"}
	trJP := Track{ID: "local:sanz1", RootKey: root, RelPath: "stars.m4a", Artist: "Sān-Z, HOYO-MIX", Title: "Stars Align (Piano Version)", Album: "Honkai Game OST"}
	trEmpty := Track{ID: "local:miss1", RootKey: root, RelPath: "miss.m4a", Artist: "Unknown", Title: "NoHit"}
	_, _ = st.UpsertTrack(trWatch)
	_, _ = st.UpsertTrack(trJP)
	_, _ = st.UpsertTrack(trEmpty)

	en := NewContextEnricher()
	en.ytPlayerURL = srv.URL + "/yt/player"
	en.ytSearchURL = ""
	en.ytWatchURL = srv.URL + "/watch"
	en.itunesURL = srv.URL + "/itunes"
	en.wikiURL = ""

	res := en.EnrichTracks(context.Background(), st, []Track{trWatch, trJP}, nil)
	if !strings.Contains(res[trWatch.ID], "Honkai Star Rail") || !strings.Contains(res[trWatch.ID], "YT cat: Gaming") {
		t.Fatalf("expected watch?v= fallback clues, got %q", res[trWatch.ID])
	}
	if !strings.Contains(res[trJP.ID], "iTunes: Soundtrack") || jpCalls.Load() == 0 {
		t.Fatalf("expected JP iTunes fallback + diacritic artist match, got %q (jpCalls=%d)", res[trJP.ID], jpCalls.Load())
	}

	// Empty enrichment results must NOT be cached so subsequent runs can retry.
	enEmpty := NewContextEnricher()
	enEmpty.ytPlayerURL = ""
	enEmpty.ytSearchURL = ""
	enEmpty.ytWatchURL = ""
	enEmpty.itunesURL = srv.URL + "/itunes-empty"
	enEmpty.wikiURL = ""
	_ = enEmpty.EnrichTracks(context.Background(), st, []Track{trEmpty}, nil)
	_ = enEmpty.EnrichTracks(context.Background(), st, []Track{trEmpty}, nil)
	if emptyRetryCalls.Load() < 2 {
		t.Fatalf("expected empty hints NOT to be cached; iTunes hits=%d", emptyRetryCalls.Load())
	}

	// AI detection merges unambiguous heuristics while dedupeGenres strips redundant parent "Soundtrack".
	g := NewGenreDetector(a)
	g.enricher = nil
	g.onlineLookup = func(_, _ string) ([]string, string, error) { return nil, "", nil }
	g.aiFactory = func(_ context.Context, _ AISettings) (AIProvider, func(), int, error) {
		p := &fakeAIProvider{
			fn: func(items []TrackGenreInput) (string, error) {
				var out []TrackGenreOutput
				for _, it := range items {
					out = append(out, TrackGenreOutput{Index: it.Index, Genres: []string{"Video Game Music", "J-Pop"}})
				}
				b, _ := json.Marshal(map[string]any{"results": out})
				return string(b), nil
			},
		}
		return p, func() {}, 10, nil
	}
	if msg := g.StartAI("missing"); msg != "" {
		t.Fatal(msg)
	}
	stat := waitDetectorDone(t, g)
	if stat.Error != "" {
		t.Fatalf("detector error: %+v", stat)
	}
	gotJP, ok := st.TrackByID(trJP.ID)
	if !ok {
		t.Fatalf("track %s not found", trJP.ID)
	}
	if gotJP.Genre != "Video Game Music|J-Pop|Piano" {
		t.Fatalf("expected AI + Piano heuristic with Soundtrack stripped, got %q", gotJP.Genre)
	}
}

