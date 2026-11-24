package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"math/rand"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"
)

var (
	fileVidIDRe      = regexp.MustCompile(`\[([A-Za-z0-9_-]{11})\][^/\\]*$`)
	archiveSkipRe    = regexp.MustCompile(`\[download\]\s+([A-Za-z0-9_-]{11}):\s+has already been recorded in the archive`)
	alreadyDlRe      = regexp.MustCompile(`\[download\]\s+(.+?)\s+has already been downloaded`)
	ytSearchPrefixRe = regexp.MustCompile(`(?i)^ytsearch\d*:`)
)

func extractURLVideoID(raw string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return ""
	}
	if v := u.Query().Get("v"); len(v) == 11 {
		return v
	}
	host := strings.ToLower(u.Host)
	if strings.Contains(host, "youtu.be") {
		seg := strings.Trim(u.Path, "/")
		if i := strings.IndexByte(seg, '/'); i >= 0 {
			seg = seg[:i]
		}
		if len(seg) == 11 {
			return seg
		}
	}
	return ""
}

func trackVideoID(t Track) string {
	if ytIDRe.MatchString(t.YtID) {
		return t.YtID
	}
	if strings.HasPrefix(t.ID, "yt:") {
		if vid := strings.TrimPrefix(t.ID, "yt:"); ytIDRe.MatchString(vid) {
			return vid
		}
	}
	if vid := ytIDFromName(t.RelPath); ytIDRe.MatchString(vid) {
		return vid
	}
	if vid := ytIDFromName(t.Path); ytIDRe.MatchString(vid) {
		return vid
	}
	return ""
}

// existingVideoIDMap returns a map of 11-char YouTube video IDs -> file path for all tracks currently in the library.
func (d *Downloader) existingVideoIDMap() map[string]string {
	out := make(map[string]string)
	d.app.trMu.RLock()
	defer d.app.trMu.RUnlock()
	for _, t := range d.app.tracks {
		if vid := trackVideoID(t); vid != "" && t.Path != "" {
			if _, err := os.Stat(t.Path); err == nil {
				out[vid] = t.Path
			}
		}
	}
	return out
}

// ---------- Canonical Song Normalization & Matching ----------

var (
	quotedJaRe      = regexp.MustCompile(`[「『]([^「」『』]+)[」』]`)
	quotedASCIIRe   = regexp.MustCompile(`["“]([^"“”]+)["”]`)
	bracketRegexes  = []*regexp.Regexp{
		regexp.MustCompile(`\[[^\[\]]*\]`),
		regexp.MustCompile(`【[^【】]*】`),
		regexp.MustCompile(`\([^()]*\)`),
		regexp.MustCompile(`（[^（）]*）`),
	}
	unclosedBracketRe = regexp.MustCompile(`(?:\[[^\]]*|【[^】]*|\([^\)]*|（[^）]*)$`)
	trailingFeatRe    = regexp.MustCompile(`(?i)\s+(?:feat\.?|ft\.?|featuring)\s+.*$`)
	promoPhraseRe     = regexp.MustCompile(`(?i)\b(?:official\s+music\s+video|official\s+lyric\s+video|official\s+visualizer|official\s+video|official\s+audio|official\s+lyrics?|official\s+mv|music\s+video|lyric\s+video|chronicle\s+movie|the\s+first\s+take|first\s+take|valkyrie\s+theme|theme\s+song|animation\s+short|animated\s+short|character\s+demo|character\s+teaser|phoenix\s+ep|special\s+ep|original\s+soundtrack|soundtrack|full\s+ver(?:sion)?\.?|short\s+ver(?:sion)?\.?|live\s+ver(?:sion)?\.?|game\s+ver(?:sion)?\.?|tv\s+size|off\s+vocal|ost|mv|pv|audio|hd|4k)\b`)
	titleSepList      = []string{" // ", " — ", " – ", " ― ", " - ", " | ", " / ", " : ", " ~ ", " 〜 ", "—", "―", "｜"}
)

var songFranchisePatterns = []string{
	"the first take", "honkai impact", "genshin impact", "zenless zone zero",
	"honkai star rail", "wuthering waves", "arknights", "bang dream", "chronicle movie",
}

var songMetaEdgeWords = map[string]bool{
	"ep": true, "ost": true, "soundtrack": true, "theme": true, "live": true, "official": true, "cover": true,
}

var songGenericWords = map[string]bool{
	"track": true, "song": true, "music": true, "video": true, "audio": true,
	"ep": true, "ost": true, "theme": true, "live": true, "official": true,
	"mix": true, "remix": true, "cover": true, "demo": true, "full": true,
	"short": true, "ver": true, "version": true, "teaser": true, "trailer": true,
	"pv": true, "mv": true, "hd": true, "4k": true,
}

var songStopWords = map[string]bool{
	"the": true, "a": true, "an": true, "and": true, "or": true, "of": true,
	"in": true, "on": true, "at": true, "to": true, "for": true, "from": true,
	"with": true, "by": true, "feat": true, "ft": true, "featuring": true,
	"ver": true, "version": true, "original": true, "soundtrack": true, "ost": true,
	"music": true, "song": true, "theme": true, "edit": true, "mix": true,
	"remaster": true, "remastered": true, "official": true, "video": true,
	"audio": true, "lyric": true, "lyrics": true, "mv": true, "pv": true,
	"ep": true, "live": true, "hd": true, "4k": true, "full": true, "short": true,
}

func normSongText(s string) string {
	s = norm.NFKC.String(s)
	s = strings.ToLower(s)
	var b strings.Builder
	b.Grow(len(s))
	lastSpace := true
	for _, r := range s {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r) || r == '%':
			b.WriteRune(r)
			lastSpace = false
		case r == '\'' || r == '’' || r == '‘':
			// omit apostrophes so "don't" matches "dont"
		default:
			if !lastSpace {
				b.WriteByte(' ')
				lastSpace = true
			}
		}
	}
	return strings.TrimSpace(b.String())
}

func stripBrackets(s string) string {
	cur := s
	for pass := 0; pass < 3; pass++ {
		next := cur
		for _, re := range bracketRegexes {
			next = re.ReplaceAllString(next, " ")
		}
		if next == cur {
			break
		}
		cur = next
	}
	cur = unclosedBracketRe.ReplaceAllString(cur, " ")
	return strings.TrimSpace(cur)
}

func stripTitleDecorations(s string) string {
	orig := norm.NFKC.String(strings.TrimSpace(s))
	cur := stripBrackets(orig)
	cur = trailingFeatRe.ReplaceAllString(cur, " ")
	cur = promoPhraseRe.ReplaceAllString(cur, " ")
	cur = strings.TrimSpace(cur)
	if normSongText(cur) == "" {
		// Fall back if bracket/phrase stripping emptied the whole title (e.g. "(Everything I Do)" or "Audio")
		noUnclosed := unclosedBracketRe.ReplaceAllString(orig, " ")
		if normSongText(noUnclosed) != "" {
			return strings.TrimSpace(noUnclosed)
		}
		return orig
	}
	return cur
}

func extractQuotedTitles(raw string) []string {
	var out []string
	seen := map[string]bool{}
	add := func(cand string) {
		cleaned := normSongText(stripTitleDecorations(cand))
		if utf8.RuneCountInString(cleaned) < 2 || songGenericWords[cleaned] || seen[cleaned] {
			return
		}
		seen[cleaned] = true
		out = append(out, cleaned)
	}
	for _, m := range quotedJaRe.FindAllStringSubmatch(raw, -1) {
		if len(m) == 2 {
			add(m[1])
		}
	}
	for _, m := range quotedASCIIRe.FindAllStringSubmatch(raw, -1) {
		if len(m) == 2 {
			add(m[1])
		}
	}
	return out
}

