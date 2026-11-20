package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type AIProviderPreset struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	Kind           string `json:"kind"` // "local" | "openai" | "gemini" | "anthropic"
	Local          bool   `json:"local"`
	NeedsKey       bool   `json:"needsKey"`
	DefaultBaseURL string `json:"defaultBaseUrl"`
	DefaultModel   string `json:"defaultModel"`
	Note           string `json:"note"`
}

var aiProviderPresets = []AIProviderPreset{
	{
		ID:     "local",
		Name:   "Local AI (Built-in — downloads on demand)",
		Kind:   "local",
		Local:  true,
		Note:   "Runs privately on your PC via llama.cpp. Nothing is downloaded until you choose to.",
	},
	{
		ID:             "ollama",
		Name:           "Ollama (Local server)",
		Kind:           "openai",
		Local:          true,
		DefaultBaseURL: "http://localhost:11434/v1",
		DefaultModel:   "qwen2.5:3b",
		Note:           "Connects to an Ollama server running on your machine (no API key needed).",
	},
	{
		ID:             "lmstudio",
		Name:           "LM Studio (Local server)",
		Kind:           "openai",
		Local:          true,
		DefaultBaseURL: "http://localhost:1234/v1",
		DefaultModel:   "local-model",
		Note:           "Connects to LM Studio's local OpenAI-compatible server.",
	},
	{
		ID:             "openai",
		Name:           "OpenAI",
		Kind:           "openai",
		NeedsKey:       true,
		DefaultBaseURL: "https://api.openai.com/v1",
		DefaultModel:   "gpt-4o-mini",
	},
	{
		ID:             "gemini",
		Name:           "Google Gemini",
		Kind:           "gemini",
		NeedsKey:       true,
		DefaultBaseURL: "https://generativelanguage.googleapis.com/v1beta",
		DefaultModel:   "gemini-3.5-flash-lite",
		Note:           "Works with free AI Studio keys (gemini-3.5-flash-lite, 15 RPM) or gemini-3.8-flash.",
	},
	{
		ID:             "anthropic",
		Name:           "Anthropic (Claude)",
		Kind:           "anthropic",
		NeedsKey:       true,
		DefaultBaseURL: "https://api.anthropic.com/v1",
		DefaultModel:   "claude-haiku-4-5",
	},
	{
		ID:             "openrouter",
		Name:           "OpenRouter",
		Kind:           "openai",
		NeedsKey:       true,
		DefaultBaseURL: "https://openrouter.ai/api/v1",
		DefaultModel:   "google/gemini-2.5-flash",
	},
	{
		ID:             "groq",
		Name:           "Groq",
		Kind:           "openai",
		NeedsKey:       true,
		DefaultBaseURL: "https://api.groq.com/openai/v1",
		DefaultModel:   "llama-3.3-70b-versatile",
	},
	{
		ID:             "mistral",
		Name:           "Mistral",
		Kind:           "openai",
		NeedsKey:       true,
		DefaultBaseURL: "https://api.mistral.ai/v1",
		DefaultModel:   "mistral-small-latest",
	},
	{
		ID:             "deepseek",
		Name:           "DeepSeek",
		Kind:           "openai",
		NeedsKey:       true,
		DefaultBaseURL: "https://api.deepseek.com/v1",
		DefaultModel:   "deepseek-chat",
	},
	{
		ID:   "custom",
		Name: "Custom (OpenAI-compatible)",
		Kind: "openai",
		Note: "Any server speaking the OpenAI Chat Completions API.",
	},
}

func GetAIProviders() []AIProviderPreset {
	out := make([]AIProviderPreset, len(aiProviderPresets))
	copy(out, aiProviderPresets)
	return out
}

func findAIPreset(id string) (AIProviderPreset, bool) {
	for _, p := range aiProviderPresets {
		if p.ID == id {
			return p, true
		}
	}
	return AIProviderPreset{}, false
}

