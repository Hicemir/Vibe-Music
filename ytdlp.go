package main

import (
	"bufio"
	"context"
	"encoding/json"
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
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"
)

var (
	fileVidIDRe    = regexp.MustCompile(`\[([A-Za-z0-9_-]{11})\][^/\\]*$`)
	archiveSkipRe  = regexp.MustCompile(`\[download\]\s+([A-Za-z0-9_-]{11}):\s+has already been recorded in the archive`)
	alreadyDlRe    = regexp.MustCompile(`\[download\]\s+(.+?)\s+has already been downloaded`)
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
	largeSet := make(map[string]bool, len(large))
	for _, tok := range large {
		largeSet[tok] = true
	}
	distinctive := false
	matched := 0
	for _, tok := range small {
		if !largeSet[tok] {
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
		setCmdHidden(cmd)
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
	app       *App
	mu        sync.Mutex
	jobs      []*DownloadJob
	nextID    int64
	notify    chan struct{}
	recMu     sync.Mutex
	seenRec   map[string]bool
	usedSeeds map[string]bool
}

func NewDownloader(app *App) *Downloader {
	d := &Downloader{
		app:       app,
		notify:    make(chan struct{}, 1),
		seenRec:   map[string]bool{},
		usedSeeds: map[string]bool{},
	}
	go d.worker()
	return d
}

func (d *Downloader) signal() {
	select {
	case d.notify <- struct{}{}:
	default:
	}
}

func cookieArgs(set Settings) []string {
	if hasValidYtCookiesFile() {
		return []string{"--cookies", ytCookiesPath()}
	}
	if set.CookieBrowser != "" {
		return []string{"--cookies-from-browser", set.CookieBrowser}
	}
	return nil
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
	args = append(args, cookieArgs(set)...)
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
	setCmdHidden(cmd)
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
	d.app.scanner.Scan(d.app.scanDirs())
	d.app.reloadTracks()
	d.app.emit("library:changed")
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

type ytFlatPlaylist struct {
	Entries []struct {
		ID       string  `json:"id"`
		Title    string  `json:"title"`
		Uploader string  `json:"uploader"`
		Channel  string  `json:"channel"`
		Duration float64 `json:"duration"`
	} `json:"entries"`
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

func (d *Downloader) buildDiscoveryMatcher(extraKnown ...string) *songSet {
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

	if d.app.store != nil {
		for _, k := range d.app.store.LoadRecommendedHistory() {
			matcher.addRawHistoryKey(k)
		}
	}
	d.recMu.Lock()
	for k := range d.seenRec {
		matcher.addRawHistoryKey(k)
	}
	d.recMu.Unlock()

	return matcher
}

func (d *Downloader) recordRecommended(items []DiscoverItem, knownNames map[string]bool) {
	if len(items) == 0 {
		return
	}
	var keys []string
	d.recMu.Lock()
	if d.seenRec == nil {
		d.seenRec = map[string]bool{}
	}
	for _, it := range items {
		if it.VideoID != "" {
			vk := "vid:" + it.VideoID
			if !d.seenRec[vk] {
				d.seenRec[vk] = true
				keys = append(keys, vk)
			}
		}
		for _, tk := range songTitleKeys(it.Title, it.Artist, knownNames) {
			sk := "song:" + tk
			if !d.seenRec[sk] {
				d.seenRec[sk] = true
				keys = append(keys, sk)
			}
		}
	}
	d.recMu.Unlock()
	if d.app.store != nil && len(keys) > 0 {
		_ = d.app.store.AddRecommendedHistory(keys)
	}
}

func filterFlatPlaylistEntries(pl ytFlatPlaylist, matcher *songSet, shuffle bool, rngSeed int64) []DiscoverItem {
	entries := append([]struct {
		ID       string  `json:"id"`
		Title    string  `json:"title"`
		Uploader string  `json:"uploader"`
		Channel  string  `json:"channel"`
		Duration float64 `json:"duration"`
	}(nil), pl.Entries...)
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
			ThumbURL: fmt.Sprintf("https://i.ytimg.com/vi/%s/hqdefault.jpg", e.ID),
			URL:      fmt.Sprintf("https://www.youtube.com/watch?v=%s", e.ID),
		})
	}
	return items
}

func (d *Downloader) DiscoverOnline(seed int) []DiscoverItem {
	path := d.app.ytdlp.Path()
	if path == "" {
		return []DiscoverItem{}
	}

	matcher := d.buildDiscoveryMatcher()

	type seedCandidate struct {
		vid    string
		artist string
	}
	byArtist := map[string][]seedCandidate{}
	totalVids := 0
	d.app.trMu.RLock()
	for _, t := range d.app.tracks {
		if vid := trackVideoID(t); vid != "" {
			artists := splitArtistsGo(t.Artist)
			primary := "unknown"
			if len(artists) > 0 {
				primary = strings.ToLower(strings.TrimSpace(artists[0]))
			}
			byArtist[primary] = append(byArtist[primary], seedCandidate{vid: vid, artist: primary})
			totalVids++
		}
	}
	d.app.trMu.RUnlock()

	if len(byArtist) == 0 {
		return []DiscoverItem{}
	}

	rng := rand.New(rand.NewSource(time.Now().UnixNano() + int64(seed)*99991))
	artistKeys := make([]string, 0, len(byArtist))
	for k := range byArtist {
		list := byArtist[k]
		rng.Shuffle(len(list), func(i, j int) { list[i], list[j] = list[j], list[i] })
		artistKeys = append(artistKeys, k)
	}
	rng.Shuffle(len(artistKeys), func(i, j int) {
		artistKeys[i], artistKeys[j] = artistKeys[j], artistKeys[i]
	})

	const targetSeeds = 6
	var chosen []string
	seenSeed := map[string]bool{}

	d.recMu.Lock()
	if d.usedSeeds == nil {
		d.usedSeeds = map[string]bool{}
	}
	hasUnused := false
	for _, list := range byArtist {
		for _, c := range list {
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
				chosen = append(chosen, c.vid)
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
					chosen = append(chosen, c.vid)
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
					chosen = append(chosen, c.vid)
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
					chosen = append(chosen, c.vid)
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
	for _, vid := range chosen {
		d.usedSeeds[vid] = true
	}
	d.recMu.Unlock()

	set := d.app.Settings()
	perSeed := make([][]DiscoverItem, len(chosen))
	var wg sync.WaitGroup

	for idx, vid := range chosen {
		wg.Add(1)
		go func(i int, v string) {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 18*time.Second)
			defer cancel()
			mixURL := fmt.Sprintf("https://www.youtube.com/watch?v=%s&list=RD%s", v, v)
			args := []string{"--flat-playlist", "-J", "--playlist-end", "40", "--no-warnings"}
			args = append(args, cookieArgs(set)...)
			args = append(args, mixURL)
			cmd := exec.CommandContext(ctx, path, args...)
			setCmdHidden(cmd)
			raw, err := cmd.Output()
			if err != nil || len(raw) == 0 {
				return
			}
			var pl ytFlatPlaylist
			if json.Unmarshal(raw, &pl) != nil || len(pl.Entries) == 0 {
				return
			}
			perSeed[i] = filterFlatPlaylistEntries(pl, matcher, true, time.Now().UnixNano()+int64(i)*7919)
		}(idx, vid)
	}
	wg.Wait()

	maxLen := 0
	for _, list := range perSeed {
		if len(list) > maxLen {
			maxLen = len(list)
		}
	}

	// Interleave results across all seeds while enforcing strict song distinctness and capping at 2 tracks per channel/artist
	artistCount := map[string]int{}
	var out []DiscoverItem

	for round := 0; round < maxLen && len(out) < 16; round++ {
		for s := 0; s < len(perSeed) && len(out) < 16; s++ {
			if round >= len(perSeed[s]) {
				continue
			}
			it := perSeed[s][round]
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
	d.recordRecommended(out, matcher.knownNames)
	return out
}

func (d *Downloader) DiscoverForArtist(artistName string, seed int) []DiscoverItem {
	path := d.app.ytdlp.Path()
	if path == "" {
		return []DiscoverItem{}
	}
	artistName = strings.TrimSpace(artistName)
	if artistName == "" {
		return []DiscoverItem{}
	}
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

	set := d.app.Settings()
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

	var sources []string
	sources = append(sources, primarySearch)
	if len(artistVids) > 0 {
		rng.Shuffle(len(artistVids), func(i, j int) { artistVids[i], artistVids[j] = artistVids[j], artistVids[i] })
		vid := artistVids[0]
		sources = append(sources, fmt.Sprintf("https://www.youtube.com/watch?v=%s&list=RD%s", vid, vid))
	}

	perSource := make([][]DiscoverItem, len(sources))
	var wg sync.WaitGroup

	for idx, src := range sources {
		wg.Add(1)
		go func(i int, target string) {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 18*time.Second)
			defer cancel()
			args := []string{"--flat-playlist", "-J", "--playlist-end", "40", "--no-warnings"}
			args = append(args, cookieArgs(set)...)
			args = append(args, target)
			cmd := exec.CommandContext(ctx, path, args...)
			setCmdHidden(cmd)
			raw, err := cmd.Output()
			if err != nil || len(raw) == 0 {
				return
			}
			var pl ytFlatPlaylist
			if json.Unmarshal(raw, &pl) != nil || len(pl.Entries) == 0 {
				return
			}
			perSource[i] = filterFlatPlaylistEntries(pl, matcher, seed > 0, time.Now().UnixNano()+int64(i)*3571+int64(seed)*997)
		}(idx, src)
	}
	wg.Wait()

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
	return out
}