func splitTitleSegments(s string) []string {
	work := strings.ReplaceAll(s, "｜", " | ")
	work = norm.NFKC.String(work)
	const delim = "\x00"
	for _, sep := range titleSepList {
		work = strings.ReplaceAll(work, sep, delim)
	}
	rawParts := strings.Split(work, delim)
	var out []string
	for _, p := range rawParts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func isMetadataSegment(seg string, rawArtists, knownNames map[string]bool) bool {
	nRaw := normSongText(seg)
	if nRaw == "" {
		return true
	}
	cleaned := stripTitleDecorations(seg)
	// Check if stripping promo phrases completely emptied the segment
	if normSongText(promoPhraseRe.ReplaceAllString(stripBrackets(norm.NFKC.String(seg)), " ")) == "" {
		return true
	}
	nClean := normSongText(cleaned)
	if rawArtists[nRaw] || rawArtists[nClean] || songGenericWords[nRaw] || songGenericWords[nClean] {
		return true
	}
	if knownNames != nil && (knownNames[nRaw] || knownNames[nClean]) {
		return true
	}
	for _, pat := range songFranchisePatterns {
		if strings.Contains(nRaw, pat) || strings.Contains(nClean, pat) {
			return true
		}
	}
	words := strings.Fields(nRaw)
	if len(words) > 0 {
		if songMetaEdgeWords[words[0]] || songMetaEdgeWords[words[len(words)-1]] {
			return true
		}
	}
	return false
}

func buildRawArtistSet(rawArtist string) map[string]bool {
	out := map[string]bool{}
	if na := normSongText(topicSuffixRe.ReplaceAllString(rawArtist, "")); na != "" && na != "unknown artist" {
		out[na] = true
	}
	for _, a := range splitArtistsGo(rawArtist) {
		if na := normSongText(a); na != "" && na != "unknown artist" {
			out[na] = true
		}
	}
	return out
}

func songTitleKeys(rawTitle, rawArtist string, knownNames map[string]bool) []string {
	var keys []string
	seen := map[string]bool{}
	addKey := func(k string) {
		k = strings.TrimSpace(k)
		if utf8.RuneCountInString(k) < 2 || songGenericWords[k] || seen[k] {
			return
		}
		seen[k] = true
		keys = append(keys, k)
	}

	// 1. Extracted quoted / 「...」 / 『...』 titles
	for _, q := range extractQuotedTitles(rawTitle) {
		addKey(q)
	}

	// 2. Segments obtained by splitting the bracket-stripped title on separators
	rawArtists := buildRawArtistSet(rawArtist)
	bracketStripped := stripBrackets(rawTitle)
	segs := splitTitleSegments(bracketStripped)
	if len(segs) >= 2 {
		var nonMeta []string
		for _, seg := range segs {
			if isMetadataSegment(seg, rawArtists, knownNames) {
				continue
			}
			k := normSongText(stripTitleDecorations(seg))
			if utf8.RuneCountInString(k) >= 2 && !songGenericWords[k] && !rawArtists[k] && (knownNames == nil || !knownNames[k]) {
				nonMeta = append(nonMeta, k)
			}
		}
		for _, k := range nonMeta {
			addKey(k)
		}
	}

	// 3. Full title cleaned
	fullKey := normSongText(stripTitleDecorations(rawTitle))
	addKey(fullKey)
	return keys
}

func tokenizeNormalizedSong(normTitle string, rawArtist string) []string {
	artistToks := map[string]bool{}
	for _, a := range splitArtistsGo(rawArtist) {
		for _, w := range strings.Fields(normSongText(a)) {
			artistToks[w] = true
		}
	}
	if na := normSongText(topicSuffixRe.ReplaceAllString(rawArtist, "")); na != "" {
		for _, w := range strings.Fields(na) {
			artistToks[w] = true
		}
	}
	var out []string
	seen := map[string]bool{}
	for _, w := range strings.Fields(normTitle) {
		if songStopWords[w] || artistToks[w] || seen[w] {
			continue
		}
		seen[w] = true
		out = append(out, w)
	}
	return out
}

func songCoreTokens(rawTitle, rawArtist string, knownNames map[string]bool) []string {
	quoted := extractQuotedTitles(rawTitle)
	if len(quoted) > 0 {
		if toks := tokenizeNormalizedSong(quoted[0], rawArtist); len(toks) > 0 {
			return toks
		}
	}
	keys := songTitleKeys(rawTitle, rawArtist, knownNames)
	best := ""
	bestToks := ([]string)(nil)
	for _, k := range keys {
		toks := tokenizeNormalizedSong(k, rawArtist)
		if len(toks) >= 2 {
			if bestToks == nil || len(k) < len(best) {
				best = k
				bestToks = toks
			}
		}
	}
	if bestToks != nil {
		return bestToks
	}
	if len(keys) > 0 {
		return tokenizeNormalizedSong(keys[0], rawArtist)
	}
	return tokenizeNormalizedSong(normSongText(stripTitleDecorations(rawTitle)), rawArtist)
}

func isDistinctiveToken(tok string) bool {
	if utf8.RuneCountInString(tok) >= 5 {
		return true
	}
	for _, r := range tok {
		if r > unicode.MaxASCII || unicode.IsDigit(r) || r == '%' {
			return true
		}
	}
	return false
}

func areSameSongTokens(a, b []string) bool {
	if len(a) < 2 || len(b) < 2 {
		return false
	}
	small, large := a, b
	if len(b) < len(a) {
		small, large = b, a
	}
	var largeSet map[string]bool
	if len(large) > 10 {
		largeSet = make(map[string]bool, len(large))
		for _, tok := range large {
			largeSet[tok] = true
		}
	}
	distinctive := false
	matched := 0
	for _, tok := range small {
		var has bool
		if largeSet != nil {
			has = largeSet[tok]
		} else {
			has = slices.Contains(large, tok)
		}
		if !has {
			return false
		}
		matched++
		if isDistinctiveToken(tok) {
			distinctive = true
		}
	}
	return distinctive || matched >= 3
}

type songSet struct {
	knownNames  map[string]bool
	vidSet      map[string]bool
	titleKeySet map[string]bool
	tokenList   [][]string
}

func newSongSet(knownNames map[string]bool) *songSet {
	if knownNames == nil {
		knownNames = map[string]bool{}
	}
	return &songSet{
		knownNames:  knownNames,
		vidSet:      map[string]bool{},
		titleKeySet: map[string]bool{},
	}
}

func containsFranchisePattern(s string) bool {
	for _, pat := range songFranchisePatterns {
		if strings.Contains(s, pat) {
			return true
		}
	}
	return false
}

func (s *songSet) addRawHistoryKey(key string) {
	switch {
	case strings.HasPrefix(key, "vid:"):
		if vid := strings.TrimSpace(strings.TrimPrefix(key, "vid:")); vid != "" {
			s.vidSet[vid] = true
		}
	case strings.HasPrefix(key, "song:"):
		sk := strings.TrimSpace(strings.TrimPrefix(key, "song:"))
		if sk != "" {
			s.titleKeySet[sk] = true
			if !containsFranchisePattern(sk) && !s.knownNames[sk] {
				if toks := tokenizeNormalizedSong(sk, ""); len(toks) >= 2 {
					s.tokenList = append(s.tokenList, toks)
				}
			}
		}
	}
}

func (s *songSet) add(vid, title, artist string) {
	if vid != "" {
		s.vidSet[vid] = true
	}
	for _, k := range songTitleKeys(title, artist, s.knownNames) {
		s.titleKeySet[k] = true
	}
	if toks := songCoreTokens(title, artist, s.knownNames); len(toks) >= 2 {
		s.tokenList = append(s.tokenList, toks)
	}
}

func (s *songSet) matches(vid, title, artist string) bool {
	if vid != "" && s.vidSet[vid] {
		return true
	}
	for _, k := range songTitleKeys(title, artist, s.knownNames) {
		if s.titleKeySet[k] {
			return true
		}
	}
	toks := songCoreTokens(title, artist, s.knownNames)
	if len(toks) >= 2 {
		for _, existing := range s.tokenList {
			if areSameSongTokens(toks, existing) {
				return true
			}
		}
	}
	return false
}

func collectKnownLibraryNames(tracks []Track, extraKnown ...string) map[string]bool {
	known := make(map[string]bool, len(tracks)*2)
	addArtist := func(raw string) {
		if na := normSongText(topicSuffixRe.ReplaceAllString(raw, "")); na != "" && na != "unknown artist" {
			known[na] = true
		}
		for _, a := range splitArtistsGo(raw) {
			if na := normSongText(a); na != "" && na != "unknown artist" {
				known[na] = true
			}
		}
	}
	for _, t := range tracks {
		addArtist(t.Artist)
		if t.Album != "" {
			if nal := normSongText(t.Album); nal != "" {
				known[nal] = true
			}
		}
	}
	for _, ex := range extraKnown {
		addArtist(ex)
	}
	return known
}

var _ = math.Abs

// ---------- yt-dlp binary management ----------

type YtdlpStatus struct {
	State   string `json:"state"` // idle | updating | ok | error
	Version string `json:"version"`
	Path    string `json:"path"`
	Message string `json:"message"`
}

type Ytdlp struct {
	mu     sync.Mutex
	status YtdlpStatus
	emit   func(string, ...any)
	jsFlag bool
}

func NewYtdlp(emit func(string, ...any)) *Ytdlp {
	y := &Ytdlp{emit: emit}
	y.status = YtdlpStatus{State: "idle"}
	return y
}

func (y *Ytdlp) ownPath() string {
	if runtime.GOOS == "windows" {
		return filepath.Join(binDir(), "yt-dlp.exe")
	}
	return filepath.Join(binDir(), "yt-dlp")
}

// Path returns the managed yt-dlp binary if present, else the system one.
func (y *Ytdlp) Path() string {
	if st, err := os.Stat(y.ownPath()); err == nil && st.Size() > 1_000_000 {
		return y.ownPath()
	}
	if p, err := exec.LookPath("yt-dlp"); err == nil {
		return p
	}
	return ""
}

func (y *Ytdlp) Status() YtdlpStatus {
	y.mu.Lock()
	defer y.mu.Unlock()
	return y.status
}

func (y *Ytdlp) set(s YtdlpStatus) {
	y.mu.Lock()
	y.status = s
	y.mu.Unlock()
	y.emit("ytdlp:status", s)
}

func (y *Ytdlp) version(path string) string {
	cmd := exec.Command(path, "--version")
	setCmdHidden(cmd)
	out, err := cmd.Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// EnsureUpToDate downloads yt-dlp if we have no managed copy, otherwise self-updates it.
func (y *Ytdlp) EnsureUpToDate() {
	own := y.ownPath()
	y.set(YtdlpStatus{State: "updating", Path: y.Path(), Message: "Checking for yt-dlp updates…"})
	_, err := os.Stat(own)
	if err != nil {
		if err := y.download(own); err != nil {
			p := y.Path()
			y.set(YtdlpStatus{State: "error", Path: p, Version: y.version(orEmpty(p)), Message: "Update failed: " + err.Error()})
			return
		}
	} else {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		cmd := exec.CommandContext(ctx, own, "-U")
		configureManagedCmd(cmd)
		out, err := cmd.CombinedOutput()
		if err != nil {
			p := y.Path()
			y.set(YtdlpStatus{State: "error", Path: p, Version: y.version(orEmpty(p)),
				Message: "Update failed: " + lastLine(string(out))})
			return
		}
	}
	p := y.Path()
	y.refreshFlags(p)
	y.set(YtdlpStatus{State: "ok", Path: p, Version: y.version(orEmpty(p)), Message: "yt-dlp is up to date"})
}

func orEmpty(p string) string {
	if p == "" {
		return "/nonexistent"
	}
	return p
}

func lastLine(s string) string {
	s = strings.TrimSpace(s)
	if i := strings.LastIndex(s, "\n"); i >= 0 {
		return strings.TrimSpace(s[i+1:])
	}
	return s
}

func (y *Ytdlp) download(dst string) error {
	url := "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"
	if runtime.GOOS == "windows" {
		url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
	}
	client := &http.Client{Timeout: 5 * time.Minute}
	resp, err := client.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	tmp := dst + ".part"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, resp.Body); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	f.Close()
	return os.Rename(tmp, dst)
}

func (y *Ytdlp) refreshFlags(path string) {
	if path == "" {
		return
	}
	cmd := exec.Command(path, "--help")
	setCmdHidden(cmd)
	out, _ := cmd.Output()
	y.mu.Lock()
	y.jsFlag = strings.Contains(string(out), "--js-runtimes")
	y.mu.Unlock()
}

// ---------- download jobs ----------

type DownloadJob struct {
	ID         int64   `json:"id"`
	URL        string  `json:"url"`
	Mode       string  `json:"mode"` // track | playlist
	Status     string  `json:"status"`
	Title      string  `json:"title"`
	Playlist   string  `json:"playlist"`
	Percent    float64 `json:"percent"`
	Speed      string  `json:"speed"`
	Index      int     `json:"index"`
	Total      int     `json:"total"`
	Done       int     `json:"done"`
	Error      string  `json:"error"`
	StartedAt  int64   `json:"startedAt"`

	ytID              string
	trackTitle        string
	artist            string
	durationSec       float64
	cancel            context.CancelFunc
	files             []string
	targetPlaylistID  int64
	targetPlaylistIDs []int64
	markAsLiked       bool
	albumOverride     string
	quiet             bool
}

type Downloader struct {
	app              *App
	mu               sync.Mutex
	jobs             []*DownloadJob
	nextID           int64
	notify           chan struct{}
	recMu            sync.Mutex
	seenRec          []string
	usedSeeds        map[string]bool
	httpClient       *http.Client
	discMu           sync.Mutex
	discCancel       context.CancelFunc
	discGen          uint64
	artistDiscMu     sync.Mutex
	artistDiscCancel context.CancelFunc
	artistDiscGen    uint64
}

func NewDownloader(app *App) *Downloader {
	d := &Downloader{
		app:        app,
		notify:     make(chan struct{}, 1),
		usedSeeds:  map[string]bool{},
		httpClient: &http.Client{Timeout: 10 * time.Second},
	}
	go d.worker()
	return d
}

// CancelDiscovery cancels any in-flight home or artist online discovery requests.
func (d *Downloader) CancelDiscovery() {
	d.discMu.Lock()
	if d.discCancel != nil {
		d.discCancel()
		d.discCancel = nil
		d.discGen++
	}
	d.discMu.Unlock()

	d.artistDiscMu.Lock()
	if d.artistDiscCancel != nil {
		d.artistDiscCancel()
		d.artistDiscCancel = nil
		d.artistDiscGen++
	}
	d.artistDiscMu.Unlock()
}

func (d *Downloader) signal() {
	select {
	case d.notify <- struct{}{}:
	default:
	}
}

func tempCookieArgs(set Settings) (args []string, cleanup func()) {
	if hasValidYtCookiesFile() {
		if b, err := os.ReadFile(ytCookiesPath()); err == nil && len(b) > 0 {
			if tmp, err := os.CreateTemp(dataDir(), "yt-cookies-*.txt"); err == nil {
				_, werr := tmp.Write(b)
				_ = tmp.Close()
				if werr == nil {
					p := tmp.Name()
					return []string{"--cookies", p}, func() { _ = os.Remove(p) }
				}
				_ = os.Remove(tmp.Name())
			}
		}
		return []string{"--cookies", ytCookiesPath()}, func() {}
	}
	if set.CookieBrowser != "" {
		return []string{"--cookies-from-browser", set.CookieBrowser}, func() {}
	}
	return nil, func() {}
}

func (d *Downloader) List() []DownloadJob {
	d.mu.Lock()
	defer d.mu.Unlock()
	out := make([]DownloadJob, 0, len(d.jobs))
	for _, j := range d.jobs {
		out = append(out, *j)
	}
	return out
}

func (d *Downloader) push(j *DownloadJob) {
	d.mu.Lock()
	cp := *j
	d.mu.Unlock()
	d.app.emit("download:update", cp)
}

func (d *Downloader) Add(url, mode string) (int64, error) {
	return d.addInternal(url, mode, 0, "", "", false, "", false)
}

func (d *Downloader) AddToPlaylist(url string, playlistID int64) (int64, error) {
	return d.addInternal(url, "track", playlistID, "", "", false, "", false)
}

func (d *Downloader) EnqueueYtMusicTrack(ytID, title string, targetPlaylistID int64, markAsLiked bool, albumOverride string) (int64, error) {
	ytID = strings.TrimSpace(ytID)
	if !ytIDRe.MatchString(ytID) {
		return 0, fmt.Errorf("invalid YouTube video id: %q", ytID)
	}
	url := "https://www.youtube.com/watch?v=" + ytID
	return d.addInternal(url, "track", targetPlaylistID, ytID, title, markAsLiked, albumOverride, true)
}

func (d *Downloader) EnqueueYtMusicTrackItem(item YtMusicTrackItem, displayTitle string, targetPlaylistID int64, markAsLiked bool, albumOverride string) (int64, error) {
	ytID := strings.TrimSpace(item.YtID)
	if !ytIDRe.MatchString(ytID) {
		return 0, fmt.Errorf("invalid YouTube video id: %q", ytID)
	}
	url := "https://www.youtube.com/watch?v=" + ytID
	return d.addInternalWithMeta(url, "track", targetPlaylistID, ytID, displayTitle, item.Title, item.Artist, item.DurationSec, markAsLiked, albumOverride, true)
}

func (d *Downloader) EnqueueYtMusicPlaylist(url, title string, targetPlaylistID int64, markAsLiked bool, albumOverride string) (int64, error) {
	return d.addInternal(url, "playlist", targetPlaylistID, "", title, markAsLiked, albumOverride, true)
}

func mergePlaylistID(list []int64, pid int64) []int64 {
	if pid <= 0 {
		return list
	}
	for _, x := range list {
		if x == pid {
			return list
		}
	}
	return append(list, pid)
}

func (d *Downloader) knownLibraryNames(extraKnown ...string) map[string]bool {
	d.app.trMu.RLock()
	tracks := make([]Track, 0, len(d.app.tracks))
	for _, t := range d.app.tracks {
		tracks = append(tracks, t)
	}
	d.app.trMu.RUnlock()
	return collectKnownLibraryNames(tracks, extraKnown...)
}

func jobsMatchSong(titleA, artistA string, durA float64, titleB, artistB string, durB float64, knownNames map[string]bool) bool {
	if artistA != "" || artistB != "" {
		item := YtMusicTrackItem{Title: titleA, Artist: artistA, DurationSec: durA}
		tr := Track{Title: titleB, Artist: artistB, Duration: durB}
		return itemMatchesTrack(item, tr, knownNames)
	}
	nA := normSongText(stripTitleDecorations(titleA))
	nB := normSongText(stripTitleDecorations(titleB))
	if nA != "" && nA == nB {
		return true
	}
	toksA := songCoreTokens(titleA, "", knownNames)
	toksB := songCoreTokens(titleB, "", knownNames)
	return areSameSongTokens(toksA, toksB)
}

func (d *Downloader) addInternal(url, mode string, targetPlaylistID int64, ytID, title string, markAsLiked bool, albumOverride string, quiet bool) (int64, error) {
	return d.addInternalWithMeta(url, mode, targetPlaylistID, ytID, title, "", "", 0, markAsLiked, albumOverride, quiet)
}

func (d *Downloader) addInternalWithMeta(url, mode string, targetPlaylistID int64, ytID, title, trackTitle, artist string, durationSec float64, markAsLiked bool, albumOverride string, quiet bool) (int64, error) {
	url = strings.TrimSpace(url)
	if !strings.HasPrefix(url, "http://") && !strings.HasPrefix(url, "https://") {
		return 0, fmt.Errorf("please paste a valid URL (starting with http/https)")
	}
	if mode != "playlist" {
		mode = "track"
	}
	if ytID == "" && mode == "track" {
		ytID = extractURLVideoID(url)
	}
	if d.app.ytdlp.Path() == "" {
		return 0, fmt.Errorf("yt-dlp not found yet – wait for the updater or install yt-dlp")
	}
	title = strings.TrimSpace(title)
	if title == "" {
		title = url
	}
	trackTitle = strings.TrimSpace(trackTitle)
	artist = strings.TrimSpace(artist)
	albumOverride = strings.TrimSpace(albumOverride)

	knownNames := d.knownLibraryNames()
	incomingSongTitle := trackTitle
	if incomingSongTitle == "" && title != url && title != ytID {
		incomingSongTitle = title
	}

	d.mu.Lock()
	if ytID != "" || mode == "track" {
		for _, existing := range d.jobs {
			if existing.Status == "cancelled" || existing.Status == "error" {
				continue
			}
			var liveFiles []string
			if existing.Status == "done" {
				for _, f := range existing.files {
					if fileExists(f) {
						liveFiles = append(liveFiles, f)
					}
				}
				if len(liveFiles) == 0 {
					continue
				}
			} else if existing.Status != "queued" && existing.Status != "running" {
				continue
			}

			matched := (ytID != "" && existing.ytID == ytID) || existing.URL == url
			if !matched && mode == "track" && existing.Mode == "track" && incomingSongTitle != "" {
				exTitle := existing.trackTitle
				if exTitle == "" && existing.Title != existing.URL && existing.Title != existing.ytID {
					exTitle = existing.Title
				}
				if exTitle != "" && jobsMatchSong(incomingSongTitle, artist, durationSec, exTitle, existing.artist, existing.durationSec, knownNames) {
					matched = true
				}
			}

			if matched {
				if targetPlaylistID > 0 {
					if existing.targetPlaylistID == 0 {
						existing.targetPlaylistID = targetPlaylistID
					}
					existing.targetPlaylistIDs = mergePlaylistID(existing.targetPlaylistIDs, targetPlaylistID)
				}
				if markAsLiked {
					existing.markAsLiked = true
				}
				if existing.albumOverride == "" && albumOverride != "" {
					existing.albumOverride = albumOverride
				}
				if existing.Status == "done" {
					d.mu.Unlock()
					d.applyJobPostActions(existing, liveFiles)
					d.push(existing)
					return existing.ID, nil
				}
				d.mu.Unlock()
				d.push(existing)
				return existing.ID, nil
			}
		}
	}
	d.nextID++
	var plIDs []int64
	if targetPlaylistID > 0 {
		plIDs = []int64{targetPlaylistID}
	}
	j := &DownloadJob{
		ID:                d.nextID,
		URL:               url,
		ytID:              ytID,
		trackTitle:        trackTitle,
		artist:            artist,
		durationSec:       durationSec,
		Mode:              mode,
		Status:            "queued",
		Title:             title,
		StartedAt:         time.Now().Unix(),
		targetPlaylistID:  targetPlaylistID,
		targetPlaylistIDs: plIDs,
		markAsLiked:       markAsLiked,
		albumOverride:     albumOverride,
		quiet:             quiet,
	}
	d.jobs = append(d.jobs, j)
	d.mu.Unlock()
	d.push(j)
	d.signal()
	return j.ID, nil
}

func (d *Downloader) Cancel(id int64) {
	d.mu.Lock()
	var j *DownloadJob
	for _, x := range d.jobs {
		if x.ID == id {
			j = x
		}
	}
	if j == nil {
		d.mu.Unlock()
		return
	}
	if j.Status == "queued" {
		j.Status = "cancelled"
	} else if j.Status == "running" && j.cancel != nil {
		j.cancel()
	}
	d.mu.Unlock()
	d.push(j)
}

func (d *Downloader) ClearFinished() {
	d.mu.Lock()
	var keep []*DownloadJob
	for _, j := range d.jobs {
		if j.Status == "queued" || j.Status == "running" {
			keep = append(keep, j)
		}
	}
	d.jobs = keep
	d.mu.Unlock()
	d.app.emit("download:cleared")
}

func (d *Downloader) nextQueuedJob() *DownloadJob {
	d.mu.Lock()
	defer d.mu.Unlock()
	for _, j := range d.jobs {
		if j.Status == "queued" {
			return j
		}
	}
	return nil
}

func (d *Downloader) worker() {
	for range d.notify {
		for {
			j := d.nextQueuedJob()
			if j == nil {
				break
			}
			d.mu.Lock()
			if j.Status != "queued" {
				d.mu.Unlock()
				continue
			}
			j.Status = "running"
			ctx, cancel := context.WithCancel(context.Background())
			j.cancel = cancel
			d.mu.Unlock()
			d.push(j)
			d.run(ctx, j)
			cancel()
		}
	}
}

func formatArgs(format string) []string {
	switch format {
	case "opus":
		return []string{"-f", "bestaudio", "-x", "--audio-format", "opus"}
	case "mp3":
		return []string{"-f", "bestaudio", "-x", "--audio-format", "mp3", "--audio-quality", "0"}
	case "best":
		return []string{"-f", "bestaudio/best", "-x", "--audio-format", "best"}
	default: // m4a
		return []string{"-f", "bestaudio[ext=m4a]/bestaudio", "-x", "--audio-format", "m4a"}
	}
}

func (d *Downloader) applyJobPostActions(j *DownloadJob, files []string) (hasTargets bool, markLiked bool) {
	if len(files) == 0 {
		return false, false
	}
	d.mu.Lock()
	targetIDs := append([]int64(nil), j.targetPlaylistIDs...)
	targetIDs = mergePlaylistID(targetIDs, j.targetPlaylistID)
	markLiked = j.markAsLiked || strings.Contains(j.URL, "list=LM")
	albumOverride := j.albumOverride
	d.mu.Unlock()

	if markLiked {
		d.app.likeFiles(files)
	}
	if albumOverride != "" {
		changedAlbum := false
		for _, f := range files {
			if t, ok := d.app.store.TrackByPath(f); ok {
				_ = d.app.store.UpdateTrackAlbum(t.ID, albumOverride)
				changedAlbum = true
			}
		}
		if changedAlbum {
			d.app.reloadTracks()
			d.app.emit("library:changed")
		}
	}
	if len(targetIDs) > 0 {
		var ids []string
		for _, f := range files {
			if t, ok := d.app.store.TrackByPath(f); ok {
				ids = append(ids, t.ID)
			}
		}
		if len(ids) > 0 {
			for _, pid := range targetIDs {
				_, _ = d.app.store.AddToPlaylist(pid, ids)
			}
			d.app.emit("playlists:changed")
		}
		hasTargets = true
	}
	return hasTargets, markLiked
}

func (d *Downloader) run(ctx context.Context, j *DownloadJob) {
	set := d.app.Settings()
	_ = os.MkdirAll(set.DownloadDir, 0o755)
	path := d.app.ytdlp.Path()

	existingByID := d.existingVideoIDMap()

	// Fast path for single-track downloads if the video ID or song is already in the library
	if j.Mode == "track" {
		vid := extractURLVideoID(j.URL)
		if vid == "" {
			vid = j.ytID
		}
		var existingPath string
		if vid != "" {
			existingPath = existingByID[vid]
		}
		if existingPath == "" {
			d.mu.Lock()
			jTitle := j.trackTitle
			if jTitle == "" && j.Title != j.URL && j.Title != j.ytID {
				jTitle = j.Title
			}
			jArtist := j.artist
			jDur := j.durationSec
			d.mu.Unlock()
			if jTitle != "" {
				d.app.trMu.RLock()
				tracks := make([]Track, 0, len(d.app.tracks))
				for _, t := range d.app.tracks {
					tracks = append(tracks, t)
				}
				d.app.trMu.RUnlock()
				knownNames := collectKnownLibraryNames(tracks)
				if existing, ok := findExistingTrackForYtmItem(YtMusicTrackItem{
					YtID:        vid,
					Title:       jTitle,
					Artist:      jArtist,
					DurationSec: jDur,
				}, tracks, knownNames); ok && existing.Path != "" && fileExists(existing.Path) {
					existingPath = existing.Path
				}
			}
		}
		if existingPath != "" {
			d.mu.Lock()
			j.Title = strings.TrimSuffix(filepath.Base(existingPath), filepath.Ext(existingPath))
			j.Done = 1
			j.files = []string{existingPath}
			d.mu.Unlock()
			d.applyJobPostActions(j, []string{existingPath})
			d.finish(j, "done", "Already in your library — skipped download")
			return
		}
	}

	// Build temporary archive file from existing local tracks so yt-dlp skips redownloading them
	archiveFile := filepath.Join(dataDir(), fmt.Sprintf("dl-archive-%d.txt", j.ID))
	var archBuf strings.Builder
	for vid := range existingByID {
		archBuf.WriteString("youtube ")
		archBuf.WriteString(vid)
		archBuf.WriteByte('\n')
		archBuf.WriteString("youtubetab ")
		archBuf.WriteString(vid)
		archBuf.WriteByte('\n')
	}
	_ = os.WriteFile(archiveFile, []byte(archBuf.String()), 0o644)
	defer os.Remove(archiveFile)

	args := []string{"--newline", "--no-colors", "--ignore-errors", "--no-overwrites",
		"--download-archive", archiveFile,
		"--embed-metadata", "--embed-thumbnail",
		// tag every download with comment=yt:<id> so re-scans on any OS recover the same canonical key
		"--parse-metadata", `yt\:%(id)s:%(meta_comment)s`,
		"--convert-thumbnails", "jpg", "--no-warnings"}
	if j.Mode == "playlist" {
		args = append(args, "--yes-playlist")
	} else {
		args = append(args, "--no-playlist")
	}
	cArgs, cookieCleanup := tempCookieArgs(set)
	defer cookieCleanup()
	args = append(args, cArgs...)
	args = append(args, formatArgs(set.DownloadFormat)...)
	if _, err := exec.LookPath("node"); err == nil {
		d.app.ytdlp.mu.Lock()
		ok := d.app.ytdlp.jsFlag
		d.app.ytdlp.mu.Unlock()
		if ok {
			args = append(args, "--js-runtimes", "node")
		}
	}
	args = append(args,
		"-o", filepath.Join(set.DownloadDir, "%(title)s [%(id)s].%(ext)s"),
		"--progress-template", "download:VMPROG|%(progress._percent_str)s|%(progress._speed_str)s|%(info.playlist_index)s|%(info.n_entries)s|%(info.title)s",
		"--print", "before_dl:VMPL|%(playlist_title)s",
		"--print", "after_move:VMFILE|%(filepath)s|%(playlist_title)s",
		j.URL)

	cmd := exec.CommandContext(ctx, path, args...)
	configureManagedCmd(cmd)
	stdout, _ := cmd.StdoutPipe()
	stderr, _ := cmd.StderrPipe()
	if err := cmd.Start(); err != nil {
		d.finish(j, "error", err.Error())
		return
	}
	var errLines []string
	var emu sync.Mutex
	go func() {
		sc := bufio.NewScanner(stderr)
		sc.Buffer(make([]byte, 1<<20), 1<<20)
		for sc.Scan() {
			emu.Lock()
			errLines = append(errLines, sc.Text())
			emu.Unlock()
		}
	}()

	sc := bufio.NewScanner(stdout)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	var plName string
	skippedExisting := 0
	seenFiles := map[string]bool{}
	addFile := func(fp string) {
		if fp == "" || seenFiles[fp] {
			return
		}
		seenFiles[fp] = true
		j.files = append(j.files, fp)
	}

	lastEmit := time.Time{}
	for sc.Scan() {
		line := sc.Text()
		switch {
		case strings.HasPrefix(line, "VMPL|"):
			name := strings.TrimSpace(strings.TrimPrefix(line, "VMPL|"))
			if name != "" && name != "NA" {
				plName = name
			}
		case strings.HasPrefix(line, "VMPROG|"):
			parts := strings.SplitN(line, "|", 6)
			if len(parts) < 6 {
				continue
			}
			pct, _ := strconv.ParseFloat(strings.TrimSuffix(strings.TrimSpace(parts[1]), "%"), 64)
			idx, _ := strconv.Atoi(strings.TrimSpace(parts[3]))
			tot, _ := strconv.Atoi(strings.TrimSpace(parts[4]))
			d.mu.Lock()
			j.Percent = pct
			j.Speed = strings.TrimSpace(parts[2])
			j.Index, j.Total = idx, tot
			j.Title = strings.TrimSpace(parts[5])
			d.mu.Unlock()
			if time.Since(lastEmit) > 200*time.Millisecond {
				lastEmit = time.Now()
				d.push(j)
			}
		case strings.HasPrefix(line, "VMFILE|"):
			parts := strings.SplitN(line, "|", 3)
			if len(parts) < 2 {
				continue
			}
			d.mu.Lock()
			addFile(parts[1])
			j.Done++
			j.Percent = 0
			if len(parts) == 3 && parts[2] != "NA" && parts[2] != "" {
				plName = parts[2]
			}
			d.mu.Unlock()
			d.push(j)
		default:
			if m := archiveSkipRe.FindStringSubmatch(line); len(m) == 2 {
				if existingPath, ok := existingByID[m[1]]; ok {
					d.mu.Lock()
					addFile(existingPath)
					skippedExisting++
					j.Title = strings.TrimSuffix(filepath.Base(existingPath), filepath.Ext(existingPath))
					d.mu.Unlock()
					d.push(j)
				}
			} else if m := alreadyDlRe.FindStringSubmatch(line); len(m) == 2 {
				fp := strings.TrimSpace(m[1])
				if _, err := os.Stat(fp); err == nil {
					d.mu.Lock()
					addFile(fp)
					skippedExisting++
					d.mu.Unlock()
					d.push(j)
				}
			}
		}
	}
	err := cmd.Wait()
	emu.Lock()
	errText := lastLine(strings.Join(errLines, "\n"))
	emu.Unlock()

	if ctx.Err() != nil {
		d.finish(j, "cancelled", "")
		return
	}
	d.mu.Lock()
	got := len(j.files)
	files := append([]string(nil), j.files...)
	d.mu.Unlock()
	if got == 0 {
		if errText == "" && err != nil {
			errText = err.Error()
		}
		if errText == "" {
			errText = "nothing was downloaded (already exists?)"
		}
		d.finish(j, "error", errText)
		return
	}

	// Index the files and refresh library, then group into a playlist when requested
	// Only the downloaded files are indexed (no full library walk), so they show up instantly.
	d.app.ingestFiles(files)
	hasTargets, markLiked := d.applyJobPostActions(j, files)
	if j.Mode == "playlist" && !hasTargets && !markLiked {
		if plName == "" {
			if strings.Contains(j.URL, "list=LM") {
				plName = "YouTube Liked Music"
			} else {
				plName = "Downloaded Playlist"
			}
		}
		d.app.addFilesToPlaylist(plName, files)
		d.mu.Lock()
		j.Playlist = plName
		d.mu.Unlock()
	}
	msg := ""
	if skippedExisting > 0 && j.Done == 0 {
		msg = fmt.Sprintf("All %d track(s) already in library — skipped redownload", skippedExisting)
	} else if skippedExisting > 0 {
		msg = fmt.Sprintf("Skipped %d already-downloaded track(s)", skippedExisting)
	} else if err != nil && errText != "" {
		msg = "Finished with some errors: " + errText
	}
	d.finish(j, "done", msg)
}

func (d *Downloader) finish(j *DownloadJob, status, msg string) {
	d.mu.Lock()
	j.Status = status
	j.Error = msg
	if status == "done" {
		j.Percent = 100
	}
	done := j.Done
	totalFiles := len(j.files)
	quiet := j.quiet
	d.mu.Unlock()
	d.push(j)
	if status == "done" && !quiet {
		if done == 0 && totalFiles > 0 {
			d.app.emit("app:toast", fmt.Sprintf("Skipped %d existing track(s) (already in library)", totalFiles))
		} else {
			d.app.emit("app:toast", fmt.Sprintf("Downloaded %d track(s)", done))
		}
	}
}

// ---------- Online Discovery (YouTube Music Radio via yt-dlp) ----------

type DiscoverItem struct {
	VideoID  string  `json:"videoId"`
	Title    string  `json:"title"`
	Artist   string  `json:"artist"`
	Duration float64 `json:"duration"`
	ThumbURL string  `json:"thumbUrl"`
	URL      string  `json:"url"`
}

type ytFlatThumb struct {
	URL    string `json:"url"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
}

type ytFlatEntry struct {
	ID         string        `json:"id"`
	Title      string        `json:"title"`
	Uploader   string        `json:"uploader"`
	Channel    string        `json:"channel"`
	Duration   float64       `json:"duration"`
	Thumbnails []ytFlatThumb `json:"thumbnails"`
}

type ytFlatPlaylist struct {
	Entries []ytFlatEntry `json:"entries"`
}

var googleThumbParamRe = regexp.MustCompile(`=(?:w\d+-h\d+|s\d+)[^/?#]*$`)

func upgradeGoogleThumbURL(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	if googleThumbParamRe.MatchString(raw) {
		return googleThumbParamRe.ReplaceAllString(raw, "=w600-h600-l90-rj")
	}
	if idx := strings.LastIndex(raw, "="); idx > strings.LastIndex(raw, "/") {
		return raw[:idx] + "=w600-h600-l90-rj"
	}
	return raw + "=w600-h600-l90-rj"
}

func bestDiscoverThumbURL(videoID string, thumbs []ytFlatThumb) string {
	var bestLarge string
	bestW := 0
	for _, th := range thumbs {
		u := strings.TrimSpace(th.URL)
		if u == "" {
			continue
		}
		if strings.Contains(u, "googleusercontent.com") || strings.Contains(u, "yt3.ggpht.com") {
			return upgradeGoogleThumbURL(u)
		}
		if th.Width >= 640 && th.Width > bestW {
			bestW = th.Width
			bestLarge = u
		}
	}
	if bestLarge != "" {
		return bestLarge
	}
	return fmt.Sprintf("https://i.ytimg.com/vi/%s/maxresdefault.jpg", videoID)
}

var nonMusicTitleKeywords = []string{
	"beginner's guide", "guide to", "how to", "writes a", "music theory",
	"analysis", "analyzing", "explained", "explanation", "tutorial", "lesson",
	"reaction", "reacts", "review", "interview", "documentary", "tier list",
	"ranking", "ranked", "top 10", "top 5", "top 20", "history of", "making of",
	"behind the scenes", "breakdown", "video essay", "essay", "podcast", "lecture",
	"livestream", "gameplay", "walkthrough", "playthrough", "let's play", "speedrun",
	"trailer", "teaser", "unboxing", "compilation", "full album", "full ost",
	"full soundtrack", "1 hour", "2 hours", "3 hours", "10 hours", "voicing",
	"voiceleading", "chord progression", "sheet music", "piano tutorial",
	"guitar tab", "synthesia", "play along", "backing track", "critique",
	"discussion", "retrospective", "the story of", "what happened to",
	"biography", "deep dive", "vlog", "q&a",
}

var nonMusicChannelKeywords = []string{
	"music theory", "explained", "lessons", "tutorials", "reacts",
	"reviews", "podcast", "gaming", "ign", "gamespot", "synthesia",
	"piano tutorial", "guitar tab",
}

func isNonMusicEntry(title, channel string, duration float64) bool {
	if duration > 0 && (duration < 40 || duration > 540) {
		return true
	}
	tl := strings.ToLower(title)
	if strings.HasPrefix(tl, "how ") || strings.HasPrefix(tl, "why ") || strings.Contains(tl, "|| how ") || strings.Contains(tl, "| how ") {
		return true
	}
	for _, kw := range nonMusicTitleKeywords {
		if strings.Contains(tl, kw) {
			return true
		}
	}
	cl := strings.ToLower(channel)
	for _, kw := range nonMusicChannelKeywords {
		if strings.Contains(cl, kw) {
			return true
		}
	}
	return false
}

func (d *Downloader) buildDiscoveryMatcherWithHistory(includeHistory bool, extraKnown ...string) *songSet {
	d.app.trMu.RLock()
	tracks := make([]Track, 0, len(d.app.tracks))
	for _, t := range d.app.tracks {
		tracks = append(tracks, t)
	}
	d.app.trMu.RUnlock()

	knownNames := collectKnownLibraryNames(tracks, extraKnown...)
	matcher := newSongSet(knownNames)

	for _, t := range tracks {
		matcher.add(trackVideoID(t), t.Title, t.Artist)
	}

	d.mu.Lock()
	for _, j := range d.jobs {
		if j.Status == "cancelled" {
			continue
		}
		vid := j.ytID
		if vid == "" {
			vid = extractURLVideoID(j.URL)
		}
		jTitle := j.trackTitle
		if jTitle == "" && j.Title != j.URL && j.Title != j.ytID {
			jTitle = j.Title
		}
		matcher.add(vid, jTitle, j.artist)
	}
	d.mu.Unlock()

	if includeHistory {
		if d.app.store != nil {
			for _, k := range d.app.store.LoadRecommendedHistory() {
				matcher.addRawHistoryKey(k)
			}
		} else {
			d.recMu.Lock()
			for _, k := range d.seenRec {
				matcher.addRawHistoryKey(k)
			}
			d.recMu.Unlock()
		}
	}

	return matcher
}

func (d *Downloader) buildDiscoveryMatcher(extraKnown ...string) *songSet {
	return d.buildDiscoveryMatcherWithHistory(true, extraKnown...)
}

func (d *Downloader) buildOwnedDiscoveryMatcher(extraKnown ...string) *songSet {
	return d.buildDiscoveryMatcherWithHistory(false, extraKnown...)
}

func (d *Downloader) recordRecommended(items []DiscoverItem, knownNames map[string]bool) {
	if len(items) == 0 {
		return
	}
	var batch []string
	batchSeen := map[string]bool{}
	for _, it := range items {
		if it.VideoID != "" {
			vk := "vid:" + it.VideoID
			if !batchSeen[vk] {
				batchSeen[vk] = true
				batch = append(batch, vk)
			}
		}
		for _, tk := range songTitleKeys(it.Title, it.Artist, knownNames) {
			sk := "song:" + tk
			if !batchSeen[sk] {
				batchSeen[sk] = true
				batch = append(batch, sk)
			}
		}
	}
	if len(batch) == 0 {
		return
	}
	d.recMu.Lock()
	existing := make(map[string]bool, len(d.seenRec)+len(batch))
	for _, k := range d.seenRec {
		existing[k] = true
	}
	for _, k := range batch {
		if !existing[k] {
			existing[k] = true
			d.seenRec = append(d.seenRec, k)
		}
	}
	d.seenRec = trimRecommendedHistory(d.seenRec, maxRecommendedHistorySongs)
	d.recMu.Unlock()
	if d.app.store != nil {
		_ = d.app.store.AddRecommendedHistory(batch)
	}
}

func filterFlatPlaylistEntries(pl ytFlatPlaylist, matcher *songSet, shuffle bool, rngSeed int64) []DiscoverItem {
	entries := append([]ytFlatEntry(nil), pl.Entries...)
	if shuffle {
		localRng := rand.New(rand.NewSource(rngSeed))
		localRng.Shuffle(len(entries), func(a, b int) {
			entries[a], entries[b] = entries[b], entries[a]
		})
	}
	var items []DiscoverItem
	for _, e := range entries {
		if len(e.ID) != 11 {
			continue
		}
		title := strings.TrimSpace(e.Title)
		if title == "" || title == "[Deleted video]" || title == "[Private video]" {
			continue
		}
		rawChannel := strings.TrimSpace(e.Channel)
		if rawChannel == "" {
			rawChannel = strings.TrimSpace(e.Uploader)
		}
		if isNonMusicEntry(title, rawChannel, e.Duration) {
			continue
		}
		artist := strings.TrimSpace(topicSuffixRe.ReplaceAllString(rawChannel, ""))
		if matcher.matches(e.ID, title, artist) {
			continue
		}
		items = append(items, DiscoverItem{
			VideoID:  e.ID,
			Title:    title,
			Artist:   artist,
			Duration: e.Duration,
			ThumbURL: bestDiscoverThumbURL(e.ID, e.Thumbnails),
			URL:      fmt.Sprintf("https://www.youtube.com/watch?v=%s", e.ID),
		})
	}
	return items
}

func parseDurationText(raw string) float64 {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0
	}
	parts := strings.Split(raw, ":")
	if len(parts) < 2 || len(parts) > 3 {
		return 0
	}
	total := 0
	for i, part := range parts {
		n, err := strconv.Atoi(strings.TrimSpace(part))
		if err != nil || n < 0 {
			return 0
		}
		if i > 0 && n >= 60 {
			return 0
		}
		total = total*60 + n
	}
	return float64(total)
}

func extractInnerTubeThumbnails(renderer map[string]any) []ytFlatThumb {
	var thumbs []ytFlatThumb
	for _, tr := range asSlice(getPath(renderer, "thumbnail", "thumbnails")) {
		tm := asMap(tr)
		if tm == nil {
			continue
		}
		u := getStr(tm, "url")
		if u == "" {
			continue
		}
		if strings.HasPrefix(u, "//") {
			u = "https:" + u
		}
		w, _ := tm["width"].(float64)
		h, _ := tm["height"].(float64)
		thumbs = append(thumbs, ytFlatThumb{URL: u, Width: int(w), Height: int(h)})
	}
	return thumbs
}

func firstNonEmptyRunsText(m map[string]any, keys ...string) string {
	for _, k := range keys {
		if s := strings.TrimSpace(extractRunsText(asMap(m[k]))); s != "" {
			return s
		}
	}
	return ""
}

func cleanInnerTubeArtist(raw string) string {
	s := strings.TrimSpace(raw)
	for _, sep := range []string{" • ", " · "} {
		if idx := strings.Index(s, sep); idx >= 0 {
			s = strings.TrimSpace(s[:idx])
		}
	}
	return s
}

func extractInnerTubeRendererEntry(renderer map[string]any, channelKeys ...string) (ytFlatEntry, bool) {
	if renderer == nil || renderer["unplayableText"] != nil {
		return ytFlatEntry{}, false
	}
	vid := getStr(renderer, "videoId")
	if len(vid) != 11 {
		return ytFlatEntry{}, false
	}
	title := strings.TrimSpace(extractRunsText(asMap(renderer["title"])))
	if title == "" {
		return ytFlatEntry{}, false
	}
	channel := cleanInnerTubeArtist(firstNonEmptyRunsText(renderer, channelKeys...))
	dur := parseDurationText(extractRunsText(asMap(renderer["lengthText"])))
	return ytFlatEntry{
		ID:         vid,
		Title:      title,
		Channel:    channel,
		Duration:   dur,
		Thumbnails: extractInnerTubeThumbnails(renderer),
	}, true
}

func parseInnerTubeNextEntries(root map[string]any, limit int) []ytFlatEntry {
	if root == nil {
		return nil
	}
	if limit <= 0 {
		limit = 40
	}
	var entries []ytFlatEntry
	seen := map[string]bool{}
	var walk func(node any)
	walk = func(node any) {
		if len(entries) >= limit {
			return
		}
		switch v := node.(type) {
		case map[string]any:
			if vr := asMap(v["playlistPanelVideoRenderer"]); vr != nil {
				if e, ok := extractInnerTubeRendererEntry(vr, "shortBylineText", "longBylineText"); ok && !seen[e.ID] {
					seen[e.ID] = true
					entries = append(entries, e)
				}
				return
			}
			if wr := asMap(v["playlistPanelVideoWrapperRenderer"]); wr != nil {
				if pr := asMap(wr["primaryRenderer"]); pr != nil {
					walk(pr)
					return
				}
			}
			for _, child := range v {
				walk(child)
				if len(entries) >= limit {
					return
				}
			}
		case []any:
			for _, child := range v {
				walk(child)
				if len(entries) >= limit {
					return
				}
			}
		}
	}
	walk(root)
	return entries
}

func innerTubeNextContainsSeed(root map[string]any, entries []ytFlatEntry, seedVideoID string) bool {
	if len(entries) < 2 || seedVideoID == "" {
		return false
	}
	for _, e := range entries {
		if e.ID == seedVideoID {
			return true
		}
	}
	if root == nil {
		return false
	}
	found := false
	var walk func(node any)
	walk = func(node any) {
		if found {
			return
		}
		switch v := node.(type) {
		case map[string]any:
			if ppr := asMap(v["playlistPanelRenderer"]); ppr != nil {
				if pid := getStr(ppr, "playlistId"); pid != "" && strings.Contains(pid, seedVideoID) {
					found = true
					return
				}
			}
			if wr := asMap(v["playlistPanelVideoWrapperRenderer"]); wr != nil {
				for _, cp := range asSlice(wr["counterpart"]) {
					cm := asMap(cp)
					if cr := asMap(cm["counterpartRenderer"]); cr != nil {
						if vr := asMap(cr["playlistPanelVideoRenderer"]); vr != nil && getStr(vr, "videoId") == seedVideoID {
							found = true
							return
						}
					}
				}
			}
			for _, child := range v {
				walk(child)
				if found {
					return
				}
			}
		case []any:
			for _, child := range v {
				walk(child)
				if found {
					return
				}
			}
		}
	}
	walk(root)
	return found
}

func parseInnerTubeSearchEntries(root map[string]any, limit int) []ytFlatEntry {
	if root == nil {
		return nil
	}
	if limit <= 0 {
		limit = 40
	}
	var entries []ytFlatEntry
	seen := map[string]bool{}
	var walk func(node any)
	walk = func(node any) {
		if len(entries) >= limit {
			return
		}
		switch v := node.(type) {
		case map[string]any:
			if vr := asMap(v["videoRenderer"]); vr != nil {
				if vr["unplayableText"] != nil {
					return
				}
				vid := getStr(vr, "videoId")
				if len(vid) != 11 || seen[vid] {
					return
				}
				title := strings.TrimSpace(extractRunsText(asMap(vr["title"])))
				if title == "" {
					return
				}
				channel := cleanInnerTubeArtist(firstNonEmptyRunsText(vr, "ownerText", "longBylineText", "shortBylineText"))
				dur := parseDurationText(extractRunsText(asMap(vr["lengthText"])))
				seen[vid] = true
				entries = append(entries, ytFlatEntry{
					ID:         vid,
					Title:      title,
					Channel:    channel,
					Duration:   dur,
					Thumbnails: extractInnerTubeThumbnails(vr),
				})
				return
			}
			for _, child := range v {
				walk(child)
				if len(entries) >= limit {
					return
				}
			}
		case []any:
			for _, child := range v {
				walk(child)
				if len(entries) >= limit {
					return
				}
			}
		}
	}
	walk(root)
	return entries
}

func postInnerTubeJSON(ctx context.Context, client *http.Client, endpoint string, payload map[string]any, origin, clientID, clientVer string) (map[string]any, error) {
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second}
	}
	b, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(b))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Origin", origin)
	req.Header.Set("Referer", origin+"/")
	req.Header.Set("X-Origin", origin)
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
	req.Header.Set("X-Youtube-Client-Name", clientID)
	req.Header.Set("X-Youtube-Client-Version", clientVer)
	if cookieHeader := readSavedCookieHeader(); cookieHeader != "" {
		req.Header.Set("Cookie", cookieHeader)
		if auth := (&App{}).BuildSapisidHashHeader(origin); auth != "" {
			req.Header.Set("Authorization", auth)
			req.Header.Set("X-Goog-AuthUser", "0")
		}
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		io.Copy(io.Discard, resp.Body)
		return nil, fmt.Errorf("youtube innertube HTTP %d", resp.StatusCode)
	}
	var root map[string]any
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(&root); err != nil {
		return nil, err
	}
	return root, nil
}

