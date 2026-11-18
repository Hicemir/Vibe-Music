package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

type YtMusicCollection struct {
	ID             string `json:"id"`
	BrowseID       string `json:"browseId"`
	PlaylistURL    string `json:"playlistUrl"`
	Title          string `json:"title"`
	Subtitle       string `json:"subtitle"`
	TrackCountHint int    `json:"trackCountHint"`
	ThumbnailURL   string `json:"thumbnailUrl"`
	Type           string `json:"type"` // "LIKED_MUSIC" | "PLAYLIST" | "ALBUM"
}

type YtMusicTrackItem struct {
	YtID        string  `json:"ytId"`
	Title       string  `json:"title"`
	Artist      string  `json:"artist"`
	Album       string  `json:"album"`
	DurationSec float64 `json:"durationSec"`
}

type YtMusicStatus struct {
	LoggedIn bool   `json:"loggedIn"`
	Syncing  bool   `json:"syncing"`
	Message  string `json:"message"`
}

type YtMusicLibraryResponse struct {
	Collections []YtMusicCollection `json:"collections"`
	SyncedIDs   []string            `json:"syncedIds"`
	Error       string              `json:"error"`
}

var ytmAuthCookieNames = map[string]bool{
	"SAPISID":           true,
	"__Secure-3PAPISID": true,
	"__Secure-1PAPISID": true,
}

func ytCookiesPath() string {
	return filepath.Join(dataDir(), "yt_cookies.txt")
}

func ytCookieHeaderPath() string {
	return filepath.Join(dataDir(), "yt_cookie_header.txt")
}

func ytmWebviewDir() string {
	return filepath.Join(dataDir(), "ytm_webview")
}

func ytmWebviewCookiePath() string {
	return filepath.Join(ytmWebviewDir(), "cookies.txt")
}

func hasAuthCookieMap(cookies map[string]string) bool {
	for name := range ytmAuthCookieNames {
		if strings.TrimSpace(cookies[name]) != "" {
			return true
		}
	}
	return false
}

func parseCookieHeaderString(raw string) map[string]string {
	out := map[string]string{}
	for _, part := range strings.Split(raw, ";") {
		part = strings.TrimSpace(part)
		k, v, ok := strings.Cut(part, "=")
		if !ok {
			continue
		}
		k = strings.TrimSpace(k)
		v = strings.TrimSpace(v)
		if k != "" && v != "" {
			out[k] = v
		}
	}
	return out
}

func isYouTubeDomain(dom string) bool {
	d := strings.ToLower(strings.TrimSpace(dom))
	return d == "youtube.com" || d == ".youtube.com" || strings.HasSuffix(d, ".youtube.com")
}

func isGoogleDomain(dom string) bool {
	d := strings.ToLower(strings.TrimSpace(dom))
	return d == "google.com" || d == ".google.com" || strings.HasSuffix(d, ".google.com")
}

func hasValidYtCookiesFile() bool {
	f, err := os.Open(ytCookiesPath())
	if err != nil {
		return false
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		cols := strings.Split(line, "\t")
		if len(cols) >= 7 && ytmAuthCookieNames[cols[5]] && strings.TrimSpace(cols[6]) != "" {
			return true
		}
	}
	return false
}

func readSavedCookieHeader() string {
	if b, err := os.ReadFile(ytCookieHeaderPath()); err == nil {
		s := strings.TrimSpace(string(b))
		if s != "" && hasAuthCookieMap(parseCookieHeaderString(s)) {
			return s
		}
	}
	// Fallback: reconstruct Cookie header from Netscape yt_cookies.txt
	f, err := os.Open(ytCookiesPath())
	if err != nil {
		return ""
	}
	defer f.Close()
	cookies := map[string]string{}
	var order []string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		cols := strings.Split(line, "\t")
		if len(cols) < 7 {
			continue
		}
		dom, name, val := cols[0], cols[5], cols[6]
		if !isYouTubeDomain(dom) || name == "" || val == "" {
			continue
		}
		if _, exists := cookies[name]; !exists {
			order = append(order, name)
		}
		cookies[name] = val
	}
	if !hasAuthCookieMap(cookies) {
		return ""
	}
	parts := make([]string, 0, len(order))
	for _, k := range order {
		parts = append(parts, k+"="+cookies[k])
	}
	return strings.Join(parts, "; ")
}

func (a *App) HandleWebViewCookies(raw string, requireMusicHost bool) bool {
	type netscapeCookie struct {
		domain string
		path   string
		secure bool
		name   string
		value  string
	}
	var allEntries []netscapeCookie
	ytHeaderCookies := map[string]string{}
	var ytHeaderOrder []string

	for _, line := range strings.Split(raw, "\n") {
		line = strings.TrimRight(line, "\r")
		if line == "" {
			continue
		}
		cols := strings.SplitN(line, "\t", 5)
		if len(cols) != 5 {
			continue
		}
		dom := strings.TrimSpace(cols[0])
		pth := strings.TrimSpace(cols[1])
		if pth == "" {
			pth = "/"
		}
		sec := cols[2] == "1"
		name := strings.TrimSpace(cols[3])
		val := strings.TrimSpace(cols[4])
		if dom == "" || name == "" || val == "" {
			continue
		}
		if isYouTubeDomain(dom) || isGoogleDomain(dom) {
			allEntries = append(allEntries, netscapeCookie{
				domain: dom,
				path:   pth,
				secure: sec,
				name:   name,
				value:  val,
			})
		}
		if isYouTubeDomain(dom) {
			if _, ok := ytHeaderCookies[name]; !ok {
				ytHeaderOrder = append(ytHeaderOrder, name)
			}
			// Prefer music.youtube.com over generic .youtube.com if duplicates exist
			if strings.EqualFold(dom, "music.youtube.com") || ytHeaderCookies[name] == "" {
				ytHeaderCookies[name] = val
			}
		}
	}

	if !hasAuthCookieMap(ytHeaderCookies) {
		if !requireMusicHost {
			a.emit("app:toast", "Please finish signing in first")
		}
		return false
	}

	headerParts := make([]string, 0, len(ytHeaderOrder))
	for _, k := range ytHeaderOrder {
		if v := ytHeaderCookies[k]; v != "" {
			headerParts = append(headerParts, k+"="+v)
		}
	}
	headerStr := strings.Join(headerParts, "; ")
	if err := os.WriteFile(ytCookieHeaderPath(), []byte(headerStr), 0o600); err != nil {
		return false
	}

	var sb strings.Builder
	sb.WriteString("# Netscape HTTP Cookie File\n")
	sb.WriteString("# Generated by VibeMusic\n\n")
	const expires = "2147483647"
	for _, c := range allEntries {
		flag := "FALSE"
		if strings.HasPrefix(c.domain, ".") {
			flag = "TRUE"
		}
		secFlag := "FALSE"
		if c.secure || strings.HasPrefix(c.name, "__Secure-") || strings.HasPrefix(c.name, "__Host-") {
			secFlag = "TRUE"
		}
		fmt.Fprintf(&sb, "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", c.domain, flag, c.path, secFlag, expires, c.name, c.value)
	}
	if err := os.WriteFile(ytCookiesPath(), []byte(sb.String()), 0o600); err != nil {
		return false
	}

	a.emitYtmStatus()
	a.emit("ytm:login-success")
	a.emit("app:toast", "Connected to YouTube Music")
	return true
}