type TrackGenreInput struct {
	Index  int    `json:"i"`
	Artist string `json:"artist"`
	Title  string `json:"title"`
	Album  string `json:"album,omitempty"`
	Year   int    `json:"year,omitempty"`
	Hints  string `json:"hints,omitempty"`
}

type TrackGenreOutput struct {
	Index  int      `json:"i"`
	Genres []string `json:"genres"`
	Custom []string `json:"custom,omitempty"`
}

type AITestResult struct {
	OK        bool   `json:"ok"`
	Message   string `json:"message"`
	LatencyMs int64  `json:"latencyMs"`
}

type AIError struct {
	StatusCode int
	Kind       string // "auth" | "quota" | "rate_limit" | "transient" | "bad_request" | "network"
	Message    string
	RetryAfter time.Duration
}

func (e *AIError) Error() string {
	return e.Message
}

type AIProvider interface {
	ClassifyBatch(ctx context.Context, systemPrompt, userPrompt string) (string, error)
}

type resolvedAIConfig struct {
	providerID      string
	kind            string
	baseURL         string
	apiKey          string
	model           string
	webSearch       bool
	geminiGrounding bool
}

func resolveAIConfig(cfg AISettings) (resolvedAIConfig, error) {
	id := strings.TrimSpace(cfg.Provider)
	if id == "" {
		id = "openai"
	}
	preset, ok := findAIPreset(id)
	if !ok {
		return resolvedAIConfig{}, fmt.Errorf("unknown AI provider %q", id)
	}
	baseURL := strings.TrimRight(strings.TrimSpace(cfg.BaseURL), "/")
	if baseURL == "" {
		baseURL = strings.TrimRight(preset.DefaultBaseURL, "/")
	}
	model := strings.TrimSpace(cfg.Model)
	if model == "" {
		model = preset.DefaultModel
	}
	apiKey := strings.TrimSpace(cfg.APIKey)

	if preset.NeedsKey && apiKey == "" {
		return resolvedAIConfig{}, fmt.Errorf("API key is required for %s", preset.Name)
	}
	if preset.Kind != "local" {
		if baseURL == "" {
			return resolvedAIConfig{}, fmt.Errorf("base URL is required for %s", preset.Name)
		}
		if model == "" {
			return resolvedAIConfig{}, fmt.Errorf("model name is required for %s", preset.Name)
		}
	}
	return resolvedAIConfig{
		providerID:      preset.ID,
		kind:            preset.Kind,
		baseURL:         baseURL,
		apiKey:          apiKey,
		model:           model,
		webSearch:       cfg.WebSearch,
		geminiGrounding: cfg.GeminiGrounding,
	}, nil
}

type httpAIProvider struct {
	cfg         resolvedAIConfig
	client      *http.Client
	retryBase   time.Duration
	noRetry     bool
	onRetryWait func(wait time.Duration, reason string)
}

func newHTTPAIClient(cfg resolvedAIConfig) *httpAIProvider {
	return &httpAIProvider{
		cfg:       cfg,
		client:    &http.Client{Timeout: 90 * time.Second},
		retryBase: 2 * time.Second,
	}
}

func sanitizeAIError(msg, apiKey string) string {
	msg = strings.TrimSpace(msg)
	if len(apiKey) >= 4 {
		msg = strings.ReplaceAll(msg, apiKey, "[REDACTED]")
	}
	if len(msg) > 500 {
		msg = msg[:500] + "..."
	}
	return msg
}

func extractAPIErrorMessage(body []byte) string {
	var parsed struct {
		Error *struct {
			Message string `json:"message"`
		} `json:"error"`
		Message string `json:"message"`
		Detail  string `json:"detail"`
	}
	if json.Unmarshal(body, &parsed) == nil {
		if parsed.Error != nil && strings.TrimSpace(parsed.Error.Message) != "" {
			return strings.TrimSpace(parsed.Error.Message)
		}
		if strings.TrimSpace(parsed.Message) != "" {
			return strings.TrimSpace(parsed.Message)
		}
		if strings.TrimSpace(parsed.Detail) != "" {
			return strings.TrimSpace(parsed.Detail)
		}
	}
	return strings.TrimSpace(string(body))
}