func postYouTubeInnerTube(ctx context.Context, client *http.Client, endpoint string, payload map[string]any) (map[string]any, error) {
	return postInnerTubeJSON(ctx, client, endpoint, payload, "https://www.youtube.com", "1", "2.20251001.01.00")
}

func postYouTubeMusicInnerTube(ctx context.Context, client *http.Client, endpoint string, payload map[string]any) (map[string]any, error) {
	return postInnerTubeJSON(ctx, client, endpoint, payload, "https://music.youtube.com", "67", "1.20241118.01.00")
}

type ytRadioSource int

const (
	ytRadioSourceYTM ytRadioSource = iota + 1
	ytRadioSourceYT
	ytRadioSourceSearch
)

func countNonSeedEntries(entries []ytFlatEntry, seedVideoID string) int {
	n := 0
	for _, e := range entries {
		if e.ID != "" && e.ID != seedVideoID {
			n++
		}
	}
	return n
}

func fetchYouTubeRadioWithSource(ctx context.Context, client *http.Client, videoID string) ([]ytFlatEntry, ytRadioSource, error) {
	videoID = strings.TrimSpace(videoID)
	if len(videoID) != 11 {
		return nil, 0, fmt.Errorf("invalid video id")
	}

	ytmPayload := map[string]any{
		"context": map[string]any{
			"client": map[string]any{
				"clientName":    "WEB_REMIX",
				"clientVersion": "1.20241118.01.00",
				"hl":            "en",
			},
		},
		"videoId":                       videoID,
		"playlistId":                    "RDAMVM" + videoID,
		"isAudioOnly":                   true,
		"enablePersistentPlaylistPanel": true,
		"tunerSettingValue":             "AUTOMIX_SETTING_NORMAL",
	}
	var lastErr error
	if root, err := postYouTubeMusicInnerTube(ctx, client, "https://music.youtube.com/youtubei/v1/next?prettyPrint=false", ytmPayload); err == nil {
		entries := parseInnerTubeNextEntries(root, 25)
		if innerTubeNextContainsSeed(root, entries, videoID) && countNonSeedEntries(entries, videoID) >= 3 {
			return entries, ytRadioSourceYTM, nil
		}
	} else {
		lastErr = err
	}

	payload := map[string]any{
		"context": map[string]any{
			"client": map[string]any{
				"clientName":    "WEB",
				"clientVersion": "2.20251001.01.00",
				"hl":            "en",
			},
		},
		"videoId":    videoID,
		"playlistId": "RD" + videoID,
	}
	if root, err := postYouTubeInnerTube(ctx, client, "https://www.youtube.com/youtubei/v1/next?prettyPrint=false", payload); err == nil {
		entries := parseInnerTubeNextEntries(root, 20)
		if innerTubeNextContainsSeed(root, entries, videoID) && countNonSeedEntries(entries, videoID) >= 3 {
			return entries, ytRadioSourceYT, nil
		}
		return nil, 0, nil
	} else if lastErr == nil {
		lastErr = err
	}
	return nil, 0, lastErr
}