func (a *App) TryExtractBrowserCookies(browser string) bool {
	browser = strings.TrimSpace(browser)
	if browser == "" {
		return false
	}
	ytdlpPath := a.ytdlp.Path()
	if ytdlpPath == "" {
		return false
	}
	tmpFile := ytCookiesPath() + ".tmp"
	_ = os.Remove(tmpFile)
	defer os.Remove(tmpFile)

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, ytdlpPath,
		"--cookies-from-browser", browser,
		"--cookies", tmpFile,
		"--skip-download",
		"--no-warnings",
		"--playlist-items", "0",
		"https://music.youtube.com",
	)
	setCmdHidden(cmd)
	_ = cmd.Run()

	f, err := os.Open(tmpFile)
	if err != nil {
		return false
	}
	defer f.Close()

	ytCookies := map[string]string{}
	var order []string
	sc := bufio.NewScanner(f)
	var validLines []string
	for sc.Scan() {
		line := sc.Text()
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}
		cols := strings.Split(trimmed, "\t")
		if len(cols) < 7 {
			continue
		}
		dom, name, val := cols[0], cols[5], cols[6]
		if isYouTubeDomain(dom) || isGoogleDomain(dom) {
			validLines = append(validLines, trimmed)
		}
		if isYouTubeDomain(dom) && name != "" && val != "" {
			if _, ok := ytCookies[name]; !ok {
				order = append(order, name)
			}
			ytCookies[name] = val
		}
	}

	if !hasAuthCookieMap(ytCookies) {
		return false
	}

	var sb strings.Builder
	sb.WriteString("# Netscape HTTP Cookie File\n")
	sb.WriteString("# Extracted by VibeMusic\n\n")
	for _, l := range validLines {
		sb.WriteString(l)
		sb.WriteByte('\n')
	}
	if err := os.WriteFile(ytCookiesPath(), []byte(sb.String()), 0o600); err != nil {
		return false
	}

	parts := make([]string, 0, len(order))
	for _, k := range order {
		parts = append(parts, k+"="+ytCookies[k])
	}
	_ = os.WriteFile(ytCookieHeaderPath(), []byte(strings.Join(parts, "; ")), 0o600)
	return true
}

func (a *App) IsYtMusicLoggedIn() bool {
	if readSavedCookieHeader() != "" || hasValidYtCookiesFile() {
		return true
	}
	if b := a.Settings().CookieBrowser; b != "" {
		return a.TryExtractBrowserCookies(b)
	}
	return false
}

func (a *App) BuildSapisidHashHeader(origin string) string {
	if origin == "" {
		origin = "https://music.youtube.com"
	}
	header := readSavedCookieHeader()
	if header == "" {
		return ""
	}
	cookies := parseCookieHeaderString(header)
	sapisid := cookies["SAPISID"]
	p1 := cookies["__Secure-1PAPISID"]
	p3 := cookies["__Secure-3PAPISID"]
	fallback := sapisid
	if fallback == "" {
		fallback = p3
	}
	if fallback == "" {
		fallback = p1
	}
	if fallback == "" {
		return ""
	}

	ts := time.Now().Unix()
	sha1Hex := func(val string) string {
		sum := sha1.Sum([]byte(fmt.Sprintf("%d %s %s", ts, val, origin)))
		return hex.EncodeToString(sum[:])
	}
	valOr := func(v string) string {
		if v != "" {
			return v
		}
		return fallback
	}
	return strings.Join([]string{
		fmt.Sprintf("SAPISIDHASH %d_%s", ts, sha1Hex(valOr(sapisid))),
		fmt.Sprintf("SAPISID1PHASH %d_%s", ts, sha1Hex(valOr(p1))),
		fmt.Sprintf("SAPISID3PHASH %d_%s", ts, sha1Hex(valOr(p3))),
	}, " ")
}

func (a *App) emitYtmStatus() {
	a.emit("ytm:status", a.GetYtMusicStatus())
}

func (a *App) setYtmState(syncing bool, msg string) {
	a.ytmMu.Lock()
	a.ytmSyncing = syncing
	a.ytmMessage = msg
	a.ytmMu.Unlock()
	a.emitYtmStatus()
}

func (a *App) GetYtMusicStatus() YtMusicStatus {
	a.ytmMu.Lock()
	syncing := a.ytmSyncing
	msg := a.ytmMessage
	a.ytmMu.Unlock()
	return YtMusicStatus{
		LoggedIn: readSavedCookieHeader() != "" || hasValidYtCookiesFile(),
		Syncing:  syncing,
		Message:  msg,
	}
}

func (a *App) StartYtMusicLogin() {
	if b := a.Settings().CookieBrowser; b != "" {
		go func() {
			if a.TryExtractBrowserCookies(b) {
				a.emitYtmStatus()
				a.emit("ytm:login-success")
				a.emit("app:toast", "Connected to YouTube Music ("+b+")")
				return
			}
			nativeOpenYtMusicLogin(ytmWebviewCookiePath())
		}()
		return
	}
	nativeOpenYtMusicLogin(ytmWebviewCookiePath())
}