func parseRetryAfter(h string) time.Duration {
	h = strings.TrimSpace(h)
	if h == "" {
		return 0
	}
	if secs, err := strconv.Atoi(h); err == nil && secs > 0 {
		d := time.Duration(secs) * time.Second
		if d > 60*time.Second {
			return 60 * time.Second
		}
		return d
	}
	return 0
}

func (p *httpAIProvider) ClassifyBatch(ctx context.Context, systemPrompt, userPrompt string) (string, error) {
	return p.callWithRetry(ctx, func() (string, error) {
		switch p.cfg.kind {
		case "openai":
			return p.callOpenAI(ctx, systemPrompt, userPrompt, true)
		case "gemini":
			return p.callGemini(ctx, systemPrompt, userPrompt, true, true, true)
		case "anthropic":
			return p.callAnthropic(ctx, systemPrompt, userPrompt)
		default:
			return "", fmt.Errorf("unsupported AI adapter %q", p.cfg.kind)
		}
	})
}

func (p *httpAIProvider) callWithRetry(ctx context.Context, fn func() (string, error)) (string, error) {
	if p.noRetry {
		return fn()
	}
	base := p.retryBase
	if base <= 0 {
		base = 2 * time.Second
	}
	rateWaits := []time.Duration{base * 2, base * 7, base * 15, base * 31}
	transientWaits := []time.Duration{base, base * 2, base * 4, base * 7, base * 10}

	rateAttempt := 0
	transAttempt := 0

	for {
		out, err := fn()
		if err == nil {
			return out, nil
		}
		if ctx.Err() != nil {
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				var ae *AIError
				if errors.As(err, &ae) && ae.Message != "" {
					return "", fmt.Errorf("%s (timed out)", ae.Message)
				}
			}
			return "", err
		}
		var ae *AIError
		if !errors.As(err, &ae) {
			return "", err
		}
		switch ae.Kind {
		case "auth", "quota", "bad_request":
			return "", ae
		case "rate_limit":
			if rateAttempt >= len(rateWaits) {
				return "", ae
			}
			wait := rateWaits[rateAttempt]
			if ae.RetryAfter > 0 {
				wait = ae.RetryAfter
			}
			rateAttempt++
			if p.onRetryWait != nil {
				p.onRetryWait(wait, ae.Message)
			}
			if err := sleepCtx(ctx, wait); err != nil {
				if errors.Is(err, context.DeadlineExceeded) {
					return "", fmt.Errorf("%s (timed out while retrying)", ae.Message)
				}
				return "", err
			}
		case "transient", "network":
			if transAttempt >= len(transientWaits) {
				return "", ae
			}
			wait := transientWaits[transAttempt]
			transAttempt++
			if p.onRetryWait != nil {
				p.onRetryWait(wait, ae.Message)
			}
			if err := sleepCtx(ctx, wait); err != nil {
				if errors.Is(err, context.DeadlineExceeded) {
					return "", fmt.Errorf("%s (timed out while retrying)", ae.Message)
				}
				return "", err
			}
		default:
			return "", ae
		}
	}
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return ctx.Err()
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

func (p *httpAIProvider) doJSONPost(ctx context.Context, endpoint string, headers map[string]string, payload any) (int, http.Header, []byte, error) {
	b, err := json.Marshal(payload)
	if err != nil {
		return 0, nil, nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(b))
	if err != nil {
		return 0, nil, nil, &AIError{Kind: "bad_request", Message: sanitizeAIError(err.Error(), p.cfg.apiKey)}
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := p.client.Do(req)
	if err != nil {
		if errors.Is(ctx.Err(), context.Canceled) {
			return 0, nil, nil, ctx.Err()
		}
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return 0, nil, nil, &AIError{Kind: "network", Message: "request timed out waiting for AI provider response"}
		}
		return 0, nil, nil, &AIError{Kind: "network", Message: sanitizeAIError("network error: "+err.Error(), p.cfg.apiKey)}
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		if errors.Is(ctx.Err(), context.Canceled) {
			return resp.StatusCode, resp.Header, nil, ctx.Err()
		}
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return resp.StatusCode, resp.Header, nil, &AIError{Kind: "network", Message: "request timed out waiting for AI provider response"}
		}
		return resp.StatusCode, resp.Header, nil, &AIError{Kind: "network", Message: sanitizeAIError("read error: "+err.Error(), p.cfg.apiKey)}
	}
	return resp.StatusCode, resp.Header, body, nil
}

