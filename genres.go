package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode"

	"golang.org/x/text/unicode/norm"
)

// ---------- online lookup (MusicBrainz first, Deezer second; no API keys) ----------

var genreHTTP = &http.Client{Timeout: 15 * time.Second}

var (
	mbMu   sync.Mutex
	mbLast time.Time
)

// httpGetJSON retries rate-limit / transient errors with a back-off.
func httpGetJSON(u string, v any) error {
	var lastErr error
	for attempt := 0; attempt < 4; attempt++ {
		if attempt > 0 {
			time.Sleep(time.Duration(attempt) * 2500 * time.Millisecond)
		}
		req, _ := http.NewRequest("GET", u, nil)
		req.Header.Set("User-Agent", "VibeMusic/0.7 (https://github.com/vibemusic)")
		req.Header.Set("Accept", "application/json")
		resp, err := genreHTTP.Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		if resp.StatusCode == 503 || resp.StatusCode == 429 || resp.StatusCode >= 500 {
			io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			lastErr = fmt.Errorf("http %d", resp.StatusCode)
			continue
		}
		if resp.StatusCode != 200 {
			io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			return fmt.Errorf("http %d", resp.StatusCode)
		}
		err = json.NewDecoder(resp.Body).Decode(v)
		resp.Body.Close()
		return err
	}
	return lastErr
}

func normName(s string) string {
	s = norm.NFKD.String(s)
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if unicode.Is(unicode.Mn, r) {
			continue
		}
		if r == '.' || r == '\'' || r == '’' || r == '"' {
			continue
		}
		b.WriteRune(r)
	}
	return strings.Join(strings.Fields(b.String()), " ")
}

type mbTag struct {
	Count int    `json:"count"`
	Name  string `json:"name"`
}

// pickTags turns vote-counted tags into up to max taxonomy genres (most voted first).
func pickTags(tags []mbTag, max int) []string {
	sort.SliceStable(tags, func(i, j int) bool { return tags[i].Count > tags[j].Count })
	top := 0
	for _, t := range tags {
		if t.Count > top {
			top = t.Count
		}
	}
	var out []string
	for _, t := range tags {
		if t.Count <= 0 { // downvoted
			continue
		}
		if top >= 4 && float64(t.Count) < float64(top)*0.2 {
			continue
		}
		if g := mapTag(t.Name); g != "" {
			out = append(out, g)
		}
	}
	return dedupeGenres(out, max)
}

func mbLookup(artist string) ([]string, error) {
	mbMu.Lock()
	if d := time.Since(mbLast); d < 1100*time.Millisecond {
		time.Sleep(1100*time.Millisecond - d)
	}
	mbLast = time.Now()
	mbMu.Unlock()

	q := fmt.Sprintf(`artist:"%s"`, strings.ReplaceAll(artist, `"`, ""))
	var res struct {
		Artists []struct {
			Name   string  `json:"name"`
			Score  int     `json:"score"`
			Tags   []mbTag `json:"tags"`
			Genres []mbTag `json:"genres"`
		} `json:"artists"`
	}
	if err := httpGetJSON("https://musicbrainz.org/ws/2/artist/?fmt=json&limit=5&query="+url.QueryEscape(q), &res); err != nil {
		return nil, err
	}
	want := normName(artist)
	for _, a := range res.Artists {
		if a.Score < 85 || normName(a.Name) != want {
			continue
		}
		if g := pickTags(append(append([]mbTag{}, a.Genres...), a.Tags...), 4); len(g) > 0 {
			return g, nil
		}
	}
	return nil, nil
}