func (a *App) LogoutYtMusic() {
	_ = os.Remove(ytCookiesPath())
	_ = os.Remove(ytCookieHeaderPath())
	_ = os.RemoveAll(ytmWebviewDir())
	nativeClearYtMusicCookies()
	if a.Settings().CookieBrowser != "" {
		a.updateSettings(func(s *Settings) { s.CookieBrowser = "" })
		a.emit("settings:changed", a.Settings())
	}
	a.setYtmState(false, "")
	a.emit("app:toast", "Signed out of YouTube Music")
}

// ---------- InnerTube browse client ----------

var (
	ytmCountRe    = regexp.MustCompile(`(?i)(\d+)\s+(?:songs?|tracks?)`)
	ytmDurationRe = regexp.MustCompile(`^\d{1,2}(?::\d{2}){1,2}$`)
	ytmPlaysRe    = regexp.MustCompile(`(?i)^\d+(?:\.\d+)?[KMB]?\s+(?:plays?|views?)$`)
	ytmYearRe     = regexp.MustCompile(`^(?:19|20)\d{2}$`)
	ytmMediaTypes = map[string]bool{
		"song": true, "video": true, "album": true, "single": true, "ep": true, "podcast": true, "episode": true,
	}
)

func asMap(v any) map[string]any {
	m, _ := v.(map[string]any)
	return m
}

func asSlice(v any) []any {
	s, _ := v.([]any)
	return s
}

func getPath(m map[string]any, keys ...string) any {
	var cur any = m
	for _, k := range keys {
		cm := asMap(cur)
		if cm == nil {
			return nil
		}
		cur = cm[k]
	}
	return cur
}

func getMap(m map[string]any, keys ...string) map[string]any {
	return asMap(getPath(m, keys...))
}

func getSlice(m map[string]any, keys ...string) []any {
	return asSlice(getPath(m, keys...))
}

func getStr(m map[string]any, keys ...string) string {
	s, _ := getPath(m, keys...).(string)
	return strings.TrimSpace(s)
}

func extractRunsText(textObj map[string]any) string {
	if textObj == nil {
		return ""
	}
	if runs := asSlice(textObj["runs"]); len(runs) > 0 {
		var sb strings.Builder
		for _, r := range runs {
			if rm := asMap(r); rm != nil {
				if t, ok := rm["text"].(string); ok {
					sb.WriteString(t)
				}
			}
		}
		return sb.String()
	}
	if s, ok := textObj["simpleText"].(string); ok {
		return s
	}
	return ""
}

func extractBestThumbnail(thumbObj map[string]any) string {
	if thumbObj == nil {
		return ""
	}
	arr := asSlice(thumbObj["thumbnails"])
	if len(arr) == 0 {
		return ""
	}
	last := asMap(arr[len(arr)-1])
	if last == nil {
		return ""
	}
	return getStr(last, "url")
}

func isExcludedBrowseID(browseID string) bool {
	return browseID == "VLLM" ||
		browseID == "LM" ||
		browseID == "FEmusic_liked_videos" ||
		browseID == "VLSE" ||
		browseID == "SE" ||
		(strings.HasPrefix(browseID, "FEmusic_library_") && !strings.Contains(browseID, "release_detail"))
}

func buildYtmCollection(browseID, watchPlaylistID, title, subtitle string, countHint int, thumbURL, colType string) YtMusicCollection {
	cleanID := browseID
	if watchPlaylistID != "" {
		cleanID = watchPlaylistID
	} else if strings.HasPrefix(browseID, "VL") {
		cleanID = strings.TrimPrefix(browseID, "VL")
	}
	playlistURL := "https://music.youtube.com/browse/" + browseID
	if watchPlaylistID != "" {
		playlistURL = "https://music.youtube.com/playlist?list=" + watchPlaylistID
	} else if strings.HasPrefix(browseID, "VL") {
		playlistURL = "https://music.youtube.com/playlist?list=" + strings.TrimPrefix(browseID, "VL")
	}
	if subtitle == "" {
		switch colType {
		case "ALBUM":
			subtitle = "Saved Album"
		case "PLAYLIST":
			subtitle = "Playlist"
		case "LIKED_MUSIC":
			subtitle = "Liked Songs"
		}
	}
	return YtMusicCollection{
		ID:             cleanID,
		BrowseID:       browseID,
		PlaylistURL:    playlistURL,
		Title:          title,
		Subtitle:       subtitle,
		TrackCountHint: countHint,
		ThumbnailURL:   thumbURL,
		Type:           colType,
	}
}

func (a *App) callInnerTubeBrowse(browseID, continuation string) (map[string]any, error) {
	cookieHeader := readSavedCookieHeader()
	authHeader := a.BuildSapisidHashHeader("https://music.youtube.com")

	payload := map[string]any{
		"context": map[string]any{
			"client": map[string]any{
				"clientName":    "WEB_REMIX",
				"clientVersion": "1.20241118.01.00",
				"hl":            "en",
				"gl":            "US",
			},
		},
	}
	if browseID != "" {
		payload["browseId"] = browseID
	}
	if continuation != "" {
		payload["continuation"] = continuation
	}
	bodyBytes, _ := json.Marshal(payload)

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", bytes.NewReader(bodyBytes))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", "https://music.youtube.com")
	req.Header.Set("Referer", "https://music.youtube.com/")
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
	req.Header.Set("X-Origin", "https://music.youtube.com")
	req.Header.Set("X-Goog-AuthUser", "0")
	req.Header.Set("X-Youtube-Client-Name", "67")
	req.Header.Set("X-Youtube-Client-Version", "1.20241118.01.00")
	if cookieHeader != "" {
		req.Header.Set("Cookie", cookieHeader)
	}
	if authHeader != "" {
		req.Header.Set("Authorization", authHeader)
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 || len(raw) == 0 {
		return nil, fmt.Errorf("InnerTube HTTP %d", resp.StatusCode)
	}
	var root map[string]any
	if err := json.Unmarshal(raw, &root); err != nil {
		return nil, err
	}
	return root, nil
}