var geminiRetryInRe = regexp.MustCompile(`(?i)retry in (\d+(?:\.\d+)?)s`)

func parseGeminiRetryInfo(body []byte) (retryAfter time.Duration, isDailyQuota bool, isZeroLimit bool) {
	var parsed struct {
		Error struct {
			Message string `json:"message"`
			Details []struct {
				Type       string `json:"@type"`
				RetryDelay string `json:"retryDelay"`
				Violations []struct {
					QuotaMetric string `json:"quotaMetric"`
					QuotaID     string `json:"quotaId"`
					QuotaValue  string `json:"quotaValue"`
				} `json:"violations"`
			} `json:"details"`
		} `json:"error"`
	}
	if json.Unmarshal(body, &parsed) != nil {
		return 0, false, false
	}
	for _, d := range parsed.Error.Details {
		if strings.Contains(d.Type, "QuotaFailure") {
			for _, v := range d.Violations {
				qid := strings.ToLower(v.QuotaID)
				if strings.Contains(qid, "perday") || strings.Contains(qid, "daily") {
					isDailyQuota = true
				}
				if strings.TrimSpace(v.QuotaValue) == "0" {
					isZeroLimit = true
				}
			}
		}
		if strings.Contains(d.Type, "RetryInfo") && d.RetryDelay != "" {
			if dur, err := time.ParseDuration(strings.TrimSpace(d.RetryDelay)); err == nil && dur > 0 {
				dur += time.Second
				if dur > 75*time.Second {
					dur = 75 * time.Second
				}
				retryAfter = dur
			}
		}
	}
	if retryAfter == 0 {
		if m := geminiRetryInRe.FindStringSubmatch(parsed.Error.Message); len(m) == 2 {
			if secs, err := strconv.ParseFloat(m[1], 64); err == nil && secs > 0 {
				dur := time.Duration(secs*float64(time.Second)) + time.Second
				if dur > 75*time.Second {
					dur = 75 * time.Second
				}
				retryAfter = dur
			}
		}
	}
	return retryAfter, isDailyQuota, isZeroLimit
}

func (p *httpAIProvider) classifyHTTPFailure(status int, hdr http.Header, body []byte) *AIError {
	detail := sanitizeAIError(extractAPIErrorMessage(body), p.cfg.apiKey)
	lower := strings.ToLower(detail)
	switch {
	case status == http.StatusUnauthorized || status == http.StatusForbidden:
		msg := fmt.Sprintf("authentication failed (HTTP %d): check your API key", status)
		if detail != "" {
			msg += " — " + detail
		}
		return &AIError{StatusCode: status, Kind: "auth", Message: msg}
	case status == http.StatusBadRequest && (strings.Contains(lower, "api_key_invalid") || strings.Contains(lower, "invalid api key") || strings.Contains(lower, "api key not valid")):
		return &AIError{StatusCode: status, Kind: "auth", Message: "invalid API key — " + detail}
	case status == http.StatusTooManyRequests:
		gemRetry, isDaily, isZero := parseGeminiRetryInfo(body)
		if isZero || strings.Contains(lower, "limit: 0") {
			return &AIError{StatusCode: status, Kind: "quota", Message: "quota exceeded (HTTP 429, limit: 0) — " + detail}
		}
		if isDaily {
			return &AIError{StatusCode: status, Kind: "quota", Message: "daily quota exhausted (HTTP 429) — " + detail}
		}
		retryAfter := parseRetryAfter(hdr.Get("Retry-After"))
		if retryAfter == 0 {
			retryAfter = gemRetry
		}
		msg := "rate limited (HTTP 429)"
		if detail != "" {
			msg += " — " + detail
		}
		return &AIError{StatusCode: status, Kind: "rate_limit", Message: msg, RetryAfter: retryAfter}
	case status >= 500 || status == http.StatusRequestTimeout:
		msg := fmt.Sprintf("provider error (HTTP %d)", status)
		if detail != "" {
			msg += " — " + detail
		}
		return &AIError{StatusCode: status, Kind: "transient", Message: msg}
	default:
		msg := fmt.Sprintf("request rejected (HTTP %d)", status)
		if detail != "" {
			msg += " — " + detail
		}
		return &AIError{StatusCode: status, Kind: "bad_request", Message: msg}
	}
}