func fetchYouTubeRadioInnerTube(ctx context.Context, client *http.Client, videoID string) ([]ytFlatEntry, error) {
	entries, _, err := fetchYouTubeRadioWithSource(ctx, client, videoID)
	return entries, err
}

func fetchYouTubeSearchInnerTube(ctx context.Context, client *http.Client, query string, limit int) ([]ytFlatEntry, error) {
	query = strings.TrimSpace(ytSearchPrefixRe.ReplaceAllString(strings.TrimSpace(query), ""))
	if query == "" {
		return nil, fmt.Errorf("empty search query")
	}
	payload := map[string]any{
		"context": map[string]any{
			"client": map[string]any{
				"clientName":    "WEB",
				"clientVersion": "2.20251001.01.00",
				"hl":            "en",
				"gl":            "US",
			},
		},
		"query": query,
	}
	root, err := postYouTubeInnerTube(ctx, client, "https://www.youtube.com/youtubei/v1/search?prettyPrint=false", payload)
	if err != nil {
		return nil, err
	}
	return parseInnerTubeSearchEntries(root, limit), nil
}

type discoverySeedInfo struct {
	vid         string
	artist      string
	artists     []string
	artistSet   map[string]bool
	artistWords []string
	tokens      []string
	score       float64
}

type discoveryLibraryProfile struct {
	artists     map[string]bool
	genreWeight map[string]float64
	genreTotal  float64
	artistGenre func(artistKey string) []string
}