func collectRenderers(node any, twoRowOut, listRowOut *[]map[string]any, skipCarousels bool) {
	switch v := node.(type) {
	case map[string]any:
		if r := asMap(v["musicTwoRowItemRenderer"]); r != nil {
			*twoRowOut = append(*twoRowOut, r)
		}
		if r := asMap(v["musicResponsiveListItemRenderer"]); r != nil {
			*listRowOut = append(*listRowOut, r)
		}
		for k, child := range v {
			if skipCarousels && k == "musicCarouselShelfRenderer" {
				continue
			}
			collectRenderers(child, twoRowOut, listRowOut, skipCarousels)
		}
	case []any:
		for _, child := range v {
			collectRenderers(child, twoRowOut, listRowOut, skipCarousels)
		}
	}
}

func findPlaylistShelfNodes(node any, out *[]map[string]any) {
	switch v := node.(type) {
	case map[string]any:
		matched := false
		for _, key := range []string{"musicPlaylistShelfRenderer", "musicPlaylistShelfContinuation", "appendContinuationItemsAction"} {
			if obj := asMap(v[key]); obj != nil {
				*out = append(*out, obj)
				matched = true
			}
		}
		if !matched {
			for _, child := range v {
				findPlaylistShelfNodes(child, out)
			}
		}
	case []any:
		for _, child := range v {
			findPlaylistShelfNodes(child, out)
		}
	}
}

func extractContinuationToken(node any) string {
	switch v := node.(type) {
	case map[string]any:
		if tok := getStr(v, "nextContinuationData", "continuation"); tok != "" {
			return tok
		}
		if tok := getStr(v, "continuationCommand", "token"); tok != "" {
			return tok
		}
		for _, child := range v {
			if tok := extractContinuationToken(child); tok != "" {
				return tok
			}
		}
	case []any:
		for _, child := range v {
			if tok := extractContinuationToken(child); tok != "" {
				return tok
			}
		}
	}
	return ""
}

func parseTwoRowCollection(renderer map[string]any, colType string) (YtMusicCollection, bool) {
	navBrowseID := getStr(renderer, "navigationEndpoint", "browseEndpoint", "browseId")
	playEndpoint := getMap(renderer, "thumbnailOverlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint")
	watchPlaylistID := getStr(playEndpoint, "watchPlaylistEndpoint", "playlistId")
	if watchPlaylistID == "" {
		watchPlaylistID = getStr(playEndpoint, "watchEndpoint", "playlistId")
	}
	effectiveBrowseID := navBrowseID
	if effectiveBrowseID == "" && watchPlaylistID != "" {
		effectiveBrowseID = "VL" + watchPlaylistID
	}
	if effectiveBrowseID == "" || isExcludedBrowseID(effectiveBrowseID) {
		return YtMusicCollection{}, false
	}

	title := strings.TrimSpace(extractRunsText(asMap(renderer["title"])))
	if title == "" || strings.EqualFold(title, "New playlist") {
		return YtMusicCollection{}, false
	}

	subtitle := strings.TrimSpace(extractRunsText(asMap(renderer["subtitle"])))
	countHint := 0
	if m := ytmCountRe.FindStringSubmatch(subtitle); len(m) == 2 {
		countHint, _ = strconv.Atoi(m[1])
	}
	thumbURL := extractBestThumbnail(getMap(renderer, "thumbnailRenderer", "musicThumbnailRenderer", "thumbnail"))
	return buildYtmCollection(effectiveBrowseID, watchPlaylistID, title, subtitle, countHint, thumbURL, colType), true
}

func parseResponsiveCollection(renderer map[string]any, colType string) (YtMusicCollection, bool) {
	navBrowseID := getStr(renderer, "navigationEndpoint", "browseEndpoint", "browseId")
	playEndpoint := getMap(renderer, "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint")
	watchPlaylistID := getStr(playEndpoint, "watchPlaylistEndpoint", "playlistId")
	if watchPlaylistID == "" {
		watchPlaylistID = getStr(playEndpoint, "watchEndpoint", "playlistId")
	}

	flexCols := asSlice(renderer["flexColumns"])
	if len(flexCols) == 0 {
		return YtMusicCollection{}, false
	}
	col0Text := getMap(asMap(flexCols[0]), "musicResponsiveListItemFlexColumnRenderer", "text")
	var col1Text map[string]any
	if len(flexCols) > 1 {
		col1Text = getMap(asMap(flexCols[1]), "musicResponsiveListItemFlexColumnRenderer", "text")
	}

	col0BrowseID := ""
	if runs := asSlice(col0Text["runs"]); len(runs) > 0 {
		if r0 := asMap(runs[0]); r0 != nil {
			col0BrowseID = getStr(r0, "navigationEndpoint", "browseEndpoint", "browseId")
		}
	}

	effectiveBrowseID := navBrowseID
	if effectiveBrowseID == "" {
		effectiveBrowseID = col0BrowseID
	}
	if effectiveBrowseID == "" && watchPlaylistID != "" {
		effectiveBrowseID = "VL" + watchPlaylistID
	}
	if effectiveBrowseID == "" || isExcludedBrowseID(effectiveBrowseID) {
		return YtMusicCollection{}, false
	}

	title := strings.TrimSpace(extractRunsText(col0Text))
	if title == "" || strings.EqualFold(title, "New playlist") {
		return YtMusicCollection{}, false
	}

	subtitle := strings.TrimSpace(extractRunsText(col1Text))
	countHint := 0
	if m := ytmCountRe.FindStringSubmatch(subtitle); len(m) == 2 {
		countHint, _ = strconv.Atoi(m[1])
	}
	thumbURL := extractBestThumbnail(getMap(renderer, "thumbnail", "musicThumbnailRenderer", "thumbnail"))
	return buildYtmCollection(effectiveBrowseID, watchPlaylistID, title, subtitle, countHint, thumbURL, colType), true
}