func (p *httpAIProvider) callOpenAI(ctx context.Context, systemPrompt, userPrompt string, useJSONFormat bool) (string, error) {
	payload := map[string]any{
		"model":       p.cfg.model,
		"temperature": 0.1,
		"messages": []map[string]string{
			{"role": "system", "content": systemPrompt},
			{"role": "user", "content": userPrompt},
		},
	}
	if useJSONFormat {
		payload["response_format"] = map[string]string{"type": "json_object"}
	}
	headers := map[string]string{}
	if p.cfg.apiKey != "" {
		headers["Authorization"] = "Bearer " + p.cfg.apiKey
	}
	if p.cfg.providerID == "openrouter" {
		headers["HTTP-Referer"] = "https://github.com/vibemusic"
		headers["X-Title"] = "VibeMusic"
	}

	endpoint := p.cfg.baseURL + "/chat/completions"
	status, hdr, body, err := p.doJSONPost(ctx, endpoint, headers, payload)
	if err != nil {
		return "", err
	}
	if status == http.StatusBadRequest && useJSONFormat {
		lower := strings.ToLower(string(body))
		if strings.Contains(lower, "response_format") || strings.Contains(lower, "json_object") {
			return p.callOpenAI(ctx, systemPrompt, userPrompt, false)
		}
	}
	if status < 200 || status >= 300 {
		return "", p.classifyHTTPFailure(status, hdr, body)
	}

	var res struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(body, &res); err != nil {
		return "", fmt.Errorf("invalid OpenAI response JSON: %w", err)
	}
	if len(res.Choices) == 0 {
		return "", errors.New("provider returned no choices")
	}
	return res.Choices[0].Message.Content, nil
}