type radioFetchOutcome struct {
	seed          discoverySeedInfo
	source        ytRadioSource
	entries       []ytFlatEntry
	coherent      bool
	seedArtistHits int
	libArtistHits int
	tokenHits     int
	anchorHits    int
}

func isGenericDiscoveryArtist(normArt string) bool {
	switch normArt {
	case "", "unknown", "unknown artist", "various artists", "youtube", "release", "auto generated by youtube", "auto-generated by youtube":
		return true
	}
	return false
}

func normFoldWords(s string) string {
	s = norm.NFKD.String(strings.ToLower(s))
	var b strings.Builder
	b.Grow(len(s))
	lastSpace := true
	for _, r := range s {
		if unicode.Is(unicode.Mn, r) {
			continue
		}
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r) || r == '%':
			b.WriteRune(r)
			lastSpace = false
		case r == '\'' || r == '’' || r == '‘':
		default:
			if !lastSpace {
				b.WriteByte(' ')
				lastSpace = true
			}
		}
	}
	return strings.TrimSpace(b.String())
}

func normDiscoveryArtists(raw string) []string {
	cleaned := strings.TrimSpace(topicSuffixRe.ReplaceAllString(cleanInnerTubeArtist(raw), ""))
	if cleaned == "" {
		return nil
	}
	var out []string
	seen := map[string]bool{}
	add := func(cand string) {
		k := normName(cand)
		if isGenericDiscoveryArtist(k) || seen[k] {
			return
		}
		seen[k] = true
		out = append(out, k)
	}
	for _, a := range splitArtistsGo(cleaned) {
		add(a)
	}
	if len(out) == 0 {
		add(cleaned)
	}
	return out
}