func (a *App) fetchCollectionsBrowse(browseID, colType string, maxPages int) []YtMusicCollection {
	var out []YtMusicCollection
	seenBrowseIDs := map[string]bool{}
	continuation := ""

	for page := 0; page < maxPages; page++ {
		b := ""
		if page == 0 {
			b = browseID
		}
		root, err := a.callInnerTubeBrowse(b, continuation)
		if err != nil || root == nil {
			break
		}

		var twoRows, listRows []map[string]any
		collectRenderers(root, &twoRows, &listRows, false)

		for _, r := range twoRows {
			if col, ok := parseTwoRowCollection(r, colType); ok {
				if !seenBrowseIDs[col.BrowseID] && !seenBrowseIDs[col.ID] {
					seenBrowseIDs[col.BrowseID] = true
					seenBrowseIDs[col.ID] = true
					out = append(out, col)
				}
			}
		}
		for _, r := range listRows {
			if col, ok := parseResponsiveCollection(r, colType); ok {
				if !seenBrowseIDs[col.BrowseID] && !seenBrowseIDs[col.ID] {
					seenBrowseIDs[col.BrowseID] = true
					seenBrowseIDs[col.ID] = true
					out = append(out, col)
				}
			}
		}

		continuation = extractContinuationToken(root)
		if continuation == "" {
			break
		}
	}
	return out
}

func parseDurationSeconds(text string) float64 {
	parts := strings.Split(text, ":")
	nums := make([]int, 0, len(parts))
	for _, p := range parts {
		n, err := strconv.Atoi(strings.TrimSpace(p))
		if err != nil {
			return 0
		}
		nums = append(nums, n)
	}
	switch len(nums) {
	case 2:
		return float64(nums[0]*60 + nums[1])
	case 3:
		return float64(nums[0]*3600 + nums[1]*60 + nums[2])
	default:
		return 0
	}
}

func extractAlbumArtistFromSubtitle(subtitle string) string {
	for _, p := range strings.Split(subtitle, "•") {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		if !ytmMediaTypes[strings.ToLower(p)] && !ytmYearRe.MatchString(p) && !ytmCountRe.MatchString(p) {
			return cleanArtistString(p)
		}
	}
	return ""
}

func parseTrackFromListRenderer(renderer map[string]any, col YtMusicCollection) (YtMusicTrackItem, bool) {
	videoID := getStr(renderer, "playlistItemData", "videoId")
	if !ytIDRe.MatchString(videoID) {
		videoID = getStr(renderer, "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId")
	}
	flexCols := asSlice(renderer["flexColumns"])
	if !ytIDRe.MatchString(videoID) {
		for _, fc := range flexCols {
			runs := asSlice(getPath(asMap(fc), "musicResponsiveListItemFlexColumnRenderer", "text", "runs"))
			for _, r := range runs {
				vid := getStr(asMap(r), "navigationEndpoint", "watchEndpoint", "videoId")
				if ytIDRe.MatchString(vid) {
					videoID = vid
					break
				}
			}
			if ytIDRe.MatchString(videoID) {
				break
			}
		}
	}
	if !ytIDRe.MatchString(videoID) {
		return YtMusicTrackItem{}, false
	}

	var col0Text map[string]any
	if len(flexCols) > 0 {
		col0Text = getMap(asMap(flexCols[0]), "musicResponsiveListItemFlexColumnRenderer", "text")
	}
	title := strings.TrimSpace(extractRunsText(col0Text))
	if title == "" {
		title = strings.TrimSpace(extractRunsText(asMap(renderer["title"])))
	}
	if title == "" || title == "[Deleted video]" || title == "[Private video]" {
		return YtMusicTrackItem{}, false
	}

	var artists []string
	album := ""
	if col.Type == "ALBUM" {
		album = col.Title
	}
	durationSec := 0.0

	var col1Text map[string]any
	if len(flexCols) > 1 {
		col1Text = getMap(asMap(flexCols[1]), "musicResponsiveListItemFlexColumnRenderer", "text")
	}
	if runs := asSlice(col1Text["runs"]); len(runs) > 0 {
		for _, r := range runs {
			rm := asMap(r)
			if rm == nil {
				continue
			}
			txt := getStr(rm, "text")
			if txt == "" || txt == "•" || txt == "," || txt == "&" {
				continue
			}
			pageType := getStr(rm, "navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType")
			switch pageType {
			case "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL":
				artists = append(artists, txt)
			case "MUSIC_PAGE_TYPE_ALBUM":
				if album == "" {
					album = txt
				}
			}
		}
	}

	if len(artists) == 0 && col1Text != nil {
		for _, part := range strings.Split(extractRunsText(col1Text), "•") {
			part = strings.TrimSpace(part)
			if part == "" || ytmMediaTypes[strings.ToLower(part)] {
				continue
			}
			if ytmDurationRe.MatchString(part) {
				durationSec = parseDurationSeconds(part)
				continue
			}
			if ytmPlaysRe.MatchString(part) || ytmYearRe.MatchString(part) {
				continue
			}
			artists = append(artists, part)
			break
		}
	}

	if album == "" && len(flexCols) >= 3 {
		col2Text := getMap(asMap(flexCols[2]), "musicResponsiveListItemFlexColumnRenderer", "text")
		cand := strings.TrimSpace(extractRunsText(col2Text))
		if cand != "" && !ytmPlaysRe.MatchString(cand) && !ytmDurationRe.MatchString(cand) {
			album = cand
		}
	}

	if durationSec <= 0 {
		for _, fc := range asSlice(renderer["fixedColumns"]) {
			textObj := getMap(asMap(fc), "musicResponsiveListItemFixedColumnRenderer", "text")
			durStr := strings.TrimSpace(extractRunsText(textObj))
			if ytmDurationRe.MatchString(durStr) {
				durationSec = parseDurationSeconds(durStr)
				break
			}
		}
	}

	cleanedArtist := ""
	if len(artists) > 0 {
		cleanedArtist = cleanArtistString(strings.Join(artists, ", "))
	} else if col.Type == "ALBUM" {
		cleanedArtist = extractAlbumArtistFromSubtitle(col.Subtitle)
	}

	return YtMusicTrackItem{
		YtID:        videoID,
		Title:       title,
		Artist:      cleanedArtist,
		Album:       album,
		DurationSec: durationSec,
	}, true
}