func (p *httpAIProvider) callGemini(ctx context.Context, systemPrompt, userPrompt string, useThinkingConfig, useSearch, useJSONMime bool) (string, error) {
	model := strings.TrimPrefix(p.cfg.model, "models/")
	endpoint := fmt.Sprintf("%s/models/%s:generateContent", p.cfg.baseURL, url.PathEscape(model))
	genCfg := map[string]any{
		"temperature": 0.1,
	}
	if useJSONMime {
		genCfg["responseMimeType"] = "application/json"
	}
	if useThinkingConfig && strings.HasPrefix(strings.ToLower(model), "gemini-3") {
		genCfg["thinkingConfig"] = map[string]any{"thinkingLevel": "LOW"}
	}
	payload := map[string]any{
		"systemInstruction": map[string]any{
			"parts": []map[string]string{{"text": systemPrompt}},
		},
		"contents": []map[string]any{
			{
				"role":  "user",
				"parts": []map[string]string{{"text": userPrompt}},
			},
		},
		"generationConfig": genCfg,
	}
	if p.cfg.geminiGrounding && useSearch && strings.HasPrefix(strings.ToLower(model), "gemini-3") {
		payload["tools"] = []map[string]any{{"google_search": map[string]any{}}}
	}
	headers := map[string]string{}
	if p.cfg.apiKey != "" {
		headers["x-goog-api-key"] = p.cfg.apiKey
	}

	status, hdr, body, err := p.doJSONPost(ctx, endpoint, headers, payload)
	if err != nil {
		return "", err
	}
	if status == http.StatusBadRequest {
		lower := strings.ToLower(string(body))
		if useThinkingConfig && (strings.Contains(lower, "thinkingconfig") || strings.Contains(lower, "thinkinglevel")) {
			return p.callGemini(ctx, systemPrompt, userPrompt, false, useSearch, useJSONMime)
		}
		if useSearch && useJSONMime && (strings.Contains(lower, "json") || strings.Contains(lower, "mime") || strings.Contains(lower, "tool") || strings.Contains(lower, "google_search")) {
			return p.callGemini(ctx, systemPrompt, userPrompt, useThinkingConfig, true, false)
		}
		if useSearch && (strings.Contains(lower, "google_search") || strings.Contains(lower, "tool")) {
			return p.callGemini(ctx, systemPrompt, userPrompt, useThinkingConfig, false, true)
		}
	}
	if p.cfg.geminiGrounding && useSearch {
		lower := strings.ToLower(string(body))
		_, _, isZero := parseGeminiRetryInfo(body)
		if (status == http.StatusTooManyRequests && (isZero || strings.Contains(lower, "limit: 0") || strings.Contains(lower, "search") || strings.Contains(lower, "grounding"))) ||
			(status == http.StatusForbidden && (strings.Contains(lower, "search") || strings.Contains(lower, "grounding"))) {
			return p.callGemini(ctx, systemPrompt, userPrompt, useThinkingConfig, false, true)
		}
	}
	if status < 200 || status >= 300 {
		return "", p.classifyHTTPFailure(status, hdr, body)
	}

	var res struct {
		Candidates []struct {
			Content struct {
				Parts []struct {
					Text    string `json:"text"`
					Thought bool   `json:"thought"`
				} `json:"parts"`
			} `json:"content"`
		} `json:"candidates"`
	}
	if err := json.Unmarshal(body, &res); err != nil {
		return "", fmt.Errorf("invalid Gemini response JSON: %w", err)
	}
	if len(res.Candidates) == 0 {
		return "", errors.New("Gemini returned no candidates")
	}
	var sb strings.Builder
	for _, part := range res.Candidates[0].Content.Parts {
		if part.Thought {
			continue
		}
		sb.WriteString(part.Text)
	}
	return sb.String(), nil
}

func (p *httpAIProvider) callAnthropic(ctx context.Context, systemPrompt, userPrompt string) (string, error) {
	endpoint := p.cfg.baseURL + "/messages"
	payload := map[string]any{
		"model":       p.cfg.model,
		"max_tokens":  2048,
		"temperature": 0.1,
		"system":      systemPrompt,
		"messages": []map[string]string{
			{"role": "user", "content": userPrompt},
		},
	}
	headers := map[string]string{
		"anthropic-version": "2023-06-01",
	}
	if p.cfg.apiKey != "" {
		headers["x-api-key"] = p.cfg.apiKey
	}

	status, hdr, body, err := p.doJSONPost(ctx, endpoint, headers, payload)
	if err != nil {
		return "", err
	}
	if status < 200 || status >= 300 {
		return "", p.classifyHTTPFailure(status, hdr, body)
	}

	var res struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
	}
	if err := json.Unmarshal(body, &res); err != nil {
		return "", fmt.Errorf("invalid Anthropic response JSON: %w", err)
	}
	var sb strings.Builder
	for _, c := range res.Content {
		if c.Type == "text" {
			sb.WriteString(c.Text)
		}
	}
	return sb.String(), nil
}

// ---------- prompt, parsing & validation ----------

func formatGenreTreeForPrompt() string {
	children := map[string][]string{}
	var roots []string
	for _, n := range genreNodes {
		if n.Parent == "" {
			roots = append(roots, n.Name)
		} else {
			children[n.Parent] = append(children[n.Parent], n.Name)
		}
	}
	var formatNode func(name string) string
	formatNode = func(name string) string {
		kids := children[name]
		if len(kids) == 0 {
			return name
		}
		parts := make([]string, len(kids))
		for i, k := range kids {
			parts[i] = formatNode(k)
		}
		return name + " (" + strings.Join(parts, ", ") + ")"
	}
	var sb strings.Builder
	for _, r := range roots {
		sb.WriteString("- ")
		sb.WriteString(formatNode(r))
		sb.WriteByte('\n')
	}
	return sb.String()
}