func deezerLookup(artist, sampleTitle string) ([]string, error) {
	time.Sleep(200 * time.Millisecond)
	// Deezer's advanced (quoted) syntax returns nothing for many queries, so use a plain query and
	// verify the artist name on the results instead.
	q := strings.TrimSpace(artist + " " + sampleTitle)
	var res struct {
		Data []struct {
			Artist struct {
				Name string `json:"name"`
			} `json:"artist"`
			Album struct {
				ID int64 `json:"id"`
			} `json:"album"`
		} `json:"data"`
	}
	if err := httpGetJSON("https://api.deezer.com/search/track?limit=10&q="+url.QueryEscape(q), &res); err != nil {
		return nil, err
	}
	want := normName(artist)
	for _, d := range res.Data {
		if normName(d.Artist.Name) != want || d.Album.ID == 0 {
			continue
		}
		time.Sleep(200 * time.Millisecond)
		var al struct {
			Genres struct {
				Data []struct {
					Name string `json:"name"`
				} `json:"data"`
			} `json:"genres"`
		}
		if err := httpGetJSON(fmt.Sprintf("https://api.deezer.com/album/%d", d.Album.ID), &al); err != nil {
			return nil, err
		}
		var out []string
		for _, g := range al.Genres.Data {
			if m := mapTag(g.Name); m != "" {
				out = append(out, m)
			}
		}
		if out = dedupeGenres(out, 4); len(out) > 0 {
			return out, nil
		}
	}
	return nil, nil
}

// mergeGenreSources combines MusicBrainz and Deezer genres, ranking genres corroborated by both
// sources first (preferring the more specific sub-genre when one is an ancestor of the other).
func mergeGenreSources(mb, dz []string, max int) []string {
	var corroborated []string
	for _, m := range mb {
		for _, d := range dz {
			switch {
			case m == d || isAncestorGenre(d, m):
				corroborated = append(corroborated, m)
			case isAncestorGenre(m, d):
				corroborated = append(corroborated, d)
			}
		}
	}
	combined := make([]string, 0, len(corroborated)+len(mb)+len(dz))
	combined = append(combined, corroborated...)
	combined = append(combined, mb...)
	combined = append(combined, dz...)
	return dedupeGenres(combined, max)
}

// lookupArtistGenres queries both MusicBrainz and Deezer and merges their results.
// An empty result with err==nil means at least one source responded and found nothing.
func lookupArtistGenres(artist, sampleTitle string) (genres []string, source string, err error) {
	mbG, mbErr := mbLookup(artist)
	dzG, dzErr := deezerLookup(artist, sampleTitle)

	if merged := mergeGenreSources(mbG, dzG, 5); len(merged) > 0 {
		switch {
		case len(mbG) > 0 && len(dzG) > 0:
			return merged, "musicbrainz+deezer", nil
		case len(mbG) > 0:
			return merged, "musicbrainz", nil
		default:
			return merged, "deezer", nil
		}
	}
	if mbErr != nil && dzErr != nil {
		return nil, "", fmt.Errorf("musicbrainz: %v; deezer: %w", mbErr, dzErr)
	}
	return nil, "", nil
}

// ---------- store helpers ----------

func (s *Store) SetTracksGenre(ids []string, genre, source string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	now := time.Now().Unix()
	for _, id := range ids {
		if _, err := tx.Exec(`UPDATE tracks SET genre=?, genre_source=?, updated_at=? WHERE id=?`, genre, source, now, id); err != nil {
			tx.Rollback()
			return err
		}
	}
	return tx.Commit()
}

type TrackGenreUpdate struct {
	Genre  string
	Source string
}