func extractDistinctiveTokens(title, album string, artistNorms []string) []string {
	artistToks := map[string]bool{}
	for _, an := range artistNorms {
		for _, w := range strings.Fields(normFoldWords(an)) {
			artistToks[w] = true
		}
	}
	cleaned := strings.TrimSpace(stripTitleDecorations(title) + " " + stripTitleDecorations(album))
	words := strings.Fields(normFoldWords(cleaned))
	var out []string
	seen := map[string]bool{}
	for _, w := range words {
		if seen[w] || songStopWords[w] || songGenericWords[w] || artistToks[w] {
			continue
		}
		hasNonASCII := false
		for _, r := range w {
			if r > unicode.MaxASCII {
				hasNonASCII = true
				break
			}
		}
		if utf8.RuneCountInString(w) >= 4 || (hasNonASCII && utf8.RuneCountInString(w) >= 2) {
			seen[w] = true
			out = append(out, w)
		}
	}
	return out
}

func entryMatchSignals(seed discoverySeedInfo, title, rawChannel string, lib discoveryLibraryProfile) (matchesSeedArtist, matchesLibArtist, matchesSeedToken bool, entryArtists []string) {
	entryArtists = normDiscoveryArtists(rawChannel)
	for _, ea := range entryArtists {
		if seed.artistSet[ea] {
			matchesSeedArtist = true
		} else if lib.artists[ea] {
			matchesLibArtist = true
		}
	}
	titleFold := normFoldWords(title)
	if !matchesSeedArtist && titleFold != "" && len(seed.artistWords) > 0 {
		padded := " " + titleFold + " "
		for _, saw := range seed.artistWords {
			if utf8.RuneCountInString(saw) >= 3 && strings.Contains(padded, " "+saw+" ") {
				matchesSeedArtist = true
				break
			}
		}
	}
	if !matchesSeedToken && len(seed.tokens) > 0 {
		haystack := " " + titleFold + " " + normFoldWords(rawChannel) + " "
		for _, tok := range seed.tokens {
			if strings.Contains(haystack, " "+tok+" ") {
				matchesSeedToken = true
				break
			}
		}
	}
	return
}

func evaluateRadioMixCoherence(seed discoverySeedInfo, entries []ytFlatEntry, source ytRadioSource, lib discoveryLibraryProfile) (coherent bool, seedArtistHits, libArtistHits, tokenHits, anchorHits int) {
	if source == ytRadioSourceSearch {
		return len(entries) > 0, 0, 0, 0, 0
	}
	inspected := 0
	for _, e := range entries {
		if e.ID == "" || e.ID == seed.vid {
			continue
		}
		title := strings.TrimSpace(e.Title)
		if title == "" || title == "[Deleted video]" || title == "[Private video]" {
			continue
		}
		rawChannel := strings.TrimSpace(e.Channel)
		if rawChannel == "" {
			rawChannel = strings.TrimSpace(e.Uploader)
		}
		if isNonMusicEntry(title, rawChannel, e.Duration) {
			continue
		}
		msa, mla, mst, _ := entryMatchSignals(seed, title, rawChannel, lib)
		if msa {
			seedArtistHits++
		}
		if mla {
			libArtistHits++
		}
		if mst {
			tokenHits++
		}
		if msa || mla || mst {
			anchorHits++
		}
		inspected++
		if inspected >= 14 {
			break
		}
	}
	switch source {
	case ytRadioSourceYTM:
		coherent = anchorHits >= 1
	case ytRadioSourceYT:
		coherent = seedArtistHits >= 1 || libArtistHits >= 2 || (libArtistHits >= 1 && tokenHits >= 1)
	}
	return
}