func buildGenreSystemPrompt(allowCustom bool) string {
	var sb strings.Builder
	sb.WriteString("You are a music genre classifier. Classify each song individually using its artist, title, album, year, and any \"hints\" field.\n")
	sb.WriteString("Read \"hints\" (YouTube title/tags/description, iTunes genre, Wikipedia summary, MusicBrainz tags) as primary factual evidence while ignoring YouTube promo spam.\n")
	sb.WriteString("Different tracks by the same artist or composer may have different genres (for example an orchestral boss theme vs a vocal pop/rock track).\n\n")
	sb.WriteString("Allowed genre taxonomy (use exact names):\n")
	sb.WriteString(formatGenreTreeForPrompt())
	sb.WriteString("\nRules:\n")
	sb.WriteString("1. Assign up to 5 accurate sub-genres/styles/contexts per song (aim for 2 to 5 whenever multiple genuinely apply, do not pad with unrelated tags).\n")
	sb.WriteString("2. Combine BOTH media/origin context AND musical style when applicable: anime songs -> [\"Anime\", \"J-Rock\", \"Math Rock\"] or [\"Anime\", \"J-Pop\"]; game score -> [\"Video Game Music\", \"Orchestral\"]; game vocal pop/rock -> [\"Video Game Music\", \"J-Pop\"] or [\"Video Game Music\", \"Pop Rock\"].\n")
	sb.WriteString("3. Prefer specific/regional sub-genres (\"J-Rock\", \"J-Pop\", \"City Pop\", \"K-Pop\", \"Turkish Pop\", \"Turkish Hip-Hop\", \"Soft Pop\", \"Ballad\", \"Vocaloid\", \"Future Bass\") instead of generic \"Rock\", \"Pop\", or \"Alternative Rock\" when accurate.\n")
	sb.WriteString("4. Do NOT tag vocal pop, soft pop, ballad, or indie tracks as \"Electronic\" just for modern synths/production — use \"Electronic\" (or Synthpop/EDM/Future Bass) only when electronic synthesis or beats define the track.\n")
	sb.WriteString("5. Do NOT include broad parent genres when a more specific sub-genre fits (pick \"J-Rock\" instead of \"Rock\"; \"Anime\" or \"Video Game Music\" instead of \"Soundtrack\" — parents are implied automatically).\n")
	sb.WriteString("6. If you do not recognize the song/artist and \"hints\" does not identify it, return \"genres\": [] instead of guessing.\n")
	if allowCustom {
		sb.WriteString("7. Use exact names from the taxonomy in \"genres\". Only if an important genre is completely missing from the taxonomy, put it in \"custom\" (max 2 short names).\n")
		sb.WriteString("8. Reply ONLY with valid JSON: {\"results\":[{\"i\":1,\"genres\":[\"...\"],\"custom\":[]}]}\n")
	} else {
		sb.WriteString("7. Only use exact names from the taxonomy above; do not invent new genre names.\n")
		sb.WriteString("8. Reply ONLY with valid JSON: {\"results\":[{\"i\":1,\"genres\":[\"...\"]}]}\n")
	}
	return sb.String()
}

func buildGenreUserPrompt(items []TrackGenreInput) string {
	b, _ := json.Marshal(items)
	return "Classify these songs:\n" + string(b)
}

func stripMarkdownFences(s string) string {
	s = strings.TrimSpace(s)
	if strings.HasPrefix(s, "```") {
		if nl := strings.IndexByte(s, '\n'); nl >= 0 {
			s = s[nl+1:]
		} else {
			s = strings.TrimPrefix(s, "```")
		}
		if end := strings.LastIndex(s, "```"); end >= 0 {
			s = s[:end]
		}
	}
	return strings.TrimSpace(s)
}