// SetTrackGenresBatch writes per-track genre updates in a single transaction while never overwriting user-set genres.
func (s *Store) SetTrackGenresBatch(updates map[string]TrackGenreUpdate) error {
	if len(updates) == 0 {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	now := time.Now().Unix()
	for id, u := range updates {
		if _, err := tx.Exec(`UPDATE tracks SET genre=?, genre_source=?, updated_at=? WHERE id=? AND genre_source<>'user'`, u.Genre, u.Source, now, id); err != nil {
			tx.Rollback()
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) artistGenres(key string) (genres []string, checkedAt int64, ok bool) {
	var g string
	err := s.db.QueryRow(`SELECT genre, checked_at FROM artist_genres WHERE artist_key=?`, key).Scan(&g, &checkedAt)
	return splitGenres(g), checkedAt, err == nil
}

func (s *Store) setArtistGenres(key string, genres []string, source string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, _ = s.db.Exec(`INSERT INTO artist_genres(artist_key,genre,source,checked_at) VALUES(?,?,?,?)
ON CONFLICT(artist_key) DO UPDATE SET genre=excluded.genre, source=excluded.source, checked_at=excluded.checked_at`,
		key, joinGenres(genres), source, time.Now().Unix())
}

// ResetGenres forgets every automatically detected genre (hand-set ones are kept) so detection starts over.
func (s *Store) ResetGenres() {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, _ = s.db.Exec(`UPDATE tracks SET genre='', genre_source='', updated_at=? WHERE genre_source<>'user'`, time.Now().Unix())
	_, _ = s.db.Exec(`DELETE FROM artist_genres`)
}

// migrateGenresV2 runs once: the old single-genre detection used a flat list with some wrong mappings.
func (s *Store) migrateGenresV2() {
	var v string
	if s.db.QueryRow(`SELECT value FROM settings WHERE key='genres_v2'`).Scan(&v) == nil {
		return
	}
	s.ResetGenres()
	s.mu.Lock()
	_, _ = s.db.Exec(`INSERT INTO settings(key,value) VALUES('genres_v2','1') ON CONFLICT(key) DO NOTHING`)
	s.mu.Unlock()
}

// ---------- background genre detection ----------

type GenreStatus struct {
	Running      bool   `json:"running"`
	Mode         string `json:"mode"`         // "" | "classic" | "ai"
	Phase        string `json:"phase"`
	Done         int    `json:"done"`
	Total        int    `json:"total"`
	Identified   int    `json:"identified"`   // songs with at least one genre
	AIIdentified int    `json:"aiIdentified"` // songs classified by AI
	Unidentified int    `json:"unidentified"` // songs we looked for and found nothing
	Unchecked    int    `json:"unchecked"`    // songs not processed yet
	PendingAI    int    `json:"pendingAI"`    // remaining tracks from an interrupted AI run
	Message      string `json:"message"`
	Error        string `json:"error"`
}

type GenreTargets struct {
	Missing   int `json:"missing"`
	AllAuto   int `json:"allAuto"`
	PendingAI int `json:"pendingAI"`
	Total     int `json:"total"`
}

type GenreDetector struct {
	app          *App
	mu           sync.Mutex
	running      bool
	again        bool
	againMode    string
	mode         string
	phase        string
	done         int
	total        int
	message      string
	errMsg       string
	cancel       context.CancelFunc
	aiFactory    func(ctx context.Context, cfg AISettings) (AIProvider, func(), int, error)
	onlineLookup func(artist, title string) ([]string, string, error)
	enricher     *ContextEnricher
	geminiPace   time.Duration
}

func NewGenreDetector(a *App) *GenreDetector {
	g := &GenreDetector{app: a}
	g.aiFactory = g.defaultAIFactory
	g.onlineLookup = lookupArtistGenres
	g.enricher = NewContextEnricher()
	g.geminiPace = 4200 * time.Millisecond
	return g
}

func (g *GenreDetector) Status() GenreStatus {
	g.mu.Lock()
	st := GenreStatus{
		Running: g.running,
		Mode:    g.mode,
		Phase:   g.phase,
		Done:    g.done,
		Total:   g.total,
		Message: g.message,
		Error:   g.errMsg,
	}
	g.mu.Unlock()
	if g.app.store != nil {
		db := g.app.store.db
		_ = db.QueryRow(`SELECT COUNT(*) FROM tracks WHERE genre<>''`).Scan(&st.Identified)
		_ = db.QueryRow(`SELECT COUNT(*) FROM tracks WHERE genre<>'' AND genre_source='ai'`).Scan(&st.AIIdentified)
		_ = db.QueryRow(`SELECT COUNT(*) FROM tracks WHERE genre='' AND genre_source='none'`).Scan(&st.Unidentified)
		_ = db.QueryRow(`SELECT COUNT(*) FROM tracks WHERE genre='' AND genre_source=''`).Scan(&st.Unchecked)
		st.PendingAI = len(g.app.store.LoadPendingAITracks())
	}
	return st
}

func (g *GenreDetector) Targets() GenreTargets {
	var t GenreTargets
	if g.app.store == nil {
		return t
	}
	db := g.app.store.db
	_ = db.QueryRow(`SELECT COUNT(*) FROM tracks`).Scan(&t.Total)
	_ = db.QueryRow(`SELECT COUNT(*) FROM tracks WHERE genre=''`).Scan(&t.Missing)
	_ = db.QueryRow(`SELECT COUNT(*) FROM tracks WHERE genre_source NOT IN ('user','tag')`).Scan(&t.AllAuto)
	t.PendingAI = len(g.app.store.LoadPendingAITracks())
	return t
}

func (g *GenreDetector) setPhase(phase string, total int) {
	g.mu.Lock()
	g.phase, g.total, g.done = phase, total, 0
	g.mu.Unlock()
	g.app.emit("genres:status", g.Status())
}

func (g *GenreDetector) tick(done int) {
	g.mu.Lock()
	g.done = done
	g.mu.Unlock()
}

func (g *GenreDetector) defaultAIFactory(ctx context.Context, cfg AISettings) (AIProvider, func(), int, error) {
	batchSize := 15
	switch {
	case cfg.Provider == "local" || cfg.Provider == "ollama" || cfg.Provider == "lmstudio":
		if cfg.WebSearch {
			batchSize = 8
		} else {
			batchSize = 10
		}
	case cfg.Provider == "gemini":
		if cfg.GeminiGrounding {
			batchSize = 8
		} else {
			batchSize = 15
		}
	case cfg.WebSearch:
		batchSize = 12
	}
	attachWait := func(hp *httpAIProvider) {
		hp.onRetryWait = func(wait time.Duration, _ string) {
			secs := int(wait.Round(time.Second).Seconds())
			if secs < 1 {
				secs = 1
			}
			g.mu.Lock()
			g.phase = fmt.Sprintf("Rate limited — waiting %ds to retry", secs)
			g.mu.Unlock()
			g.app.emit("genres:status", g.Status())
		}
	}
	if cfg.Provider == "local" {
		if g.app.localAI == nil {
			return nil, nil, 0, fmt.Errorf("local AI manager is not available")
		}
		modelPath, err := g.app.localAI.ModelPath(cfg)
		if err != nil {
			return nil, nil, 0, err
		}
		baseURL, err := g.app.localAI.Start(ctx, modelPath, cfg.LocalGPU)
		if err != nil {
			return nil, nil, 0, err
		}
		rcfg := resolvedAIConfig{
			providerID: "local",
			kind:       "openai",
			baseURL:    baseURL,
			model:      "local",
		}
		hp := newHTTPAIClient(rcfg)
		attachWait(hp)
		return hp, func() { g.app.localAI.Stop() }, batchSize, nil
	}
	rcfg, err := resolveAIConfig(cfg)
	if err != nil {
		return nil, nil, 0, err
	}
	hp := newHTTPAIClient(rcfg)
	attachWait(hp)
	return hp, func() {}, batchSize, nil
}

// Kick starts (or re-queues) a background detection pass for new/unchecked songs. Safe to call often.
func (g *GenreDetector) Kick() {
	g.startRun("auto", true)
}

// KickClassic runs the classic non-AI detection pass (tags, online lookup, heuristics).
func (g *GenreDetector) KickClassic() {
	g.startRun("classic", true)
}

// StartAI starts an explicit AI detection pass ("missing", "all", or "resume").
func (g *GenreDetector) StartAI(mode string) string {
	var runMode string
	switch mode {
	case "missing":
		runMode = "ai-missing"
	case "all":
		runMode = "ai-all"
	case "resume":
		runMode = "ai-resume"
	default:
		return fmt.Sprintf("unknown AI detection mode %q", mode)
	}
	if !g.startRun(runMode, false) {
		return "Genre detection is already running"
	}
	return ""
}

func (g *GenreDetector) startRun(runMode string, queueIfBusy bool) bool {
	g.mu.Lock()
	if g.running {
		if queueIfBusy {
			g.again = true
			if g.againMode == "" {
				g.againMode = runMode
			}
		}
		g.mu.Unlock()
		return false
	}
	ctx, cancel := context.WithCancel(context.Background())
	g.running = true
	g.again = false
	g.againMode = ""
	g.cancel = cancel
	g.message = ""
	g.errMsg = ""
	g.phase = "Starting"
	if strings.HasPrefix(runMode, "ai-") {
		g.mode = "ai"
	} else {
		g.mode = "classic"
	}
	g.done, g.total = 0, 0
	g.mu.Unlock()
	g.app.emit("genres:status", g.Status())
	go func() {
		curMode := runMode
		curCtx, curCancel := ctx, cancel
		for {
			g.runPass(curCtx, curMode)
			curCancel()
			g.mu.Lock()
			if !g.again {
				g.running = false
				g.mode = ""
				g.phase = ""
				g.cancel = nil
				g.mu.Unlock()
				break
			}
			curMode = g.againMode
			if curMode == "" {
				curMode = "auto"
			}
			g.again = false
			g.againMode = ""
			curCtx, curCancel = context.WithCancel(context.Background())
			g.cancel = curCancel
			g.mu.Unlock()
		}
		g.app.emit("genres:status", g.Status())
	}()
	return true
}

func (g *GenreDetector) Cancel() {
	g.mu.Lock()
	g.again = false
	g.againMode = ""
	cancel := g.cancel
	g.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func primaryArtistKeys(artist string) [][2]string {
	var out [][2]string
	for _, a := range splitArtistsGo(artist) {
		if strings.EqualFold(a, "Unknown Artist") {
			continue
		}
		out = append(out, [2]string{normName(a), a})
		if len(out) == 2 { // main artist plus first featured artist
			break
		}
	}
	return out
}

func (g *GenreDetector) publish() {
	g.app.reloadTracks()
	g.app.emit("library:changed")
	g.app.emit("genres:status", g.Status())
}

func (g *GenreDetector) shouldAutoAI() bool {
	cfg := g.app.Settings().AI
	if !cfg.Enabled || !cfg.AutoOnNew {
		return false
	}
	if cfg.Provider == "local" {
		return g.app.localAI != nil && g.app.localAI.IsReadyForAuto(cfg)
	}
	_, err := resolveAIConfig(cfg)
	return err == nil
}

func (g *GenreDetector) runPass(ctx context.Context, runMode string) {
	st := g.app.store
	tracks, err := st.AllTracks()
	if err != nil {
		return
	}

	// 1) embedded tags for songs that were never checked
	var unchecked []Track
	for _, t := range tracks {
		if t.Genre == "" && t.GenreSource == "" && t.Path != "" {
			unchecked = append(unchecked, t)
		}
	}
	if len(unchecked) > 0 {
		g.setPhase("Reading file tags", len(unchecked))
		var wg sync.WaitGroup
		sem := make(chan struct{}, 4)
		var cmu sync.Mutex
		n := 0
		for _, t := range unchecked {
			if ctx.Err() != nil {
				break
			}
			wg.Add(1)
			sem <- struct{}{}
			go func(t Track) {
				defer wg.Done()
				defer func() { <-sem }()
				if ctx.Err() == nil {
					if p, err := probe(t.Path); err == nil {
						if gs := tagGenres(p.tags()["genre"]); len(gs) > 0 {
							_ = st.SetTracksGenre([]string{t.ID}, joinGenres(gs), "tag")
						}
					}
				}
				cmu.Lock()
				n++
				g.tick(n)
				cmu.Unlock()
			}(t)
		}
		wg.Wait()
	}

	if ctx.Err() != nil {
		g.mu.Lock()
		g.message = "Stopped"
		g.mu.Unlock()
		g.publish()
		return
	}

	explicitAI := runMode == "ai-missing" || runMode == "ai-all" || runMode == "ai-resume"
	runAI := explicitAI || (runMode == "auto" && g.shouldAutoAI())

	// 2) AI classification (per song in batches)
	if runAI {
		tracks, _ = st.AllTracks()
		var targetTracks []Track
		switch runMode {
		case "ai-resume":
			byID := make(map[string]Track, len(tracks))
			for _, t := range tracks {
				byID[t.ID] = t
			}
			for _, id := range st.LoadPendingAITracks() {
				if t, ok := byID[id]; ok && t.GenreSource != "user" && t.GenreSource != "tag" {
					targetTracks = append(targetTracks, t)
				}
			}
		case "ai-all":
			for _, t := range tracks {
				if t.GenreSource != "user" && t.GenreSource != "tag" {
					targetTracks = append(targetTracks, t)
				}
			}
		default: // "ai-missing" or "auto"
			for _, t := range tracks {
				if t.Genre == "" && t.GenreSource != "user" && t.GenreSource != "tag" {
					targetTracks = append(targetTracks, t)
				}
			}
		}

		if len(targetTracks) > 0 {
			g.mu.Lock()
			g.mode = "ai"
			g.mu.Unlock()

			targetIDs := make([]string, len(targetTracks))
			for i, t := range targetTracks {
				targetIDs[i] = t.ID
			}
			if explicitAI {
				_ = st.SavePendingAITracks(targetIDs)
			}

			cfg := g.app.Settings().AI
			var hintsMap map[string]string
			if cfg.WebSearch && g.enricher != nil {
				g.setPhase("Searching YouTube, iTunes & Wikipedia", len(targetTracks))
				hintsMap = g.enricher.EnrichTracks(ctx, st, targetTracks, func(done, _ int) {
					g.tick(done)
					if done%4 == 0 {
						g.app.emit("genres:status", g.Status())
					}
				})
				if ctx.Err() != nil {
					g.mu.Lock()
					g.message = "Stopped"
					g.mu.Unlock()
					g.publish()
					return
				}
			}

			factory := g.aiFactory
			if factory == nil {
				factory = g.defaultAIFactory
			}
			provider, cleanup, batchSize, err := factory(ctx, cfg)
			if err != nil {
				g.mu.Lock()
				g.errMsg = err.Error()
				g.message = err.Error()
				g.mu.Unlock()
				g.publish()
				return
			}
			if cleanup != nil {
				defer cleanup()
			}
			if batchSize <= 0 {
				batchSize = 15
			}

			g.setPhase("Classifying songs with AI", len(targetTracks))
			sysPrompt := buildGenreSystemPrompt(cfg.AllowCustomGenres)
			for start := 0; start < len(targetTracks); start += batchSize {
				if ctx.Err() != nil {
					g.mu.Lock()
					g.message = "Stopped"
					g.mu.Unlock()
					g.publish()
					return
				}
				end := start + batchSize
				if end > len(targetTracks) {
					end = len(targetTracks)
				}
				batch := targetTracks[start:end]
				inputs := make([]TrackGenreInput, len(batch))
				for idx, t := range batch {
					inputs[idx] = TrackGenreInput{
						Index:  idx + 1,
						Artist: t.Artist,
						Title:  t.Title,
						Album:  t.Album,
						Year:   t.Year,
						Hints:  hintsMap[t.ID],
					}
				}
				batchStart := time.Now()
				raw, err := provider.ClassifyBatch(ctx, sysPrompt, buildGenreUserPrompt(inputs))
				if err != nil {
					g.mu.Lock()
					if ctx.Err() != nil {
						g.message = "Stopped"
					} else {
						g.errMsg = err.Error()
						g.message = err.Error()
					}
					g.mu.Unlock()
					g.publish()
					return
				}
				parsed, err := parseAIResponse(raw)
				if err != nil {
					g.mu.Lock()
					g.errMsg = err.Error()
					g.message = err.Error()
					g.mu.Unlock()
					g.publish()
					return
				}
				byIdx := make(map[int]TrackGenreOutput, len(parsed))
				for _, out := range parsed {
					byIdx[out.Index] = out
				}
				updates := make(map[string]TrackGenreUpdate, len(batch))
				for idx, t := range batch {
					var gs []string
					if out, ok := byIdx[idx+1]; ok {
						gs = validateAIGenres(out, cfg.AllowCustomGenres)
					}
					if len(gs) > 0 {
						// Merge unambiguous title/album heuristics (dedupeGenres keeps specific sub-genres and drops redundant parents).
						if hg := heuristicGenres(t); len(hg) > 0 {
							gs = dedupeGenres(append(gs, hg...), 5)
						}
						updates[t.ID] = TrackGenreUpdate{Genre: joinGenres(gs), Source: "ai"}
					} else if runMode == "ai-all" {
						updates[t.ID] = TrackGenreUpdate{Genre: "", Source: ""}
					}
				}
				_ = st.SetTrackGenresBatch(updates)
				if explicitAI {
					if end < len(targetIDs) {
						_ = st.SavePendingAITracks(targetIDs[end:])
					} else {
						_ = st.ClearPendingAITracks()
					}
				}
				g.mu.Lock()
				g.phase = "Classifying songs with AI"
				g.done = end
				g.mu.Unlock()
				g.publish()
				if cfg.Provider == "gemini" && g.geminiPace > 0 && end < len(targetTracks) {
					if rem := g.geminiPace - time.Since(batchStart); rem > 0 {
						if err := sleepCtx(ctx, rem); err != nil {
							g.mu.Lock()
							g.message = "Stopped"
							g.mu.Unlock()
							g.publish()
							return
						}
					}
				}
			}
		} else if runMode == "ai-resume" {
			_ = st.ClearPendingAITracks()
		}
	} else {
		// Classic path: apply same-artist inference + title/album hints before online lookup
		g.setPhase("Matching songs by artist", 0)
		if g.applyGenres() {
			g.publish()
		}
	}

	if ctx.Err() != nil {
		g.mu.Lock()
		g.message = "Stopped"
		g.mu.Unlock()
		g.publish()
		return
	}

	// 3) online lookup (MusicBrainz + Deezer), once per artist that still has songs without genres
	tracks, _ = st.AllTracks()
	type job struct{ key, name, title string }
	seen := map[string]bool{}
	var jobs []job
	cutoff := time.Now().Add(-30 * 24 * time.Hour).Unix()
	for _, t := range tracks {
		if t.Genre != "" {
			continue
		}
		for _, k := range primaryArtistKeys(t.Artist) {
			if seen[k[0]] {
				continue
			}
			seen[k[0]] = true
			if gs, at, ok := st.artistGenres(k[0]); ok && (len(gs) > 0 || at > cutoff) {
				continue
			}
			jobs = append(jobs, job{k[0], k[1], t.Title})
		}
	}
	failures := 0
	if len(jobs) > 0 {
		g.setPhase("Looking up artists online", len(jobs))
	}
	lookupOnline := g.onlineLookup
	if lookupOnline == nil {
		lookupOnline = lookupArtistGenres
	}
	for i, j := range jobs {
		if ctx.Err() != nil {
			break
		}
		gs, src, err := lookupOnline(j.name, j.title)
		if err != nil && len(gs) == 0 {
			failures++
			if failures >= 5 {
				g.mu.Lock()
				g.message = "Could not reach the genre services (MusicBrainz/Deezer). Check your connection and try again."
				g.mu.Unlock()
				break
			}
		} else {
			failures = 0
			st.setArtistGenres(j.key, gs, src)
		}
		g.tick(i + 1)
		if (i+1)%8 == 0 {
			if g.applyGenres() {
				g.publish()
			} else {
				g.app.emit("genres:status", g.Status())
			}
		}
	}

	// 4) final apply; whatever is still empty has been looked at and found nothing
	g.setPhase("Finishing", 0)
	g.applyGenres()
	if ctx.Err() == nil && failures < 5 {
		st.mu.Lock()
		_, _ = st.db.Exec(`UPDATE tracks SET genre_source='none' WHERE genre='' AND genre_source=''`)
		st.mu.Unlock()
	}
	fin := g.Status()
	g.mu.Lock()
	if ctx.Err() != nil {
		g.message = "Stopped"
	} else if g.message == "" {
		g.message = fmt.Sprintf("%d songs have genres", fin.Identified)
		if fin.Unidentified > 0 {
			g.message += fmt.Sprintf("; %d could not be identified (no data found) — set those by hand from the track menu", fin.Unidentified)
		}
	}
	g.mu.Unlock()
	g.publish()
}

// applyGenres fills songs without a tag/user/ai genre from sibling songs by the same artist, the cached
// online lookup and title/album hints. It reports whether anything changed.
func (g *GenreDetector) applyGenres() bool {
	st := g.app.store
	tracks, err := st.AllTracks()
	if err != nil {
		return false
	}
	// genres of trusted songs (tag/user), and secondary AI songs, per artist
	counts := map[string]map[string]int{}
	aiCounts := map[string]map[string]int{}
	for _, t := range tracks {
		if t.Genre == "" {
			continue
		}
		var target map[string]map[string]int
		switch t.GenreSource {
		case "tag", "user":
			target = counts
		case "ai":
			target = aiCounts
		default:
			continue
		}
		for _, a := range splitArtistsGo(t.Artist) {
			k := normName(a)
			if target[k] == nil {
				target[k] = map[string]int{}
			}
			for _, gg := range splitGenres(t.Genre) {
				target[k][gg]++
			}
		}
	}
	dominantFrom := func(srcMap map[string]map[string]int, k string) []string {
		type kv struct {
			g string
			n int
		}
		var list []kv
		top := 0
		for gname, n := range srcMap[k] {
			list = append(list, kv{gname, n})
			if n > top {
				top = n
			}
		}
		sort.Slice(list, func(i, j int) bool { return list[i].n > list[j].n || (list[i].n == list[j].n && list[i].g < list[j].g) })
		var out []string
		for _, e := range list {
			if float64(e.n) >= float64(top)*0.5 {
				out = append(out, e.g)
			}
		}
		return dedupeGenres(out, 3)
	}

	type upd struct{ genre, src string }
	groups := map[upd][]string{}
	for _, t := range tracks {
		if t.GenreSource == "user" || t.GenreSource == "tag" || t.GenreSource == "ai" {
			continue
		}
		var base []string
		src := ""
		for _, k := range primaryArtistKeys(t.Artist) {
			if d := dominantFrom(counts, k[0]); len(d) > 0 {
				base, src = d, "inferred"
				break
			}
			if ag, _, ok := st.artistGenres(k[0]); ok && len(ag) > 0 {
				base, src = ag, "online"
				break
			}
			if d := dominantFrom(aiCounts, k[0]); len(d) > 0 {
				base, src = d, "inferred"
				break
			}
		}
		final := dedupeGenres(append(append([]string{}, base...), heuristicGenres(t)...), 5)
		if len(final) == 0 {
			continue
		}
		if src == "" {
			src = "heuristic"
		}
		if joinGenres(final) == t.Genre && src == t.GenreSource {
			continue
		}
		u := upd{joinGenres(final), src}
		groups[u] = append(groups[u], t.ID)
	}
	changed := false
	for u, ids := range groups {
		if st.SetTracksGenre(ids, u.genre, u.src) == nil {
			changed = true
		}
	}
	return changed
}