func discoverySourceMaxPos(source ytRadioSource) int {
	switch source {
	case ytRadioSourceYTM:
		return 16
	case ytRadioSourceYT:
		return 12
	case ytRadioSourceSearch:
		return 20
	default:
		return 12
	}
}

func rankDiscoveryOutcomes(
	outcomes []radioFetchOutcome,
	matcher *songSet,
	lib discoveryLibraryProfile,
	rngSeed int64,
	target int,
	artistCount map[string]int,
) []DiscoverItem {
	if target <= 0 || len(outcomes) == 0 {
		return []DiscoverItem{}
	}
	if artistCount == nil {
		artistCount = map[string]int{}
	}

	trackSeedCount := map[string]int{}
	artistSeedCount := map[string]int{}
	for _, o := range outcomes {
		if !o.coherent {
			continue
		}
		maxPos := discoverySourceMaxPos(o.source)
		seenVidInSeed := map[string]bool{}
		seenArtInSeed := map[string]bool{}
		pos := 0
		for _, e := range o.entries {
			if e.ID == "" || e.ID == o.seed.vid || len(e.ID) != 11 {
				continue
			}
			if pos >= maxPos {
				break
			}
			pos++
			if !seenVidInSeed[e.ID] {
				seenVidInSeed[e.ID] = true
				trackSeedCount[e.ID]++
			}
			rawChannel := strings.TrimSpace(e.Channel)
			if rawChannel == "" {
				rawChannel = strings.TrimSpace(e.Uploader)
			}
			for _, ea := range normDiscoveryArtists(rawChannel) {
				if !seenArtInSeed[ea] {
					seenArtInSeed[ea] = true
					artistSeedCount[ea]++
				}
			}
		}
	}

	type scoredCandidate struct {
		item  DiscoverItem
		score float64
	}
	type rankedBucket struct {
		sourceRank int
		orderTie   float64
		items      []scoredCandidate
	}

	localRng := rand.New(rand.NewSource(rngSeed))
	var buckets []rankedBucket
	for _, o := range outcomes {
		if !o.coherent {
			continue
		}
		maxPos := discoverySourceMaxPos(o.source)
		var cands []scoredCandidate
		pos := 0
		for _, e := range o.entries {
			if len(e.ID) != 11 || e.ID == o.seed.vid {
				continue
			}
			if pos >= maxPos {
				break
			}
			curPos := pos
			pos++

			title := strings.TrimSpace(e.Title)
			if title == "" || title == "[Deleted video]" || title == "[Private video]" {
				continue
			}
			rawChannel := strings.TrimSpace(e.Channel)
			if rawChannel == "" {
				rawChannel = strings.TrimSpace(e.Uploader)
			}
			if isNonMusicEntry(title, rawChannel, e.Duration) {
				continue
			}
			artist := strings.TrimSpace(topicSuffixRe.ReplaceAllString(cleanInnerTubeArtist(rawChannel), ""))
			if matcher.matches(e.ID, title, artist) {
				continue
			}

			msa, mla, mst, entryArtists := entryMatchSignals(o.seed, title, rawChannel, lib)
			primaryArt := ""
			if len(entryArtists) > 0 {
				primaryArt = entryArtists[0]
			}
			crossSeed := trackSeedCount[e.ID]
			if primaryArt != "" && artistSeedCount[primaryArt] > crossSeed {
				crossSeed = artistSeedCount[primaryArt]
			}
			corroborated := msa || mla || mst || crossSeed >= 2

			// Anti-drift gate: block uncorroborated tail/generic entries
			switch o.source {
			case ytRadioSourceSearch:
				if !msa && !mla {
					continue
				}
			case ytRadioSourceYT:
				if !corroborated && !(o.seedArtistHits >= 2 && curPos < 4) {
					continue
				}
			case ytRadioSourceYTM:
				if !corroborated && curPos >= 8 {
					continue
				}
			}

			// Zero-network genre clash guard using cached artist_genres and title/artist heuristics
			genreBonus := 0.0
			if lib.genreTotal > 0 && !msa && !mla {
				var candGenres []string
				if lib.artistGenre != nil && primaryArt != "" {
					candGenres = append(candGenres, lib.artistGenre(primaryArt)...)
				}
				candGenres = append(candGenres, heuristicGenres(Track{Title: title, Artist: artist})...)
				candGenres = dedupeGenres(candGenres, 5)
				if len(candGenres) > 0 {
					simW := 0.0
					clashW := 0.0
					for lg, lw := range lib.genreWeight {
						simW += lw * genreSimGo(candGenres, []string{lg})
						clashW += lw * genreClashGo(candGenres, []string{lg})
					}
					sim := math.Min(1.0, simW/lib.genreTotal)
					clash := math.Min(1.0, clashW/lib.genreTotal)
					if clash >= 0.55 && sim < 0.25 {
						continue
					}
					genreBonus = sim*18.0 - clash*20.0
				}
			}

			var base float64
			switch o.source {
			case ytRadioSourceYTM:
				base = 36.0 - float64(curPos)*2.4
			case ytRadioSourceYT:
				base = 18.0 - float64(curPos)*3.5
			default:
				base = 24.0 - float64(curPos)*1.0
			}
			cohBonus := math.Min(float64(o.anchorHits), 6.0) * 4.0
			anchorBonus := 0.0
			if msa {
				anchorBonus += 18.0
			}
			if mla {
				anchorBonus += 16.0
			}
			if mst {
				anchorBonus += 14.0
			}
			crossBonus := 0.0
			if crossSeed >= 2 {
				crossBonus = math.Min(float64(crossSeed-1)*24.0, 48.0)
			}

			sc := base + cohBonus + anchorBonus + crossBonus + genreBonus + localRng.Float64()*14.0
			cands = append(cands, scoredCandidate{
				item: DiscoverItem{
					VideoID:  e.ID,
					Title:    title,
					Artist:   artist,
					Duration: e.Duration,
					ThumbURL: bestDiscoverThumbURL(e.ID, e.Thumbnails),
					URL:      fmt.Sprintf("https://www.youtube.com/watch?v=%s", e.ID),
				},
				score: sc,
			})
		}
		if len(cands) == 0 {
			continue
		}
		sort.SliceStable(cands, func(i, j int) bool { return cands[i].score > cands[j].score })
		buckets = append(buckets, rankedBucket{
			sourceRank: int(o.source),
			orderTie:   localRng.Float64(),
			items:      cands,
		})
	}
	if len(buckets) == 0 {
		return []DiscoverItem{}
	}
	sort.SliceStable(buckets, func(i, j int) bool {
		if buckets[i].sourceRank != buckets[j].sourceRank {
			return buckets[i].sourceRank < buckets[j].sourceRank
		}
		return buckets[i].orderTie < buckets[j].orderTie
	})

	maxLen := 0
	for _, b := range buckets {
		if len(b.items) > maxLen {
			maxLen = len(b.items)
		}
	}

	out := make([]DiscoverItem, 0, target)
	for round := 0; round < maxLen && len(out) < target; round++ {
		for bi := 0; bi < len(buckets) && len(out) < target; bi++ {
			if round >= len(buckets[bi].items) {
				continue
			}
			it := buckets[bi].items[round].item
			if matcher.matches(it.VideoID, it.Title, it.Artist) {
				continue
			}
			aKey := normSongText(it.Artist)
			if aKey != "" && artistCount[aKey] >= 2 {
				continue
			}
			matcher.add(it.VideoID, it.Title, it.Artist)
			if aKey != "" {
				artistCount[aKey]++
			}
			out = append(out, it)
		}
	}
	return out
}