func parseAIResponse(raw string) ([]TrackGenreOutput, error) {
	s := stripMarkdownFences(raw)
	if s == "" {
		return nil, errors.New("empty AI response")
	}
	tryParse := func(candidate string) ([]TrackGenreOutput, bool) {
		candidate = strings.TrimSpace(candidate)
		if strings.HasPrefix(candidate, "[") {
			var arr []TrackGenreOutput
			if json.Unmarshal([]byte(candidate), &arr) == nil {
				return arr, true
			}
		}
		if strings.HasPrefix(candidate, "{") {
			var obj map[string]json.RawMessage
			if json.Unmarshal([]byte(candidate), &obj) == nil {
				for _, key := range []string{"results", "tracks", "items", "songs"} {
					if r, ok := obj[key]; ok {
						var arr []TrackGenreOutput
						if json.Unmarshal(r, &arr) == nil {
							return arr, true
						}
					}
				}
			}
		}
		return nil, false
	}

	if out, ok := tryParse(s); ok {
		return out, nil
	}
	// Try extracting outermost JSON object or array if surrounded by prose.
	objStart, objEnd := strings.IndexByte(s, '{'), strings.LastIndexByte(s, '}')
	arrStart, arrEnd := strings.IndexByte(s, '['), strings.LastIndexByte(s, ']')
	if objStart >= 0 && objEnd > objStart && (arrStart < 0 || objStart < arrStart) {
		if out, ok := tryParse(s[objStart : objEnd+1]); ok {
			return out, nil
		}
	}
	if arrStart >= 0 && arrEnd > arrStart {
		if out, ok := tryParse(s[arrStart : arrEnd+1]); ok {
			return out, nil
		}
	}
	return nil, errors.New("could not parse JSON genre results from AI response")
}

var customStopwords = map[string]bool{
	"unknown": true, "none": true, "other": true, "n/a": true, "na": true,
	"music": true, "various": true, "misc": true, "genre": true, "song": true,
}

func validateAIGenres(out TrackGenreOutput, allowCustom bool) []string {
	var valid []string
	for _, g := range out.Genres {
		if c := canonicalGenreName(g); c != "" {
			valid = append(valid, c)
		}
	}
	if allowCustom {
		customAdded := 0
		for _, c := range out.Custom {
			c = strings.TrimSpace(c)
			if c == "" {
				continue
			}
			if canon := canonicalGenreName(c); canon != "" {
				valid = append(valid, canon)
				continue
			}
			if customAdded >= 2 || len(c) < 2 || len(c) > 40 || customStopwords[strings.ToLower(c)] {
				continue
			}
			valid = append(valid, c)
			customAdded++
		}
	}
	return dedupeGenres(valid, 5)
}

func testAIProvider(ctx context.Context, p AIProvider) AITestResult {
	if hp, ok := p.(*httpAIProvider); ok {
		cp := *hp
		cp.noRetry = true
		cp.cfg.webSearch = false
		cp.cfg.geminiGrounding = false
		p = &cp
	}
	tctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()

	start := time.Now()
	sys := buildGenreSystemPrompt(false)
	usr := buildGenreUserPrompt([]TrackGenreInput{
		{Index: 1, Artist: "Metallica", Title: "Master of Puppets", Album: "Master of Puppets"},
	})
	raw, err := p.ClassifyBatch(tctx, sys, usr)
	elapsed := time.Since(start).Milliseconds()
	if err != nil {
		return AITestResult{OK: false, Message: err.Error(), LatencyMs: elapsed}
	}
	parsed, err := parseAIResponse(raw)
	if err != nil {
		return AITestResult{OK: false, Message: err.Error(), LatencyMs: elapsed}
	}
	if len(parsed) == 0 {
		return AITestResult{OK: false, Message: "connected, but model returned empty results", LatencyMs: elapsed}
	}
	return AITestResult{
		OK:        true,
		Message:   fmt.Sprintf("Connected (%d ms)", elapsed),
		LatencyMs: elapsed,
	}
}