func (a *App) fetchTracksViaInnerTube(col YtMusicCollection) []YtMusicTrackItem {
	var out []YtMusicTrackItem
	seen := map[string]bool{}
	continuation := ""
	const maxPages = 25

	for page := 0; page < maxPages; page++ {
		b := ""
		if page == 0 {
			b = col.BrowseID
		}
		root, err := a.callInnerTubeBrowse(b, continuation)
		if err != nil || root == nil {
			break
		}

		var twoRows, listRows []map[string]any
		if col.Type == "PLAYLIST" || col.Type == "LIKED_MUSIC" {
			var shelves []map[string]any
			findPlaylistShelfNodes(root, &shelves)
			if len(shelves) > 0 {
				nextTok := ""
				for _, shelf := range shelves {
					collectRenderers(shelf, &twoRows, &listRows, true)
					if nextTok == "" {
						nextTok = extractContinuationToken(shelf)
					}
				}
				continuation = nextTok
			} else {
				collectRenderers(root, &twoRows, &listRows, true)
				continuation = extractContinuationToken(root)
			}
		} else {
			collectRenderers(root, &twoRows, &listRows, true)
			continuation = extractContinuationToken(root)
		}

		for _, r := range listRows {
			if item, ok := parseTrackFromListRenderer(r, col); ok {
				if !seen[item.YtID] {
					seen[item.YtID] = true
					out = append(out, item)
				}
			}
		}
		if continuation == "" {
			break
		}
	}
	return out
}