func (d *Downloader) DiscoverOnline(seed int) []DiscoverItem {
	discCtx, discCancel := context.WithTimeout(context.Background(), 25*time.Second)
	d.discMu.Lock()
	if d.discCancel != nil {
		d.discCancel()
	}
	d.discGen++
	myGen := d.discGen
	d.discCancel = discCancel
	d.discMu.Unlock()
	defer func() {
		discCancel()
		d.discMu.Lock()
		if d.discGen == myGen {
			d.discCancel = nil
		}
		d.discMu.Unlock()
	}()

	matcher := d.buildDiscoveryMatcher()

	byArtist := map[string][]discoverySeedInfo{}
	artistAff := map[string]float64{}
	libArtists := map[string]bool{}
	genreWeight := map[string]float64{}
	genreTotal := 0.0
	totalVids := 0

	d.app.trMu.RLock()
	for _, t := range d.app.tracks {
		artists := splitArtistsGo(t.Artist)
		var normArts []string
		var wordArts []string
		artSet := map[string]bool{}
		primaryDisplay := ""
		primaryKey := "unknown"
		for _, a := range artists {
			nk := normName(a)
			if isGenericDiscoveryArtist(nk) {
				continue
			}
			if primaryDisplay == "" {
				primaryDisplay = strings.TrimSpace(topicSuffixRe.ReplaceAllString(a, ""))
				primaryKey = nk
			}
			if !artSet[nk] {
				artSet[nk] = true
				normArts = append(normArts, nk)
				libArtists[nk] = true
				if fw := normFoldWords(a); fw != "" {
					wordArts = append(wordArts, fw)
				}
			}
		}

		tScore := float64(t.PlayCount*10) + 1
		if t.Liked {
			tScore += 25
		}
		if gs := splitGenres(t.Genre); len(gs) > 0 {
			gw := 1.0 + float64(t.PlayCount)*0.5 + func() float64 {
				if t.Liked {
					return 2.0
				}
				return 0.0
			}()
			for _, g := range gs {
				genreWeight[g] += gw
				genreTotal += gw
			}
		}

		if vid := trackVideoID(t); vid != "" {
			byArtist[primaryKey] = append(byArtist[primaryKey], discoverySeedInfo{
				vid:         vid,
				artist:      primaryDisplay,
				artists:     normArts,
				artistSet:   artSet,
				artistWords: wordArts,
				tokens:      extractDistinctiveTokens(t.Title, t.Album, normArts),
				score:       tScore,
			})
			artistAff[primaryKey] += tScore
			totalVids++
		}
	}
	d.app.trMu.RUnlock()

	if len(byArtist) == 0 {
		return []DiscoverItem{}
	}

	// Skip "unknown" / generic artist bucket when real artists exist
	hasNamedArtists := false
	for k := range byArtist {
		if !isGenericDiscoveryArtist(k) {
			hasNamedArtists = true
			break
		}
	}

	rng := rand.New(rand.NewSource(time.Now().UnixNano() + int64(seed)*99991))
	artistKeys := make([]string, 0, len(byArtist))
	artistRank := make(map[string]float64, len(byArtist))
	for k := range byArtist {
		if hasNamedArtists && isGenericDiscoveryArtist(k) {
			continue
		}
		list := byArtist[k]
		// Prioritize listened/liked tracks within each artist with light random exploration
		for i := range list {
			list[i].score += rng.Float64() * 20
		}
		sort.SliceStable(list, func(i, j int) bool { return list[i].score > list[j].score })
		byArtist[k] = list
		artistRank[k] = math.Min(artistAff[k], 250) + rng.Float64()*45
		artistKeys = append(artistKeys, k)
	}
	sort.SliceStable(artistKeys, func(i, j int) bool {
		return artistRank[artistKeys[i]] > artistRank[artistKeys[j]]
	})

	const targetSeeds = 8
	var chosen []discoverySeedInfo
	seenSeed := map[string]bool{}

	d.recMu.Lock()
	if d.usedSeeds == nil {
		d.usedSeeds = map[string]bool{}
	}
	hasUnused := false
	for _, ak := range artistKeys {
		for _, c := range byArtist[ak] {
			if !d.usedSeeds[c.vid] {
				hasUnused = true
				break
			}
		}
		if hasUnused {
			break
		}
	}
	if !hasUnused && totalVids > 0 {
		d.usedSeeds = map[string]bool{}
	}

	// Pick up to targetSeeds unvisited tracks across distinct artists first
	for _, ak := range artistKeys {
		for _, c := range byArtist[ak] {
			if !d.usedSeeds[c.vid] && !seenSeed[c.vid] {
				seenSeed[c.vid] = true
				chosen = append(chosen, c)
				break
			}
		}
		if len(chosen) >= targetSeeds {
			break
		}
	}
	// Top up with remaining unvisited seeds across all artists
	if len(chosen) < targetSeeds {
		for _, ak := range artistKeys {
			for _, c := range byArtist[ak] {
				if !d.usedSeeds[c.vid] && !seenSeed[c.vid] {
					seenSeed[c.vid] = true
					chosen = append(chosen, c)
					if len(chosen) >= targetSeeds {
						break
					}
				}
			}
			if len(chosen) >= targetSeeds {
				break
			}
		}
	}
	// If library has fewer unvisited seeds remaining than targetSeeds, top up with distinct artists/tracks
	if len(chosen) < targetSeeds {
		for _, ak := range artistKeys {
			for _, c := range byArtist[ak] {
				if !seenSeed[c.vid] {
					seenSeed[c.vid] = true
					chosen = append(chosen, c)
					break
				}
			}
			if len(chosen) >= targetSeeds {
				break
			}
		}
	}
	if len(chosen) < targetSeeds {
		for _, ak := range artistKeys {
			for _, c := range byArtist[ak] {
				if !seenSeed[c.vid] {
					seenSeed[c.vid] = true
					chosen = append(chosen, c)
					if len(chosen) >= targetSeeds {
						break
					}
				}
			}
			if len(chosen) >= targetSeeds {
				break
			}
		}
	}
	for _, c := range chosen {
		d.usedSeeds[c.vid] = true
	}
	d.recMu.Unlock()

	var genreCacheMu sync.Mutex
	artistGenreCache := map[string][]string{}
	libProfile := discoveryLibraryProfile{
		artists:     libArtists,
		genreWeight: genreWeight,
		genreTotal:  genreTotal,
		artistGenre: func(artistKey string) []string {
			if d.app.store == nil || artistKey == "" {
				return nil
			}
			genreCacheMu.Lock()
			cached, ok := artistGenreCache[artistKey]
			genreCacheMu.Unlock()
			if ok {
				return cached
			}
			gs, _, found := d.app.store.artistGenres(artistKey)
			if !found {
				gs = nil
			}
			genreCacheMu.Lock()
			artistGenreCache[artistKey] = gs
			genreCacheMu.Unlock()
			return gs
		},
	}

	outcomes := make([]radioFetchOutcome, len(chosen))

	// Pure-Go YouTube Music / YouTube InnerTube HTTP path (zero subprocesses, bounded concurrency).
	httpSem := make(chan struct{}, 3)
	var httpWg sync.WaitGroup
	baseSeed := time.Now().UnixNano()
	for idx, seedInfo := range chosen {
		httpWg.Add(1)
		go func(i int, s discoverySeedInfo) {
			defer httpWg.Done()
			select {
			case httpSem <- struct{}{}:
			case <-discCtx.Done():
				return
			}
			defer func() { <-httpSem }()
			ctx, cancel := context.WithTimeout(discCtx, 8*time.Second)
			entries, src, err := fetchYouTubeRadioWithSource(ctx, d.httpClient, s.vid)
			cancel()

			if err == nil && len(entries) > 0 {
				coh, sah, lah, th, ah := evaluateRadioMixCoherence(s, entries, src, libProfile)
				if coh {
					outcomes[i] = radioFetchOutcome{
						seed:           s,
						source:         src,
						entries:        entries,
						coherent:       true,
						seedArtistHits: sah,
						libArtistHits:  lah,
						tokenHits:      th,
						anchorHits:     ah,
					}
					return
				}
			}

			// Fallback to artist search when radio mix failed or drifted into generic pop filler
			if s.artist != "" && discCtx.Err() == nil {
				ctx2, cancel2 := context.WithTimeout(discCtx, 8*time.Second)
				sEntries, sErr := fetchYouTubeSearchInnerTube(ctx2, d.httpClient, s.artist+" official audio", 20)
				cancel2()
				if sErr == nil && len(sEntries) > 0 {
					outcomes[i] = radioFetchOutcome{
						seed:     s,
						source:   ytRadioSourceSearch,
						entries:  sEntries,
						coherent: true,
					}
				}
			}
		}(idx, seedInfo)
	}
	httpWg.Wait()

	if errors.Is(discCtx.Err(), context.Canceled) {
		return []DiscoverItem{}
	}

	artistCount := map[string]int{}
	out := rankDiscoveryOutcomes(outcomes, matcher, libProfile, baseSeed, 16, artistCount)

	// If recommendation history filtered most candidates, top up using non-owned tracks from the same coherent outcomes.
	if len(out) < 16 {
		ownedMatcher := d.buildOwnedDiscoveryMatcher()
		for _, it := range out {
			ownedMatcher.add(it.VideoID, it.Title, it.Artist)
		}
		topUp := rankDiscoveryOutcomes(outcomes, ownedMatcher, libProfile, baseSeed+17, 16-len(out), artistCount)
		out = append(out, topUp...)
	}

	d.recordRecommended(out, matcher.knownNames)
	if discCtx.Err() == nil && len(out) > 0 {
		thumbCtx, thumbCancel := context.WithTimeout(discCtx, 4*time.Second)
		prewarmYtThumbs(thumbCtx, out, 8)
		thumbCancel()
	}
	return out
}

func (d *Downloader) DiscoverForArtist(artistName string, seed int) []DiscoverItem {
	artistName = strings.TrimSpace(artistName)
	if artistName == "" {
		return []DiscoverItem{}
	}
	artistCtx, artistCancel := context.WithTimeout(context.Background(), 25*time.Second)
	d.artistDiscMu.Lock()
	if d.artistDiscCancel != nil {
		d.artistDiscCancel()
	}
	d.artistDiscGen++
	myGen := d.artistDiscGen
	d.artistDiscCancel = artistCancel
	d.artistDiscMu.Unlock()
	defer func() {
		artistCancel()
		d.artistDiscMu.Lock()
		if d.artistDiscGen == myGen {
			d.artistDiscCancel = nil
		}
		d.artistDiscMu.Unlock()
	}()

	matcher := d.buildDiscoveryMatcher(artistName)
	targetNorm := strings.ToLower(artistName)

	var artistVids []string
	d.app.trMu.RLock()
	for _, t := range d.app.tracks {
		if vid := trackVideoID(t); vid != "" {
			for _, a := range splitArtistsGo(t.Artist) {
				if strings.ToLower(strings.TrimSpace(a)) == targetNorm {
					artistVids = append(artistVids, vid)
					break
				}
			}
		}
	}
	d.app.trMu.RUnlock()

	rng := rand.New(rand.NewSource(time.Now().UnixNano() + int64(seed)*7919))

	// Build queries targeting this artist's music so we get plenty of songs across albums/OSTs
	searchQueries := []string{
		fmt.Sprintf("ytsearch40:%s music", artistName),
		fmt.Sprintf("ytsearch40:%s", artistName),
		fmt.Sprintf("ytsearch40:%s soundtrack", artistName),
		fmt.Sprintf("ytsearch40:%s ost", artistName),
	}
	qIdx := ((seed % len(searchQueries)) + len(searchQueries)) % len(searchQueries)
	primarySearch := searchQueries[qIdx]
	fallbackSearch := searchQueries[(qIdx+1)%len(searchQueries)]

	radioVid := ""
	if len(artistVids) > 0 {
		rng.Shuffle(len(artistVids), func(i, j int) { artistVids[i], artistVids[j] = artistVids[j], artistVids[i] })
		radioVid = artistVids[0]
	}

	sourceCount := 1
	if radioVid != "" {
		sourceCount = 2
	}
	perSource := make([][]DiscoverItem, sourceCount)
	var wg sync.WaitGroup

	wg.Add(1)
	go func() {
		defer wg.Done()
		ctx, cancel := context.WithTimeout(artistCtx, 8*time.Second)
		entries, err := fetchYouTubeSearchInnerTube(ctx, d.httpClient, primarySearch, 40)
		cancel()
		if (err != nil || len(entries) == 0) && artistCtx.Err() == nil {
			ctx2, cancel2 := context.WithTimeout(artistCtx, 8*time.Second)
			entries, err = fetchYouTubeSearchInnerTube(ctx2, d.httpClient, fallbackSearch, 40)
			cancel2()
		}
		if err == nil && len(entries) > 0 {
			perSource[0] = filterFlatPlaylistEntries(ytFlatPlaylist{Entries: entries}, matcher, seed > 0, time.Now().UnixNano()+int64(seed)*997)
		}
	}()

	if radioVid != "" {
		wg.Add(1)
		go func(v string) {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(artistCtx, 8*time.Second)
			entries, err := fetchYouTubeRadioInnerTube(ctx, d.httpClient, v)
			cancel()
			if err == nil && len(entries) > 0 {
				perSource[1] = filterFlatPlaylistEntries(ytFlatPlaylist{Entries: entries}, matcher, seed > 0, time.Now().UnixNano()+3571+int64(seed)*997)
			}
		}(radioVid)
	}
	wg.Wait()

	if errors.Is(artistCtx.Err(), context.Canceled) {
		return []DiscoverItem{}
	}

	var directMatches []DiscoverItem
	var relatedMatches []DiscoverItem

	for s := 0; s < len(perSource); s++ {
		for _, it := range perSource[s] {
			if it.VideoID == "" || matcher.matches(it.VideoID, it.Title, it.Artist) {
				continue
			}
			tKey := strings.ToLower(it.Title)
			aKey := strings.ToLower(it.Artist)
			if strings.Contains(aKey, targetNorm) || strings.Contains(tKey, targetNorm) {
				directMatches = append(directMatches, it)
			} else {
				relatedMatches = append(relatedMatches, it)
			}
		}
	}

	out := make([]DiscoverItem, 0, 16)
	for _, it := range directMatches {
		if matcher.matches(it.VideoID, it.Title, it.Artist) {
			continue
		}
		matcher.add(it.VideoID, it.Title, it.Artist)
		out = append(out, it)
		if len(out) >= 16 {
			break
		}
	}
	if len(out) < 16 {
		for _, it := range relatedMatches {
			if matcher.matches(it.VideoID, it.Title, it.Artist) {
				continue
			}
			matcher.add(it.VideoID, it.Title, it.Artist)
			out = append(out, it)
			if len(out) >= 16 {
				break
			}
		}
	}
	d.recordRecommended(out, matcher.knownNames)
	if artistCtx.Err() == nil && len(out) > 0 {
		thumbCtx, thumbCancel := context.WithTimeout(artistCtx, 3*time.Second)
		prewarmYtThumbs(thumbCtx, out, 8)
		thumbCancel()
	}
	return out
}