func (a *App) fetchPlaylistsViaYtdlp() []YtMusicCollection {
	ytdlpPath := a.ytdlp.Path()
	if ytdlpPath == "" {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	args := []string{"--flat-playlist", "-J", "--no-warnings"}
	args = append(args, cookieArgs(a.Settings())...)
	args = append(args, "https://music.youtube.com/library/playlists")
	cmd := exec.CommandContext(ctx, ytdlpPath, args...)
	setCmdHidden(cmd)
	raw, err := cmd.Output()
	if err != nil || len(raw) == 0 {
		return nil
	}
	var root struct {
		Entries []struct {
			ID        string `json:"id"`
			Title     string `json:"title"`
			URL       string `json:"url"`
			Thumbnail string `json:"thumbnail"`
		} `json:"entries"`
	}
	if json.Unmarshal(raw, &root) != nil {
		return nil
	}
	var out []YtMusicCollection
	for _, e := range root.Entries {
		id := strings.TrimSpace(e.ID)
		title := strings.TrimSpace(e.Title)
		if id == "" || id == "LM" || id == "SE" || title == "" {
			continue
		}
		u := strings.TrimSpace(e.URL)
		if u == "" {
			u = "https://music.youtube.com/playlist?list=" + id
		}
		browseID := id
		if !strings.HasPrefix(browseID, "VL") {
			browseID = "VL" + id
		}
		out = append(out, YtMusicCollection{
			ID:           id,
			BrowseID:     browseID,
			PlaylistURL:  u,
			Title:        title,
			Subtitle:     "Playlist",
			ThumbnailURL: strings.TrimSpace(e.Thumbnail),
			Type:         "PLAYLIST",
		})
	}
	return out
}

func (a *App) fetchTracksViaYtdlp(col YtMusicCollection) []YtMusicTrackItem {
	ytdlpPath := a.ytdlp.Path()
	if ytdlpPath == "" {
		return nil
	}
	var candidates []string
	seenURL := map[string]bool{}
	addURL := func(u string) {
		u = strings.TrimSpace(u)
		if u != "" && !seenURL[u] {
			seenURL[u] = true
			candidates = append(candidates, u)
		}
	}
	addURL(col.PlaylistURL)
	if strings.HasPrefix(col.ID, "PL") || strings.HasPrefix(col.ID, "OLAK") || col.ID == "LM" {
		addURL("https://www.youtube.com/playlist?list=" + col.ID)
	}
	if strings.HasPrefix(col.BrowseID, "MPREb_") {
		addURL("https://music.youtube.com/browse/" + col.BrowseID)
	}

	cArgs := cookieArgs(a.Settings())
	for _, targetURL := range candidates {
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		args := []string{"--flat-playlist", "-J", "--no-warnings"}
		args = append(args, cArgs...)
		args = append(args, targetURL)
		cmd := exec.CommandContext(ctx, ytdlpPath, args...)
		setCmdHidden(cmd)
		raw, err := cmd.Output()
		cancel()
		if err != nil || len(raw) == 0 {
			continue
		}
		var root struct {
			Entries []struct {
				ID       string  `json:"id"`
				Title    string  `json:"title"`
				Artist   string  `json:"artist"`
				Uploader string  `json:"uploader"`
				Channel  string  `json:"channel"`
				Album    string  `json:"album"`
				Duration float64 `json:"duration"`
			} `json:"entries"`
		}
		if json.Unmarshal(raw, &root) != nil {
			continue
		}
		var out []YtMusicTrackItem
		seen := map[string]bool{}
		for _, e := range root.Entries {
			id := strings.TrimSpace(e.ID)
			if !ytIDRe.MatchString(id) || seen[id] {
				continue
			}
			title := strings.TrimSpace(e.Title)
			if title == "" || title == "[Deleted video]" || title == "[Private video]" {
				continue
			}
			seen[id] = true
			rawArtist := firstNonEmpty(strings.TrimSpace(e.Artist), strings.TrimSpace(e.Uploader), strings.TrimSpace(e.Channel))
			artist := ""
			if rawArtist != "" {
				artist = cleanArtistString(rawArtist)
			}
			album := strings.TrimSpace(e.Album)
			if album == "" && col.Type == "ALBUM" {
				album = col.Title
			}
			dur := e.Duration
			if dur < 0 {
				dur = 0
			}
			out = append(out, YtMusicTrackItem{
				YtID:        id,
				Title:       title,
				Artist:      artist,
				Album:       album,
				DurationSec: dur,
			})
		}
		if len(out) > 0 {
			return out
		}
	}
	return nil
}

func (a *App) fetchYtMusicLibraryInternal() ([]YtMusicCollection, error) {
	if !a.IsYtMusicLoggedIn() {
		return nil, fmt.Errorf("not signed in to YouTube Music")
	}
	var result []YtMusicCollection
	seenIDs := map[string]bool{}

	likedMusic := YtMusicCollection{
		ID:          "LM",
		BrowseID:    "VLLM",
		PlaylistURL: "https://music.youtube.com/playlist?list=LM",
		Title:       "Liked Music",
		Subtitle:    "Your YouTube Music likes • Syncs to Liked Songs",
		Type:        "LIKED_MUSIC",
	}
	result = append(result, likedMusic)
	seenIDs["LM"] = true

	playlists := a.fetchCollectionsBrowse("FEmusic_liked_playlists", "PLAYLIST", 8)
	for _, col := range playlists {
		if !seenIDs[col.ID] {
			seenIDs[col.ID] = true
			result = append(result, col)
		}
	}

	if len(playlists) == 0 && hasValidYtCookiesFile() {
		for _, col := range a.fetchPlaylistsViaYtdlp() {
			if !seenIDs[col.ID] {
				seenIDs[col.ID] = true
				result = append(result, col)
			}
		}
	}

	albums := a.fetchCollectionsBrowse("FEmusic_liked_albums", "ALBUM", 8)
	for _, col := range albums {
		if !seenIDs[col.ID] {
			seenIDs[col.ID] = true
			result = append(result, col)
		}
	}

	return result, nil
}

func (a *App) fetchCollectionTracks(col YtMusicCollection) []YtMusicTrackItem {
	tracks := a.fetchTracksViaInnerTube(col)
	if len(tracks) > 0 {
		return tracks
	}
	if col.Type == "LIKED_MUSIC" && col.BrowseID != "FEmusic_liked_videos" {
		alt := col
		alt.BrowseID = "FEmusic_liked_videos"
		if altTracks := a.fetchTracksViaInnerTube(alt); len(altTracks) > 0 {
			return altTracks
		}
	}
	return a.fetchTracksViaYtdlp(col)
}

func itemMatchesTrack(item YtMusicTrackItem, t Track, knownNames map[string]bool) bool {
	if item.YtID != "" && trackVideoID(t) == item.YtID {
		return true
	}
	if strings.TrimSpace(item.Title) == "" || strings.TrimSpace(t.Title) == "" {
		return false
	}

	itemKeys := songTitleKeys(item.Title, item.Artist, knownNames)
	tKeys := songTitleKeys(t.Title, t.Artist, knownNames)
	itemKeySet := make(map[string]bool, len(itemKeys))
	for _, k := range itemKeys {
		itemKeySet[k] = true
	}
	titleKeyMatch := false
	for _, k := range tKeys {
		if itemKeySet[k] {
			titleKeyMatch = true
			break
		}
	}

	itemTokens := songCoreTokens(item.Title, item.Artist, knownNames)
	tTokens := songCoreTokens(t.Title, t.Artist, knownNames)
	tokenMatch := areSameSongTokens(itemTokens, tTokens)

	if !titleKeyMatch && !tokenMatch {
		return false
	}

	itemArtists := buildRawArtistSet(item.Artist)
	tArtists := buildRawArtistSet(t.Artist)

	// 1. Artist overlap
	artistOverlap := false
	for ia := range itemArtists {
		if tArtists[ia] {
			artistOverlap = true
			break
		}
	}
	if !artistOverlap && len(itemArtists) > 0 && len(tArtists) > 0 {
		tArtistNorm := " " + normSongText(t.Artist) + " "
		tTitleNorm := " " + normSongText(t.Title) + " "
		iArtistNorm := " " + normSongText(item.Artist) + " "
		iTitleNorm := " " + normSongText(item.Title) + " "
		for ia := range itemArtists {
			if utf8.RuneCountInString(ia) >= 2 && (strings.Contains(tArtistNorm, " "+ia+" ") || strings.Contains(tTitleNorm, " "+ia+" ")) {
				artistOverlap = true
				break
			}
		}
		if !artistOverlap {
			for ta := range tArtists {
				if utf8.RuneCountInString(ta) >= 2 && (strings.Contains(iArtistNorm, " "+ta+" ") || strings.Contains(iTitleNorm, " "+ta+" ")) {
					artistOverlap = true
					break
				}
			}
		}
	}
	if artistOverlap {
		return true
	}

	// 2. Duration proximity (within 15 seconds)
	if item.DurationSec > 0 && t.Duration > 0 && math.Abs(item.DurationSec-t.Duration) <= 15 {
		return true
	}

	// 3. Missing/generic artist metadata + matching canonical title + compatible duration
	if (len(tArtists) == 0 || len(itemArtists) == 0) && titleKeyMatch {
		if item.DurationSec == 0 || t.Duration == 0 || math.Abs(item.DurationSec-t.Duration) <= 15 {
			return true
		}
	}

	return false
}

func findExistingTrackForYtmItem(item YtMusicTrackItem, tracks []Track, knownNames map[string]bool) (Track, bool) {
	if item.YtID != "" {
		for _, t := range tracks {
			if trackVideoID(t) == item.YtID {
				return t, true
			}
		}
	}
	for _, t := range tracks {
		if itemMatchesTrack(item, t, knownNames) {
			return t, true
		}
	}
	return Track{}, false
}

func (a *App) ImportYtMusicCollections(collections []YtMusicCollection) string {
	if len(collections) == 0 {
		return "No collections selected"
	}
	a.ytmMu.Lock()
	if a.ytmSyncing {
		a.ytmMu.Unlock()
		return "YouTube Music import/sync is already running"
	}
	a.ytmSyncing = true
	a.ytmMessage = "Starting YouTube Music import…"
	a.ytmMu.Unlock()
	a.emitYtmStatus()

	existingSynced := a.store.LoadSyncedYtCollections()
	mergedSet := map[string]bool{}
	for _, id := range existingSynced {
		mergedSet[id] = true
	}
	for _, col := range collections {
		if col.ID != "" {
			mergedSet[col.ID] = true
		}
	}
	mergedIDs := make([]string, 0, len(mergedSet))
	for id := range mergedSet {
		mergedIDs = append(mergedIDs, id)
	}
	sort.Strings(mergedIDs)
	_ = a.store.SaveSyncedYtCollections(mergedIDs)

	go func() {
		defer func() {
			a.ytmMu.Lock()
			wasSyncing := a.ytmSyncing
			a.ytmSyncing = false
			a.ytmMu.Unlock()
			if wasSyncing {
				a.emitYtmStatus()
			}
		}()

		linkedExisting := 0
		queuedDownloads := 0
		libraryUpdated := false
		playlistsUpdated := false

		var queuedTracks []Track
		seenJobIDs := map[int64]bool{}
		queuedSongToYtID := map[string]string{}

		for _, col := range collections {
			a.setYtmState(true, fmt.Sprintf("Reading YT Music: %s…", col.Title))
			trackItems := a.fetchCollectionTracks(col)

			allTracks := a.reloadTracks()
			knownNames := collectKnownLibraryNames(allTracks)

			var targetPlID int64
			if col.Type == "PLAYLIST" || col.Type == "ALBUM" {
				if pid, err := a.store.GetOrCreatePlaylist(col.Title); err == nil && pid > 0 {
					targetPlID = pid
					playlistsUpdated = true
				}
			}

			albumOverride := ""
			if col.Type == "ALBUM" {
				albumOverride = col.Title
			}
			markAsLiked := col.Type == "LIKED_MUSIC"

			if len(trackItems) == 0 {
				if col.PlaylistURL != "" {
					if jobID, err := a.dl.EnqueueYtMusicPlaylist(col.PlaylistURL, col.Title, targetPlID, markAsLiked, albumOverride); err == nil {
						if !seenJobIDs[jobID] {
							seenJobIDs[jobID] = true
							queuedDownloads++
						}
					}
				}
				continue
			}

			var existingIDsToLink []string
			linkedInCol := map[string]bool{}
			for _, item := range trackItems {
				if existing, ok := findExistingTrackForYtmItem(item, allTracks, knownNames); ok {
					if markAsLiked && !existing.Liked {
						if a.store.SetLiked(existing.ID, true) == nil {
							existing.Liked = true
							a.trMu.Lock()
							if cur, exists := a.tracks[existing.ID]; exists {
								cur.Liked = true
								a.tracks[existing.ID] = cur
							}
							a.trMu.Unlock()
							a.emit("track:liked", existing.ID, true)
							libraryUpdated = true
						}
					}
					if albumOverride != "" && existing.Album != albumOverride {
						if a.store.UpdateTrackAlbum(existing.ID, albumOverride) == nil {
							existing.Album = albumOverride
							libraryUpdated = true
						}
					}
					if !linkedInCol[existing.ID] {
						linkedInCol[existing.ID] = true
						existingIDsToLink = append(existingIDsToLink, existing.ID)
						linkedExisting++
					}
					continue
				}

				targetYtID := item.YtID
				reusedQueued := false
				if qMatch, ok := findExistingTrackForYtmItem(item, queuedTracks, knownNames); ok && qMatch.YtID != "" {
					targetYtID = qMatch.YtID
					reusedQueued = true
				} else if qID, ok := queuedSongToYtID[normSongText(item.Artist)+"|"+normSongText(stripTitleDecorations(item.Title))]; ok && qID != "" {
					targetYtID = qID
					reusedQueued = true
				}

				displayParts := make([]string, 0, 2)
				if strings.TrimSpace(item.Artist) != "" {
					displayParts = append(displayParts, strings.TrimSpace(item.Artist))
				}
				if strings.TrimSpace(item.Title) != "" {
					displayParts = append(displayParts, strings.TrimSpace(item.Title))
				}
				displayTitle := strings.Join(displayParts, " - ")
				if displayTitle == "" {
					displayTitle = targetYtID
				}

				qItem := item
				qItem.YtID = targetYtID
				if jobID, err := a.dl.EnqueueYtMusicTrackItem(qItem, displayTitle, targetPlID, markAsLiked, albumOverride); err == nil {
					if !seenJobIDs[jobID] {
						seenJobIDs[jobID] = true
						queuedDownloads++
					}
					if !reusedQueued {
						queuedSongToYtID[normSongText(item.Artist)+"|"+normSongText(stripTitleDecorations(item.Title))] = targetYtID
						queuedTracks = append(queuedTracks, Track{
							ID:       "yt:" + targetYtID,
							YtID:     targetYtID,
							Title:    item.Title,
							Artist:   item.Artist,
							Album:    item.Album,
							Duration: item.DurationSec,
						})
					}
				}
			}

			if targetPlID > 0 && len(existingIDsToLink) > 0 {
				if n, _ := a.store.AddToPlaylist(targetPlID, existingIDsToLink); n > 0 {
					playlistsUpdated = true
				}
			}
		}

		if libraryUpdated {
			a.reloadTracks()
			a.emit("library:changed")
		}
		if playlistsUpdated {
			a.emit("playlists:changed")
		}

		summary := fmt.Sprintf("YT Music import: %d linked, %d queued (%d collections)", linkedExisting, queuedDownloads, len(collections))
		a.ytmMu.Lock()
		a.ytmSyncing = false
		a.ytmMessage = summary
		a.ytmMu.Unlock()
		a.emitYtmStatus()
		a.emit("app:toast", summary)
	}()

	return ""
}

func (a *App) SyncYtMusicLibrary() string {
	a.ytmMu.Lock()
	if a.ytmSyncing {
		a.ytmMu.Unlock()
		return "YouTube Music sync is already running"
	}
	a.ytmSyncing = true
	a.ytmMessage = "Syncing with YouTube Music…"
	a.ytmMu.Unlock()
	a.emitYtmStatus()

	go func() {
		allCols, err := a.fetchYtMusicLibraryInternal()
		if err != nil {
			errMsg := "Sync failed: " + err.Error()
			a.setYtmState(false, errMsg)
			a.emit("app:toast", errMsg)
			return
		}

		savedSet := map[string]bool{}
		for _, id := range a.store.LoadSyncedYtCollections() {
			savedSet[id] = true
		}

		localPlNames := map[string]bool{}
		if pls, err := a.store.Playlists(); err == nil {
			for _, p := range pls {
				localPlNames[strings.ToLower(strings.TrimSpace(p.Name))] = true
			}
		}

		var toSync []YtMusicCollection
		if len(savedSet) == 0 {
			toSync = allCols
		} else {
			for _, col := range allCols {
				if col.Type == "LIKED_MUSIC" || savedSet[col.ID] || localPlNames[strings.ToLower(strings.TrimSpace(col.Title))] {
					toSync = append(toSync, col)
				}
			}
			if len(toSync) == 0 {
				toSync = allCols
			}
		}

		a.ytmMu.Lock()
		a.ytmSyncing = false
		a.ytmMu.Unlock()
		if msg := a.ImportYtMusicCollections(toSync); msg != "" {
			a.setYtmState(false, msg)
			a.emit("app:toast", msg)
		}
	}()

	return ""
}
