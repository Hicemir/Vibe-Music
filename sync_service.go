package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type syncSession struct {
	id            string
	peerDeviceID  string
	peerName      string
	peerPlatform  string
	peerPubSpki   string
	peerPubKey    *ecdsa.PublicKey
	sessionKey    []byte
	authKey       []byte
	transcript    string
	authenticated bool
	seenNonces    map[string]int64
	lastActivity  time.Time
	mu            sync.Mutex
}

type pendingPairEntry struct {
	pairID         string
	initDeviceID   string
	initDeviceName string
	initPlatform   string
	initPubSpki    string
	initPubKey     *ecdsa.PublicKey
	transcript     string
	sessionKey     []byte
	authKey        []byte
	pin            string
	status         string // pending | accepted | declined | expired
	sessionID      string
	trustPeer      bool
	createdAt      time.Time
}

type SyncManager struct {
	app      *App
	mu       sync.Mutex
	identity *SyncIdentity
	disc     *syncDiscovery

	httpLn   net.Listener
	httpSrv  *http.Server
	httpPort int

	discoveryActive bool
	syncBusy        bool
	activeCancel    context.CancelFunc
	status          SyncStatus

	sessions      map[string]*syncSession
	pendingPair   *pendingPairEntry
	pairCh        chan struct{}
	lastDeclined  time.Time
	reverseNotify chan string
}

func NewSyncManager(app *App) *SyncManager {
	id, _ := loadOrCreateSyncIdentity(app.store)
	m := &SyncManager{
		app:           app,
		identity:      id,
		sessions:      map[string]*syncSession{},
		reverseNotify: make(chan string, 4),
		status:        SyncStatus{State: "idle"},
	}
	m.disc = newSyncDiscovery(m)
	return m
}

func (m *SyncManager) identitySnapshot() SyncIdentity {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.identity == nil {
		id, _ := loadOrCreateSyncIdentity(m.app.store)
		m.identity = id
	}
	if m.identity == nil {
		return SyncIdentity{}
	}
	return *m.identity
}

func (m *SyncManager) selfInfo() SyncSelfInfo {
	id := m.identitySnapshot()
	tracks, _ := m.app.store.AllTracksIncludeHidden()
	pls, _ := m.app.store.Playlists()
	ips, _ := localIPv4sAndBroadcasts()
	m.mu.Lock()
	port := m.httpPort
	m.mu.Unlock()
	return SyncSelfInfo{
		DeviceID:      id.DeviceID,
		DeviceName:    m.app.store.GetSyncDeviceName(),
		Platform:      runtime.GOOS,
		Port:          port,
		LocalIPs:      ips,
		TrackCount:    len(tracks),
		PlaylistCount: len(pls),
	}
}

func (m *SyncManager) setStatus(st SyncStatus) {
	m.mu.Lock()
	m.status = st
	m.mu.Unlock()
	m.app.emit("sync:status", st)
}

func (m *SyncManager) getStatus() SyncStatus {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.status
}

func (m *SyncManager) emitPeers() {
	m.app.emit("sync:peers", m.disc.Peers())
}

func (m *SyncManager) currentPrompt() *SyncPairPrompt {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.pendingPair == nil || m.pendingPair.status != "pending" {
		return nil
	}
	p := m.pendingPair
	return &SyncPairPrompt{
		PairID:         p.pairID,
		DeviceID:       p.initDeviceID,
		DeviceName:     p.initDeviceName,
		Platform:       p.initPlatform,
		Pin:            p.pin,
		TrustedDefault: true,
		Incoming:       true,
	}
}

func (m *SyncManager) State() SyncStateResponse {
	return SyncStateResponse{
		Self:   m.selfInfo(),
		Status: m.getStatus(),
		Peers:  m.disc.Peers(),
		Prompt: m.currentPrompt(),
	}
}

func (m *SyncManager) StartDiscovery() error {
	m.mu.Lock()
	m.discoveryActive = true
	needServer := m.httpSrv == nil
	m.mu.Unlock()

	if needServer {
		if err := m.startHTTPServer(); err != nil {
			return err
		}
	}
	if err := m.disc.Start(); err != nil {
		return err
	}
	st := m.getStatus()
	if st.State == "idle" {
		m.setStatus(SyncStatus{State: "discovering", Phase: "Listening for nearby VibeMusic devices…"})
	}
	m.app.emit("sync:state", m.State())
	return nil
}

func (m *SyncManager) StopDiscovery() {
	m.mu.Lock()
	m.discoveryActive = false
	busy := m.syncBusy
	m.mu.Unlock()

	m.disc.Stop()
	if !busy {
		m.stopHTTPServer()
		m.setStatus(SyncStatus{State: "idle"})
	}
}

func (m *SyncManager) Shutdown() {
	m.CancelSync()
	m.disc.Stop()
	m.stopHTTPServer()
}

func (m *SyncManager) CancelSync() {
	m.mu.Lock()
	cancel := m.activeCancel
	if m.pendingPair != nil && m.pendingPair.status == "pending" {
		m.pendingPair.status = "declined"
		if m.pairCh != nil {
			close(m.pairCh)
			m.pairCh = nil
		}
	}
	m.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (m *SyncManager) startHTTPServer() error {
	ln, err := net.Listen("tcp4", fmt.Sprintf(":%d", syncDefaultPort))
	if err != nil {
		ln, err = net.Listen("tcp4", ":0")
		if err != nil {
			return err
		}
	}
	tcpAddr, _ := ln.Addr().(*net.TCPAddr)
	port := syncDefaultPort
	if tcpAddr != nil {
		port = tcpAddr.Port
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/sync/v1/", m.handleHTTP)
	srv := &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 15 * time.Second,
	}

	m.mu.Lock()
	m.httpLn = ln
	m.httpSrv = srv
	m.httpPort = port
	m.mu.Unlock()

	go func() {
		_ = srv.Serve(ln)
	}()
	return nil
}

func (m *SyncManager) stopHTTPServer() {
	m.mu.Lock()
	srv := m.httpSrv
	ln := m.httpLn
	m.httpSrv = nil
	m.httpLn = nil
	m.httpPort = 0
	m.sessions = map[string]*syncSession{}
	m.mu.Unlock()

	if srv != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_ = srv.Shutdown(ctx)
		cancel()
	}
	if ln != nil {
		_ = ln.Close()
	}
}

// ---------- manual IP probe ----------

func (m *SyncManager) ConnectManualIP(ipPort string) (SyncPeer, error) {
	ipPort = strings.TrimSpace(ipPort)
	if ipPort == "" {
		return SyncPeer{}, errors.New("enter an IP address")
	}
	if !strings.Contains(ipPort, ":") {
		ipPort = fmt.Sprintf("%s:%d", ipPort, syncDefaultPort)
	}
	host, portStr, err := net.SplitHostPort(ipPort)
	if err != nil {
		return SyncPeer{}, fmt.Errorf("invalid address %q", ipPort)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port <= 0 || port > 65535 {
		return SyncPeer{}, fmt.Errorf("invalid port %q", portStr)
	}
	info, err := m.fetchPeerInfo(context.Background(), host, port)
	if err != nil {
		return SyncPeer{}, err
	}
	if info.DeviceID == m.identitySnapshot().DeviceID {
		return SyncPeer{}, errors.New("cannot connect to this device")
	}
	if _, err := verifyDeviceIDAndKey(info.DeviceID, info.PubKeySpkiB64); err != nil {
		return SyncPeer{}, err
	}
	_, trusted := m.app.store.GetTrustedSyncPeer(info.DeviceID)
	peer := SyncPeer{
		DeviceID:      info.DeviceID,
		DeviceName:    info.DeviceName,
		Platform:      info.Platform,
		IP:            host,
		Port:          port,
		TrackCount:    info.TrackCount,
		PlaylistCount: info.PlaylistCount,
		PubKeySpkiB64: info.PubKeySpkiB64,
		Trusted:       trusted,
		LastSeen:      time.Now().Unix(),
	}
	m.disc.UpsertManualPeer(peer)
	return peer, nil
}

func (m *SyncManager) fetchPeerInfo(ctx context.Context, ip string, port int) (syncInfoResponse, error) {
	cli := &http.Client{Timeout: 2500 * time.Millisecond}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, fmt.Sprintf("http://%s:%d/api/sync/v1/info", ip, port), nil)
	if err != nil {
		return syncInfoResponse{}, err
	}
	resp, err := cli.Do(req)
	if err != nil {
		return syncInfoResponse{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return syncInfoResponse{}, fmt.Errorf("peer returned %d", resp.StatusCode)
	}
	var out syncInfoResponse
	if err := json.NewDecoder(io.LimitReader(resp.Body, 16*1024)).Decode(&out); err != nil {
		return syncInfoResponse{}, err
	}
	return out, nil
}

// ---------- pair UI response ----------

func (m *SyncManager) RespondPair(pairID string, accept bool, trust bool) {
	m.mu.Lock()
	p := m.pendingPair
	if p == nil || p.pairID != pairID || p.status != "pending" {
		m.mu.Unlock()
		return
	}
	if accept {
		p.status = "accepted"
		p.trustPeer = trust
		sessID := randomHex(16)
		p.sessionID = sessID
		m.sessions[sessID] = &syncSession{
			id:           sessID,
			peerDeviceID: p.initDeviceID,
			peerName:     p.initDeviceName,
			peerPlatform: p.initPlatform,
			peerPubSpki:  p.initPubSpki,
			peerPubKey:   p.initPubKey,
			sessionKey:   p.sessionKey,
			authKey:      p.authKey,
			transcript:   p.transcript,
			seenNonces:   map[string]int64{},
			lastActivity: time.Now(),
		}
	} else {
		p.status = "declined"
		m.lastDeclined = time.Now()
	}
	ch := m.pairCh
	m.pairCh = nil
	m.mu.Unlock()

	if ch != nil {
		close(ch)
	}
	m.app.emit("sync:pair-prompt", nil)
}

// ---------- incoming HTTP server handlers ----------

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func (m *SyncManager) handleHTTP(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	switch {
	case path == "/api/sync/v1/info" && r.Method == http.MethodGet:
		m.handleInfo(w, r)
	case path == "/api/sync/v1/auth-init" && r.Method == http.MethodPost:
		m.handleAuthInit(w, r)
	case path == "/api/sync/v1/auth-finish" && r.Method == http.MethodPost:
		m.handleAuthFinish(w, r)
	case path == "/api/sync/v1/pair-init" && r.Method == http.MethodPost:
		m.handlePairInit(w, r)
	case path == "/api/sync/v1/pair-status" && r.Method == http.MethodPost:
		m.handlePairStatus(w, r)
	case path == "/api/sync/v1/manifest" && r.Method == http.MethodPost:
		m.handleManifest(w, r)
	case strings.HasPrefix(path, "/api/sync/v1/file/"):
		m.handleFile(w, r)
	case path == "/api/sync/v1/progress" && r.Method == http.MethodPost:
		m.handleProgressPing(w, r)
	case path == "/api/sync/v1/commit" && r.Method == http.MethodPost:
		m.handleCommit(w, r)
	default:
		http.NotFound(w, r)
	}
}

func (m *SyncManager) handleInfo(w http.ResponseWriter, _ *http.Request) {
	info := m.selfInfo()
	id := m.identitySnapshot()
	writeJSON(w, http.StatusOK, syncInfoResponse{
		ProtocolVersion: syncProtocolVersion,
		DeviceID:        id.DeviceID,
		DeviceName:      info.DeviceName,
		Platform:        info.Platform,
		Port:            info.Port,
		TrackCount:      info.TrackCount,
		PlaylistCount:   info.PlaylistCount,
		PubKeySpkiB64:   id.PubSpkiB64,
	})
}

func (m *SyncManager) handleAuthInit(w http.ResponseWriter, r *http.Request) {
	var req syncAuthInitRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 32*1024)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad json"})
		return
	}
	peerPub, err := verifyDeviceIDAndKey(req.InitDeviceID, req.InitPubSpkiB64)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	tp, ok := m.app.store.GetTrustedSyncPeer(req.InitDeviceID)
	if !ok || tp.PubKeySpkiB64 != req.InitPubSpkiB64 {
		writeJSON(w, http.StatusForbidden, map[string]string{"error": "untrusted"})
		return
	}

	ephPriv, ephSpkiB64, err := generateEphemeralKey()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "keygen failed"})
		return
	}
	shared, err := computeECDHSharedSecret(ephPriv, req.InitEphSpkiB64)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad ephemeral key"})
		return
	}
	respNonceHex := randomHex(32)
	sessionKey, authKey, err := deriveSessionKeys(shared, req.InitNonceHex, respNonceHex)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	selfID := m.identitySnapshot()
	selfName := m.app.store.GetSyncDeviceName()
	transcript := buildSyncTranscript(req.InitDeviceID, selfID.DeviceID, req.InitPubSpkiB64, selfID.PubSpkiB64,
		req.InitEphSpkiB64, ephSpkiB64, req.InitNonceHex, respNonceHex)
	respSig, err := signMessage(selfID.Priv, []byte("resp-sig|"+transcript))
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "sign failed"})
		return
	}
	sessID := randomHex(16)
	m.mu.Lock()
	m.sessions[sessID] = &syncSession{
		id:           sessID,
		peerDeviceID: req.InitDeviceID,
		peerName:     req.InitDeviceName,
		peerPlatform: req.InitPlatform,
		peerPubSpki:  req.InitPubSpkiB64,
		peerPubKey:   peerPub,
		sessionKey:   sessionKey,
		authKey:      authKey,
		transcript:   transcript,
		seenNonces:   map[string]int64{},
		lastActivity: time.Now(),
	}
	m.mu.Unlock()

	writeJSON(w, http.StatusOK, syncAuthInitResponse{
		SessionID:      sessID,
		RespDeviceID:   selfID.DeviceID,
		RespDeviceName: selfName,
		RespPubSpkiB64: selfID.PubSpkiB64,
		RespEphSpkiB64: ephSpkiB64,
		RespNonceHex:   respNonceHex,
		RespSigB64:     respSig,
	})
}

func (m *SyncManager) handleAuthFinish(w http.ResponseWriter, r *http.Request) {
	var req syncAuthFinishRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 16*1024)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad json"})
		return
	}
	m.mu.Lock()
	sess, ok := m.sessions[req.SessionID]
	m.mu.Unlock()
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unknown session"})
		return
	}
	msg := []byte("init-sig|" + sess.id + "|" + sess.transcript)
	if !verifySignature(sess.peerPubKey, msg, req.InitSigB64) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid signature"})
		return
	}
	sess.mu.Lock()
	sess.authenticated = true
	sess.lastActivity = time.Now()
	sess.mu.Unlock()

	// Refresh trusted peer display name if updated
	_ = m.app.store.UpsertTrustedSyncPeer(TrustedSyncPeer{
		DeviceID:      sess.peerDeviceID,
		DeviceName:    sess.peerName,
		Platform:      sess.peerPlatform,
		PubKeySpkiB64: sess.peerPubSpki,
	})
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (m *SyncManager) handlePairInit(w http.ResponseWriter, r *http.Request) {
	var req syncPairInitRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 32*1024)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad json"})
		return
	}
	peerPub, err := verifyDeviceIDAndKey(req.InitDeviceID, req.InitPubSpkiB64)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}

	m.mu.Lock()
	if time.Since(m.lastDeclined) < 3*time.Second {
		m.mu.Unlock()
		writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "cooldown"})
		return
	}
	if m.pendingPair != nil && m.pendingPair.status == "pending" && time.Since(m.pendingPair.createdAt) < 60*time.Second {
		m.mu.Unlock()
		writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "pair request already active"})
		return
	}
	m.mu.Unlock()

	ephPriv, ephSpkiB64, err := generateEphemeralKey()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "keygen failed"})
		return
	}
	shared, err := computeECDHSharedSecret(ephPriv, req.InitEphSpkiB64)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad ephemeral key"})
		return
	}
	respNonceHex := randomHex(32)
	sessionKey, authKey, err := deriveSessionKeys(shared, req.InitNonceHex, respNonceHex)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	selfID := m.identitySnapshot()
	selfName := m.app.store.GetSyncDeviceName()
	transcript := buildSyncTranscript(req.InitDeviceID, selfID.DeviceID, req.InitPubSpkiB64, selfID.PubSpkiB64,
		req.InitEphSpkiB64, ephSpkiB64, req.InitNonceHex, respNonceHex)
	respSig, err := signMessage(selfID.Priv, []byte("resp-sig|"+transcript))
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "sign failed"})
		return
	}
	pairID := randomHex(16)
	pin := computeSasPin(transcript)

	entry := &pendingPairEntry{
		pairID:         pairID,
		initDeviceID:   req.InitDeviceID,
		initDeviceName: req.InitDeviceName,
		initPlatform:   req.InitPlatform,
		initPubSpki:    req.InitPubSpkiB64,
		initPubKey:     peerPub,
		transcript:     transcript,
		sessionKey:     sessionKey,
		authKey:        authKey,
		pin:            pin,
		status:         "pending",
		createdAt:      time.Now(),
	}

	m.mu.Lock()
	m.pendingPair = entry
	m.pairCh = make(chan struct{})
	m.mu.Unlock()

	m.app.emit("sync:pair-prompt", SyncPairPrompt{
		PairID:         pairID,
		DeviceID:       req.InitDeviceID,
		DeviceName:     req.InitDeviceName,
		Platform:       req.InitPlatform,
		TrackCount:     req.InitTrackCount,
		Pin:            pin,
		TrustedDefault: true,
		Incoming:       true,
	})

	writeJSON(w, http.StatusOK, syncPairInitResponse{
		PairID:         pairID,
		RespDeviceID:   selfID.DeviceID,
		RespDeviceName: selfName,
		RespPlatform:   runtime.GOOS,
		RespPubSpkiB64: selfID.PubSpkiB64,
		RespEphSpkiB64: ephSpkiB64,
		RespNonceHex:   respNonceHex,
		RespSigB64:     respSig,
	})
}

func (m *SyncManager) handlePairStatus(w http.ResponseWriter, r *http.Request) {
	var req syncPairStatusRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 16*1024)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad json"})
		return
	}
	m.mu.Lock()
	p := m.pendingPair
	if p == nil || p.pairID != req.PairID {
		m.mu.Unlock()
		writeJSON(w, http.StatusOK, syncPairStatusResponse{Status: "expired"})
		return
	}
	if p.status == "pending" && time.Since(p.createdAt) > 60*time.Second {
		p.status = "expired"
		m.lastDeclined = time.Now()
		m.mu.Unlock()
		m.app.emit("sync:pair-prompt", nil)
		writeJSON(w, http.StatusOK, syncPairStatusResponse{Status: "expired"})
		return
	}
	status := p.status
	sessID := p.sessionID
	trustResp := p.trustPeer
	m.mu.Unlock()

	if status != "accepted" {
		writeJSON(w, http.StatusOK, syncPairStatusResponse{Status: status})
		return
	}
	// Verify initiator owns its identity private key
	msg := []byte("init-sig|" + p.pairID + "|" + p.transcript)
	if !verifySignature(p.initPubKey, msg, req.InitSigB64) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid signature"})
		return
	}
	m.mu.Lock()
	sess := m.sessions[sessID]
	m.mu.Unlock()
	if sess != nil {
		sess.mu.Lock()
		sess.authenticated = true
		sess.lastActivity = time.Now()
		sess.mu.Unlock()
	}
	if trustResp && req.TrustPeer {
		_ = m.app.store.UpsertTrustedSyncPeer(TrustedSyncPeer{
			DeviceID:      p.initDeviceID,
			DeviceName:    p.initDeviceName,
			Platform:      p.initPlatform,
			PubKeySpkiB64: p.initPubSpki,
			PairedAt:      time.Now().Unix(),
		})
		m.emitPeers()
	}
	writeJSON(w, http.StatusOK, syncPairStatusResponse{
		Status:           "accepted",
		SessionID:        sessID,
		ResponderTrusted: trustResp,
	})
}

func (m *SyncManager) authenticateRequest(r *http.Request) (*syncSession, bool) {
	sessID := r.Header.Get("X-Sync-Session")
	nonce := r.Header.Get("X-Sync-Nonce")
	macHex := r.Header.Get("X-Sync-Mac")
	if sessID == "" || nonce == "" || macHex == "" {
		return nil, false
	}
	tsStr, _, ok := strings.Cut(nonce, "-")
	if !ok {
		return nil, false
	}
	ts, err := strconv.ParseInt(tsStr, 10, 64)
	if err != nil {
		return nil, false
	}
	now := time.Now().Unix()
	if now-ts > 300 || ts-now > 300 {
		return nil, false
	}
	m.mu.Lock()
	sess, ok := m.sessions[sessID]
	m.mu.Unlock()
	if !ok {
		return nil, false
	}
	sess.mu.Lock()
	defer sess.mu.Unlock()
	if !sess.authenticated {
		return nil, false
	}
	if _, replayed := sess.seenNonces[nonce]; replayed {
		return nil, false
	}
	if !verifyRequestMAC(sess.authKey, r.Method, r.URL.RequestURI(), sessID, nonce, macHex) {
		return nil, false
	}
	sess.seenNonces[nonce] = now
	sess.lastActivity = time.Now()
	return sess, true
}

func (m *SyncManager) handleManifest(w http.ResponseWriter, r *http.Request) {
	sess, ok := m.authenticateRequest(r)
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 64*1024*1024))
	if err != nil {
		http.Error(w, "read failed", http.StatusBadRequest)
		return
	}
	plain, err := decryptSingleBuffer(sess.sessionKey, body)
	if err != nil {
		http.Error(w, "decrypt failed", http.StatusBadRequest)
		return
	}
	var peerManifest SyncManifest
	if err := json.Unmarshal(plain, &peerManifest); err != nil {
		http.Error(w, "invalid manifest", http.StatusBadRequest)
		return
	}

	m.app.scanner.SetPaused(true)
	select {
	case m.reverseNotify <- sess.peerDeviceID:
	default:
	}
	m.setStatus(SyncStatus{
		State:     "syncing",
		Phase:     "Comparing libraries with " + sess.peerName + "…",
		PeerID:    sess.peerDeviceID,
		PeerName:  sess.peerName,
		Direction: "incoming",
	})

	id := m.identitySnapshot()
	localManifest, err := m.app.store.BuildSyncManifest(id.DeviceID, m.app.store.GetSyncDeviceName(), runtime.GOOS, m.app.scanDirs())
	if err != nil {
		m.app.scanner.SetPaused(false)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	respJSON, err := json.Marshal(localManifest)
	if err != nil {
		m.app.scanner.SetPaused(false)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	sealed, err := encryptSingleBuffer(sess.sessionKey, respJSON)
	if err != nil {
		m.app.scanner.SetPaused(false)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	_, _ = w.Write(sealed)
}

func (m *SyncManager) handleProgressPing(w http.ResponseWriter, r *http.Request) {
	sess, ok := m.authenticateRequest(r)
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 64*1024))
	if err != nil {
		http.Error(w, "bad body", http.StatusBadRequest)
		return
	}
	plain, err := decryptSingleBuffer(sess.sessionKey, body)
	if err != nil {
		http.Error(w, "decrypt failed", http.StatusBadRequest)
		return
	}
	var p syncProgressPing
	if json.Unmarshal(plain, &p) == nil {
		m.setStatus(SyncStatus{
			State:            "syncing",
			Phase:            p.Phase,
			PeerID:           sess.peerDeviceID,
			PeerName:         sess.peerName,
			Direction:        "incoming",
			Percent:          p.Percent,
			CurrentFile:      p.CurrentFile,
			TransferredBytes: p.TransferredBytes,
			TotalBytes:       p.TotalBytes,
			FilesDone:        p.FilesDone,
			FilesTotal:       p.FilesTotal,
			SpeedBps:         p.SpeedBps,
		})
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (m *SyncManager) handleFile(w http.ResponseWriter, r *http.Request) {
	sess, ok := m.authenticateRequest(r)
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	rest := strings.TrimPrefix(r.URL.Path, "/api/sync/v1/file/")
	kind, idB64, found := strings.Cut(rest, "/")
	if !found || idB64 == "" {
		http.Error(w, "bad path", http.StatusBadRequest)
		return
	}
	rawID, err := base64.RawURLEncoding.DecodeString(idB64)
	if err != nil || len(rawID) == 0 {
		http.Error(w, "bad track id", http.StatusBadRequest)
		return
	}
	trackID := string(rawID)

	if r.Method == http.MethodGet {
		m.serveEncryptedFile(w, sess, kind, trackID)
		return
	}
	if r.Method == http.MethodPut {
		m.receiveEncryptedFile(w, r, sess, kind, trackID)
		return
	}
	http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
}

func (m *SyncManager) serveEncryptedFile(w http.ResponseWriter, sess *syncSession, kind, trackID string) {
	var filePath string
	var ext string
	switch kind {
	case "audio":
		t, ok := m.app.store.TrackByID(trackID)
		if !ok || t.Path == "" {
			http.NotFound(w, nil)
			return
		}
		filePath = t.Path
		ext = filepath.Ext(t.Path)
	case "cover":
		p := coverPath(trackID)
		if !fileExists(p) {
			http.NotFound(w, nil)
			return
		}
		filePath = p
		ext = ".jpg"
	case "lyrics":
		t, ok := m.app.store.TrackByID(trackID)
		if !ok || t.Path == "" {
			http.NotFound(w, nil)
			return
		}
		p := sidecarLrcPath(t.Path)
		if p == "" {
			http.NotFound(w, nil)
			return
		}
		filePath = p
		ext = ".lrc"
	default:
		http.Error(w, "unknown kind", http.StatusBadRequest)
		return
	}

	f, err := os.Open(filePath)
	if err != nil {
		http.NotFound(w, nil)
		return
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		http.NotFound(w, nil)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(encryptedStreamSize(fi.Size()), 10))
	w.Header().Set("X-Track-Ext", ext)
	w.Header().Set("X-Plain-Size", strconv.FormatInt(fi.Size(), 10))
	_ = writeEncryptedStream(w, sess.sessionKey, f, nil)
}

func (m *SyncManager) receiveEncryptedFile(w http.ResponseWriter, r *http.Request, sess *syncSession, kind, trackID string) {
	plainSize, _ := strconv.ParseInt(r.Header.Get("X-Plain-Size"), 10, 64)
	if plainSize < 0 {
		plainSize = -1
	}

	switch kind {
	case "audio":
		metaB64 := r.Header.Get("X-Track-Meta")
		if metaB64 == "" {
			http.Error(w, "missing track meta", http.StatusBadRequest)
			return
		}
		sealedMeta, err := base64.StdEncoding.DecodeString(metaB64)
		if err != nil {
			http.Error(w, "bad meta encoding", http.StatusBadRequest)
			return
		}
		plainMeta, err := decryptSingleBuffer(sess.sessionKey, sealedMeta)
		if err != nil {
			http.Error(w, "meta decrypt failed", http.StatusBadRequest)
			return
		}
		var meta SyncTrack
		if err := json.Unmarshal(plainMeta, &meta); err != nil {
			http.Error(w, "bad meta json", http.StatusBadRequest)
			return
		}
		meta.ID = trackID
		ext := r.Header.Get("X-Track-Ext")
		if err := m.saveIncomingAudio(r.Body, sess.sessionKey, meta, ext, plainSize, nil); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})

	case "cover":
		if err := m.saveIncomingCover(r.Body, sess.sessionKey, trackID, plainSize); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})

	case "lyrics":
		if err := m.saveIncomingLyrics(r.Body, sess.sessionKey, trackID, plainSize); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})

	default:
		http.Error(w, "unknown kind", http.StatusBadRequest)
	}
}

func (m *SyncManager) handleCommit(w http.ResponseWriter, r *http.Request) {
	sess, ok := m.authenticateRequest(r)
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 64*1024*1024))
	if err != nil {
		http.Error(w, "read failed", http.StatusBadRequest)
		return
	}
	plain, err := decryptSingleBuffer(sess.sessionKey, body)
	if err != nil {
		http.Error(w, "decrypt failed", http.StatusBadRequest)
		return
	}
	var payload SyncCommitPayload
	if err := json.Unmarshal(plain, &payload); err != nil {
		http.Error(w, "invalid commit payload", http.StatusBadRequest)
		return
	}
	if err := m.app.store.ApplySyncCommit(payload, sess.peerDeviceID, &payload.NewSnapshot); err != nil {
		m.app.scanner.SetPaused(false)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	m.app.scanner.SetPaused(false)
	m.app.reloadTracks()
	m.app.emit("library:changed")
	m.app.emit("playlists:changed")
	m.emitPeers()

	msg := fmt.Sprintf("Sync with %s complete (%d tracks, %d playlists)", sess.peerName, len(payload.TracksMeta), len(payload.Playlists))
	m.setStatus(SyncStatus{
		State:           "done",
		Phase:           "Sync complete",
		PeerID:          sess.peerDeviceID,
		PeerName:        sess.peerName,
		Direction:       "incoming",
		Percent:         100,
		DeletedTracks:   len(payload.DeletedTrackIDs),
		SyncedPlaylists: len(payload.Playlists),
		Message:         msg,
	})
	m.app.emit("app:toast", msg)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// ---------- safe file ingestion helpers ----------

func sanitizeIncomingRelPath(relPath, ytID, ext, fallbackArtist, fallbackTitle string) string {
	if ext == "" {
		ext = ".m4a"
	}
	if !strings.HasPrefix(ext, ".") {
		ext = "." + ext
	}
	rel, ok := cleanRelPath(relPath)
	if !ok {
		name := strings.TrimSpace(fallbackArtist + " - " + fallbackTitle)
		if name == "" || name == "-" {
			name = "Synced Track"
		}
		name = strings.Map(func(r rune) rune {
			switch r {
			case '/', '\\', ':', '*', '?', '"', '<', '>', '|':
				return '_'
			default:
				return r
			}
		}, name)
		rel = name + ext
	}
	if ytID != "" && ytIDFromName(rel) == "" {
		curExt := filepath.Ext(rel)
		if curExt == "" {
			curExt = ext
		}
		stem := strings.TrimSuffix(rel, curExt)
		rel = fmt.Sprintf("%s [%s]%s", stem, ytID, curExt)
	}
	return rel
}

func (m *SyncManager) primaryDestDir() string {
	set := m.app.Settings()
	dest := strings.TrimSpace(set.DownloadDir)
	if dest == "" && len(set.MusicDirs) > 0 {
		dest = set.MusicDirs[0]
	}
	if dest == "" {
		dest = defaultSettings().DownloadDir
	}
	_ = os.MkdirAll(dest, 0o755)
	return dest
}

func (m *SyncManager) saveIncomingAudio(r io.Reader, sessionKey []byte, meta SyncTrack, ext string, expectedSize int64, onBytes func(int64)) error {
	destDir := m.primaryDestDir()
	if expectedSize > 0 {
		if avail, ok := freeDiskBytes(destDir); ok && avail < uint64(expectedSize)+64*1024*1024 {
			return fmt.Errorf("insufficient disk space in %s", destDir)
		}
	}
	rel := sanitizeIncomingRelPath(meta.RelPath, meta.YtID, ext, meta.Artist, meta.Title)
	target := filepath.Join(destDir, filepath.FromSlash(rel))
	if fi, err := os.Stat(target); err == nil {
		if existing, ok := m.app.store.TrackByPath(target); ok && existing.ID != meta.ID {
			target = uniqueTarget(target)
			if r2, err := filepath.Rel(destDir, target); err == nil {
				if c, ok2 := cleanRelPath(filepath.ToSlash(r2)); ok2 {
					rel = c
				}
			}
		} else if !ok && fi.Size() != expectedSize {
			target = uniqueTarget(target)
			if r2, err := filepath.Rel(destDir, target); err == nil {
				if c, ok2 := cleanRelPath(filepath.ToSlash(r2)); ok2 {
					rel = c
				}
			}
		}
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return err
	}
	part := target + ".vmsync.part"
	f, err := os.Create(part)
	if err != nil {
		return err
	}
	written, err := readEncryptedStream(r, sessionKey, f, expectedSize, onBytes)
	cErr := f.Close()
	if err != nil || cErr != nil {
		_ = os.Remove(part)
		if err != nil {
			return err
		}
		return cErr
	}
	if strings.HasPrefix(meta.ID, "local:") {
		computed, herr := localContentID(part, written)
		if herr != nil || computed != meta.ID {
			_ = os.Remove(part)
			return fmt.Errorf("content hash verification failed for %s", meta.Title)
		}
	}
	if meta.MTimeUnix > 0 {
		mt := time.Unix(meta.MTimeUnix, 0)
		_ = os.Chtimes(part, mt, mt)
	}
	if err := os.Rename(part, target); err != nil {
		_ = os.Remove(part)
		return err
	}
	fi, _ := os.Stat(target)
	mtime := meta.MTimeUnix
	if fi != nil {
		mtime = fi.ModTime().Unix()
	}
	rootKey := m.app.store.EnsureRoot(destDir)
	effectiveYtID := meta.YtID
	if effectiveYtID != "" {
		var ownerID string
		if m.app.store.db.QueryRow(`SELECT id FROM tracks WHERE yt_id=? AND id<>? LIMIT 1`, effectiveYtID, meta.ID).Scan(&ownerID) == nil && ownerID != "" {
			effectiveYtID = ""
		}
	}
	tr := Track{
		ID:          meta.ID,
		YtID:        effectiveYtID,
		RootKey:     rootKey,
		RelPath:     rel,
		Title:       meta.Title,
		Artist:      meta.Artist,
		Album:       meta.Album,
		Duration:    meta.Duration,
		Year:        meta.Year,
		AddedAt:     meta.AddedAt,
		Liked:       meta.Liked,
		PlayCount:   meta.PlayCount,
		LastPlay:    meta.LastPlayed,
		HasCover:    fileExists(coverPath(meta.ID)) || meta.HasCover,
		Format:      meta.Format,
		Bitrate:     meta.Bitrate,
		Size:        written,
		MTime:       mtime,
		UpdatedAt:   meta.UpdatedAt,
		Genre:       meta.Genre,
		GenreSource: meta.GenreSource,
	}
	_, err = m.app.store.UpsertTrack(tr)
	return err
}

func (m *SyncManager) saveIncomingCover(r io.Reader, sessionKey []byte, trackID string, expectedSize int64) error {
	dst := coverPath(trackID)
	_ = os.MkdirAll(filepath.Dir(dst), 0o755)
	part := dst + ".vmsync.part"
	f, err := os.Create(part)
	if err != nil {
		return err
	}
	_, err = readEncryptedStream(r, sessionKey, f, expectedSize, nil)
	cErr := f.Close()
	if err != nil || cErr != nil {
		_ = os.Remove(part)
		if err != nil {
			return err
		}
		return cErr
	}
	if err := os.Rename(part, dst); err != nil {
		_ = os.Remove(part)
		return err
	}
	key := coverKey(trackID) + ".jpg"
	for _, sub := range []string{"128", "300", "480"} {
		_ = os.Remove(filepath.Join(coverDir(), "thumbs", "v2", sub, key))
	}
	return nil
}

func (m *SyncManager) saveIncomingLyrics(r io.Reader, sessionKey []byte, trackID string, expectedSize int64) error {
	t, ok := m.app.store.TrackByID(trackID)
	if !ok || t.Path == "" {
		return errors.New("track missing for lyrics")
	}
	dst := strings.TrimSuffix(t.Path, filepath.Ext(t.Path)) + ".lrc"
	part := dst + ".vmsync.part"
	f, err := os.Create(part)
	if err != nil {
		return err
	}
	_, err = readEncryptedStream(r, sessionKey, f, expectedSize, nil)
	cErr := f.Close()
	if err != nil || cErr != nil {
		_ = os.Remove(part)
		if err != nil {
			return err
		}
		return cErr
	}
	if err := os.Rename(part, dst); err != nil {
		_ = os.Remove(part)
		return err
	}
	return nil
}

// ---------- client outbound sync driver ----------

func (m *SyncManager) handleReverseRequest(remoteIP string, pkt syncDiscoveryPacket) {
	if _, ok := m.app.store.GetTrustedSyncPeer(pkt.DeviceID); !ok {
		return
	}
	m.mu.Lock()
	active := m.discoveryActive
	busy := m.syncBusy
	m.mu.Unlock()
	if !active || busy {
		return
	}
	m.disc.UpsertManualPeer(SyncPeer{
		DeviceID:      pkt.DeviceID,
		DeviceName:    pkt.DeviceName,
		Platform:      pkt.Platform,
		IP:            remoteIP,
		Port:          pkt.Port,
		TrackCount:    pkt.TrackCount,
		PlaylistCount: pkt.PlaylistCount,
		PubKeySpkiB64: pkt.PubKeySpkiB64,
		Trusted:       true,
	})
	_ = m.StartPeerSync(pkt.DeviceID, pkt.SyncDeletions)
}

func (m *SyncManager) StartPeerSync(deviceID string, syncDeletions bool) string {
	var target SyncPeer
	found := false
	for _, p := range m.disc.Peers() {
		if p.DeviceID == deviceID {
			target = p
			found = true
			break
		}
	}
	if !found {
		return "Device is no longer visible on the local network"
	}

	m.mu.Lock()
	if m.syncBusy {
		m.mu.Unlock()
		return "A sync is already in progress"
	}
	m.syncBusy = true
	ctx, cancel := context.WithCancel(context.Background())
	m.activeCancel = cancel
	m.mu.Unlock()

	go func() {
		defer func() {
			cancel()
			m.app.scanner.SetPaused(false)
			m.mu.Lock()
			m.syncBusy = false
			m.activeCancel = nil
			m.mu.Unlock()
		}()
		if err := m.runOutboundSync(ctx, target, syncDeletions); err != nil {
			if ctx.Err() != nil {
				m.setStatus(SyncStatus{State: "idle", Phase: "Cancelled", Message: "Sync cancelled"})
			} else {
				m.setStatus(SyncStatus{
					State:    "error",
					Phase:    "Sync failed",
					PeerID:   target.DeviceID,
					PeerName: target.DeviceName,
					Error:    err.Error(),
				})
				m.app.emit("app:toast", "Sync failed: "+err.Error())
			}
		}
	}()
	return ""
}

func (m *SyncManager) runOutboundSync(ctx context.Context, peer SyncPeer, syncDeletions bool) error {
	m.setStatus(SyncStatus{
		State:     "pairing",
		Phase:     "Connecting to " + peer.DeviceName + "…",
		PeerID:    peer.DeviceID,
		PeerName:  peer.DeviceName,
		Direction: "outgoing",
	})

	// Check reachability; if peer's firewall blocks inbound TCP, send UDP reverse_request and wait briefly
	if _, err := m.fetchPeerInfo(ctx, peer.IP, peer.Port); err != nil {
		// Drain any stale notifications
	drainLoop:
		for {
			select {
			case <-m.reverseNotify:
			default:
				break drainLoop
			}
		}
		m.disc.SendReverseRequest(peer.IP, peer.DeviceID, syncDeletions)
		m.setStatus(SyncStatus{
			State:     "pairing",
			Phase:     "Requesting reverse connection from " + peer.DeviceName + "…",
			PeerID:    peer.DeviceID,
			PeerName:  peer.DeviceName,
			Direction: "outgoing",
		})
		waitDeadline := time.NewTimer(6 * time.Second)
		reversed := false
	waitReverse:
		for {
			select {
			case rid := <-m.reverseNotify:
				if rid == peer.DeviceID {
					reversed = true
					break waitReverse
				}
			case <-waitDeadline.C:
				break waitReverse
			case <-ctx.Done():
				waitDeadline.Stop()
				return ctx.Err()
			}
		}
		waitDeadline.Stop()
		if reversed {
			return nil
		}
		return fmt.Errorf("cannot reach %s:%d (%w) — allow port %d in firewall or start sync from the other device", peer.IP, peer.Port, err, peer.Port)
	}

	httpCli := &http.Client{Timeout: 0} // stream timeouts controlled via ctx
	baseURL := fmt.Sprintf("http://%s:%d", peer.IP, peer.Port)
	selfID := m.identitySnapshot()
	selfName := m.app.store.GetSyncDeviceName()

	ephPriv, ephSpkiB64, err := generateEphemeralKey()
	if err != nil {
		return err
	}
	initNonceHex := randomHex(32)

	var sessID string
	var sessionKey, authKey []byte

	// Try trusted fast-path first
	_, localTrusted := m.app.store.GetTrustedSyncPeer(peer.DeviceID)
	if localTrusted {
		authInitReq := syncAuthInitRequest{
			InitDeviceID:   selfID.DeviceID,
			InitDeviceName: selfName,
			InitPlatform:   runtime.GOOS,
			InitPubSpkiB64: selfID.PubSpkiB64,
			InitEphSpkiB64: ephSpkiB64,
			InitNonceHex:   initNonceHex,
		}
		var aResp syncAuthInitResponse
		code, err := postJSON(ctx, httpCli, baseURL+"/api/sync/v1/auth-init", authInitReq, &aResp)
		if err != nil {
			return err
		}
		if code == http.StatusOK {
			peerPub, err := verifyDeviceIDAndKey(aResp.RespDeviceID, aResp.RespPubSpkiB64)
			if err != nil || aResp.RespDeviceID != peer.DeviceID || aResp.RespPubSpkiB64 != peer.PubKeySpkiB64 {
				return errors.New("peer identity key mismatch")
			}
			transcript := buildSyncTranscript(selfID.DeviceID, aResp.RespDeviceID, selfID.PubSpkiB64, aResp.RespPubSpkiB64,
				ephSpkiB64, aResp.RespEphSpkiB64, initNonceHex, aResp.RespNonceHex)
			if !verifySignature(peerPub, []byte("resp-sig|"+transcript), aResp.RespSigB64) {
				return errors.New("invalid peer signature")
			}
			shared, err := computeECDHSharedSecret(ephPriv, aResp.RespEphSpkiB64)
			if err != nil {
				return err
			}
			sessionKey, authKey, err = deriveSessionKeys(shared, initNonceHex, aResp.RespNonceHex)
			if err != nil {
				return err
			}
			initSig, err := signMessage(selfID.Priv, []byte("init-sig|"+aResp.SessionID+"|"+transcript))
			if err != nil {
				return err
			}
			var finOK map[string]bool
			fCode, err := postJSON(ctx, httpCli, baseURL+"/api/sync/v1/auth-finish", syncAuthFinishRequest{
				SessionID:  aResp.SessionID,
				InitSigB64: initSig,
			}, &finOK)
			if err != nil || fCode != http.StatusOK {
				return fmt.Errorf("trusted authentication rejected (%d)", fCode)
			}
			sessID = aResp.SessionID
		} else if code != http.StatusForbidden {
			return fmt.Errorf("auth-init failed (%d)", code)
		}
	}

	// First-time PIN pairing flow if not yet trusted by both sides
	if sessID == "" {
		selfInfo := m.selfInfo()
		pReq := syncPairInitRequest{
			InitDeviceID:   selfID.DeviceID,
			InitDeviceName: selfName,
			InitPlatform:   runtime.GOOS,
			InitTrackCount: selfInfo.TrackCount,
			InitPubSpkiB64: selfID.PubSpkiB64,
			InitEphSpkiB64: ephSpkiB64,
			InitNonceHex:   initNonceHex,
		}
		var pResp syncPairInitResponse
		code, err := postJSON(ctx, httpCli, baseURL+"/api/sync/v1/pair-init", pReq, &pResp)
		if err != nil {
			return err
		}
		if code != http.StatusOK {
			return fmt.Errorf("pairing rejected (%d)", code)
		}
		peerPub, err := verifyDeviceIDAndKey(pResp.RespDeviceID, pResp.RespPubSpkiB64)
		if err != nil || pResp.RespDeviceID != peer.DeviceID {
			return errors.New("peer pairing key mismatch")
		}
		transcript := buildSyncTranscript(selfID.DeviceID, pResp.RespDeviceID, selfID.PubSpkiB64, pResp.RespPubSpkiB64,
			ephSpkiB64, pResp.RespEphSpkiB64, initNonceHex, pResp.RespNonceHex)
		if !verifySignature(peerPub, []byte("resp-sig|"+transcript), pResp.RespSigB64) {
			return errors.New("invalid pairing signature")
		}
		shared, err := computeECDHSharedSecret(ephPriv, pResp.RespEphSpkiB64)
		if err != nil {
			return err
		}
		sessionKey, authKey, err = deriveSessionKeys(shared, initNonceHex, pResp.RespNonceHex)
		if err != nil {
			return err
		}
		pin := computeSasPin(transcript)
		m.setStatus(SyncStatus{
			State:     "pairing",
			Phase:     "Confirm PIN on " + peer.DeviceName + "…",
			PeerID:    peer.DeviceID,
			PeerName:  peer.DeviceName,
			Direction: "outgoing",
			Pin:       pin,
		})
		initSig, err := signMessage(selfID.Priv, []byte("init-sig|"+pResp.PairID+"|"+transcript))
		if err != nil {
			return err
		}

		deadline := time.Now().Add(65 * time.Second)
		for time.Now().Before(deadline) {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			var stResp syncPairStatusResponse
			sCode, err := postJSON(ctx, httpCli, baseURL+"/api/sync/v1/pair-status", syncPairStatusRequest{
				PairID:     pResp.PairID,
				InitSigB64: initSig,
				TrustPeer:  true,
			}, &stResp)
			if err != nil {
				return err
			}
			if sCode != http.StatusOK {
				return fmt.Errorf("pair status failed (%d)", sCode)
			}
			if stResp.Status == "accepted" && stResp.SessionID != "" {
				sessID = stResp.SessionID
				if stResp.ResponderTrusted {
					_ = m.app.store.UpsertTrustedSyncPeer(TrustedSyncPeer{
						DeviceID:      peer.DeviceID,
						DeviceName:    peer.DeviceName,
						Platform:      peer.Platform,
						PubKeySpkiB64: pResp.RespPubSpkiB64,
						PairedAt:      time.Now().Unix(),
					})
					m.emitPeers()
				}
				break
			}
			if stResp.Status == "declined" {
				return errors.New("pairing declined on " + peer.DeviceName)
			}
			if stResp.Status == "expired" {
				return errors.New("pairing timed out")
			}
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(600 * time.Millisecond):
			}
		}
		if sessID == "" {
			return errors.New("pairing timed out")
		}
	}

	// Pause local fsnotify rescan while transferring
	m.app.scanner.SetPaused(true)
	m.setStatus(SyncStatus{
		State:     "syncing",
		Phase:     "Comparing libraries…",
		PeerID:    peer.DeviceID,
		PeerName:  peer.DeviceName,
		Direction: "outgoing",
	})

	localManifest, err := m.app.store.BuildSyncManifest(selfID.DeviceID, selfName, runtime.GOOS, m.app.scanDirs())
	if err != nil {
		return err
	}
	localManBytes, _ := json.Marshal(localManifest)
	sealedLocalMan, err := encryptSingleBuffer(sessionKey, localManBytes)
	if err != nil {
		return err
	}

	sealedRemoteMan, err := doAuthedPostBytes(ctx, httpCli, baseURL, "/api/sync/v1/manifest", sessID, authKey, sealedLocalMan)
	if err != nil {
		return fmt.Errorf("manifest exchange failed: %w", err)
	}
	remoteManBytes, err := decryptSingleBuffer(sessionKey, sealedRemoteMan)
	if err != nil {
		return fmt.Errorf("manifest decrypt failed: %w", err)
	}
	var remoteManifest SyncManifest
	if err := json.Unmarshal(remoteManBytes, &remoteManifest); err != nil {
		return err
	}

	baseSnap, _ := m.app.store.LoadSyncSnapshot(peer.DeviceID)
	plan := ComputeSyncPlan(localManifest, remoteManifest, baseSnap, syncDeletions)

	// Calculate total audio bytes to transfer + check free disk space for incoming tracks
	var totalBytes int64
	var pullBytes int64
	for _, t := range plan.PullAudioFromRemote {
		totalBytes += t.FileSize
		pullBytes += t.FileSize
	}
	for _, t := range plan.PushAudioToRemote {
		totalBytes += t.FileSize
	}
	if pullBytes > 0 {
		destDir := m.primaryDestDir()
		if avail, ok := freeDiskBytes(destDir); ok && avail < uint64(pullBytes)+64*1024*1024 {
			return fmt.Errorf("not enough free disk space in %s (need %d MB)", destDir, pullBytes/(1024*1024))
		}
	}

	type xferTask struct {
		dir     string // pull | push
		kind    string // audio | cover | lyrics
		trackID string
		meta    SyncTrack
		label   string
	}
	var audioTasks []xferTask
	var auxTasks []xferTask
	for _, t := range plan.PullAudioFromRemote {
		audioTasks = append(audioTasks, xferTask{dir: "pull", kind: "audio", trackID: t.ID, meta: t, label: t.Artist + " - " + t.Title})
	}
	for _, t := range plan.PushAudioToRemote {
		audioTasks = append(audioTasks, xferTask{dir: "push", kind: "audio", trackID: t.ID, meta: t, label: t.Artist + " - " + t.Title})
	}
	for _, id := range plan.PullCoverFromRemote {
		auxTasks = append(auxTasks, xferTask{dir: "pull", kind: "cover", trackID: id})
	}
	for _, id := range plan.PullLyricsFromRemote {
		auxTasks = append(auxTasks, xferTask{dir: "pull", kind: "lyrics", trackID: id})
	}
	for _, id := range plan.PushCoverToRemote {
		auxTasks = append(auxTasks, xferTask{dir: "push", kind: "cover", trackID: id})
	}
	for _, id := range plan.PushLyricsToRemote {
		auxTasks = append(auxTasks, xferTask{dir: "push", kind: "lyrics", trackID: id})
	}

	filesTotal := len(audioTasks) + len(auxTasks)
	var filesDone atomic.Int32
	var bytesDone atomic.Int64
	startAt := time.Now()
	var progMu sync.Mutex
	currentFile := ""
	lastEmit := time.Time{}
	lastPing := time.Time{}

	reportProgress := func(force bool, label string) {
		progMu.Lock()
		if label != "" {
			currentFile = label
		}
		if !force && time.Since(lastEmit) < 200*time.Millisecond {
			progMu.Unlock()
			return
		}
		lastEmit = time.Now()
		curFile := currentFile
		shouldPing := force || time.Since(lastPing) >= 600*time.Millisecond
		if shouldPing {
			lastPing = time.Now()
		}
		progMu.Unlock()

		bd := bytesDone.Load()
		fd := int(filesDone.Load())
		pct := 0
		if totalBytes > 0 {
			pct = int((bd * 100) / totalBytes)
		} else if filesTotal > 0 {
			pct = (fd * 100) / filesTotal
		}
		if pct > 99 {
			pct = 99
		}
		elapsed := time.Since(startAt).Seconds()
		var speed int64
		if elapsed > 0.2 {
			speed = int64(float64(bd) / elapsed)
		}
		phase := fmt.Sprintf("Transferring files (%d/%d)…", fd, filesTotal)
		if filesTotal == 0 {
			phase = "Merging metadata & playlists…"
		}
		m.setStatus(SyncStatus{
			State:            "syncing",
			Phase:            phase,
			PeerID:           peer.DeviceID,
			PeerName:         peer.DeviceName,
			Direction:        "outgoing",
			Percent:          pct,
			CurrentFile:      curFile,
			TransferredBytes: bd,
			TotalBytes:       totalBytes,
			FilesDone:        fd,
			FilesTotal:       filesTotal,
			SpeedBps:         speed,
		})
		if shouldPing {
			go func() {
				pingJSON, _ := json.Marshal(syncProgressPing{
					Phase:            phase,
					Percent:          pct,
					CurrentFile:      curFile,
					TransferredBytes: bd,
					TotalBytes:       totalBytes,
					FilesDone:        fd,
					FilesTotal:       filesTotal,
					SpeedBps:         speed,
				})
				if sealed, e := encryptSingleBuffer(sessionKey, pingJSON); e == nil {
					_, _ = doAuthedPostBytes(ctx, httpCli, baseURL, "/api/sync/v1/progress", sessID, authKey, sealed)
				}
			}()
		}
	}

	runBatch := func(batch []xferTask) error {
		if len(batch) == 0 {
			return nil
		}
		if batch[0].label != "" {
			reportProgress(true, batch[0].label)
		} else {
			reportProgress(true, "")
		}
		ch := make(chan xferTask)
		var wg sync.WaitGroup
		var errMu sync.Mutex
		var firstErr error
		setErr := func(e error) {
			if e == nil {
				return
			}
			errMu.Lock()
			if firstErr == nil {
				firstErr = e
			}
			errMu.Unlock()
		}

		workers := 3
		if len(batch) < workers {
			workers = len(batch)
		}
		for i := 0; i < workers; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for t := range ch {
					if ctx.Err() != nil {
						return
					}
					if t.label != "" {
						reportProgress(false, t.label)
					}
					var err error
					if t.dir == "pull" {
						err = m.pullFileFromPeer(ctx, httpCli, baseURL, sessID, sessionKey, authKey, t.kind, t.meta, t.trackID, func(n int64) {
							bytesDone.Add(n)
							reportProgress(false, "")
						})
					} else {
						err = m.pushFileToPeer(ctx, httpCli, baseURL, sessID, sessionKey, authKey, t.kind, t.meta, t.trackID, func(n int64) {
							bytesDone.Add(n)
							reportProgress(false, "")
						})
					}
					if err != nil {
						setErr(err)
						return
					}
					filesDone.Add(1)
					reportProgress(false, "")
				}
			}()
		}

	feedLoop:
		for _, t := range batch {
			errMu.Lock()
			hasErr := firstErr != nil
			errMu.Unlock()
			if hasErr || ctx.Err() != nil {
				break feedLoop
			}
			select {
			case ch <- t:
			case <-ctx.Done():
				break feedLoop
			}
		}
		close(ch)
		wg.Wait()
		if firstErr != nil {
			return firstErr
		}
		return ctx.Err()
	}

	if err := runBatch(audioTasks); err != nil {
		return err
	}
	if err := runBatch(auxTasks); err != nil {
		return err
	}

	m.setStatus(SyncStatus{
		State:            "syncing",
		Phase:            "Applying merged playlists, play counts & genres…",
		PeerID:           peer.DeviceID,
		PeerName:         peer.DeviceName,
		Direction:        "outgoing",
		Percent:          99,
		TransferredBytes: totalBytes,
		TotalBytes:       totalBytes,
		FilesDone:        filesTotal,
		FilesTotal:       filesTotal,
	})

	// Commit on Responder first, then commit locally
	commitBytes, err := json.Marshal(plan.RemoteCommit)
	if err != nil {
		return err
	}
	sealedCommit, err := encryptSingleBuffer(sessionKey, commitBytes)
	if err != nil {
		return err
	}
	if _, err := doAuthedPostBytes(ctx, httpCli, baseURL, "/api/sync/v1/commit", sessID, authKey, sealedCommit); err != nil {
		return fmt.Errorf("remote commit failed: %w", err)
	}
	if err := m.app.store.ApplySyncCommit(plan.LocalCommit, peer.DeviceID, &plan.MergedSnapshot); err != nil {
		return fmt.Errorf("local commit failed: %w", err)
	}

	m.app.scanner.SetPaused(false)
	m.app.reloadTracks()
	m.app.emit("library:changed")
	m.app.emit("playlists:changed")
	m.emitPeers()

	msg := fmt.Sprintf("Synced with %s: +%d added, %d sent, %d removed, %d playlists",
		peer.DeviceName, plan.SummaryLocalAdded, plan.SummaryRemoteAdded, plan.SummaryLocalDeleted, plan.SummaryPlaylists)
	m.setStatus(SyncStatus{
		State:            "done",
		Phase:            "Sync complete",
		PeerID:           peer.DeviceID,
		PeerName:         peer.DeviceName,
		Direction:        "outgoing",
		Percent:          100,
		TransferredBytes: totalBytes,
		TotalBytes:       totalBytes,
		FilesDone:        filesTotal,
		FilesTotal:       filesTotal,
		AddedTracks:      plan.SummaryLocalAdded,
		UpdatedTracks:    plan.SummaryUpdated,
		DeletedTracks:    plan.SummaryLocalDeleted,
		SyncedPlaylists:  plan.SummaryPlaylists,
		Message:          msg,
	})
	m.app.emit("app:toast", msg)
	return nil
}

func (m *SyncManager) pullFileFromPeer(
	ctx context.Context,
	cli *http.Client,
	baseURL, sessID string,
	sessionKey, authKey []byte,
	kind string,
	meta SyncTrack,
	trackID string,
	onBytes func(int64),
) error {
	path := "/api/sync/v1/file/" + kind + "/" + base64.RawURLEncoding.EncodeToString([]byte(trackID))
	nonce := fmt.Sprintf("%d-%s", time.Now().Unix(), randomHex(8))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, baseURL+path, nil)
	if err != nil {
		return err
	}
	req.Header.Set("X-Sync-Session", sessID)
	req.Header.Set("X-Sync-Nonce", nonce)
	req.Header.Set("X-Sync-Mac", computeRequestMAC(authKey, http.MethodGet, path, sessID, nonce))

	resp, err := cli.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound && (kind == "cover" || kind == "lyrics") {
		return nil // optional sidecar absent
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("pull %s %s failed (%d)", kind, trackID, resp.StatusCode)
	}
	plainSize, _ := strconv.ParseInt(resp.Header.Get("X-Plain-Size"), 10, 64)
	if plainSize < 0 {
		plainSize = -1
	}
	switch kind {
	case "audio":
		ext := resp.Header.Get("X-Track-Ext")
		meta.ID = trackID
		return m.saveIncomingAudio(resp.Body, sessionKey, meta, ext, plainSize, onBytes)
	case "cover":
		return m.saveIncomingCover(resp.Body, sessionKey, trackID, plainSize)
	case "lyrics":
		return m.saveIncomingLyrics(resp.Body, sessionKey, trackID, plainSize)
	default:
		return nil
	}
}

func (m *SyncManager) pushFileToPeer(
	ctx context.Context,
	cli *http.Client,
	baseURL, sessID string,
	sessionKey, authKey []byte,
	kind string,
	meta SyncTrack,
	trackID string,
	onBytes func(int64),
) error {
	var filePath string
	var ext string
	switch kind {
	case "audio":
		t, ok := m.app.store.TrackByID(trackID)
		if !ok || t.Path == "" {
			return fmt.Errorf("missing local audio for %s", trackID)
		}
		filePath = t.Path
		ext = filepath.Ext(t.Path)
	case "cover":
		p := coverPath(trackID)
		if !fileExists(p) {
			return nil
		}
		filePath = p
		ext = ".jpg"
	case "lyrics":
		t, ok := m.app.store.TrackByID(trackID)
		if !ok || t.Path == "" {
			return nil
		}
		p := sidecarLrcPath(t.Path)
		if p == "" {
			return nil
		}
		filePath = p
		ext = ".lrc"
	default:
		return nil
	}

	f, err := os.Open(filePath)
	if err != nil {
		if kind != "audio" {
			return nil
		}
		return err
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return err
	}

	path := "/api/sync/v1/file/" + kind + "/" + base64.RawURLEncoding.EncodeToString([]byte(trackID))
	nonce := fmt.Sprintf("%d-%s", time.Now().Unix(), randomHex(8))
	pr, pw := io.Pipe()

	req, err := http.NewRequestWithContext(ctx, http.MethodPut, baseURL+path, pr)
	if err != nil {
		_ = pr.Close()
		_ = pw.Close()
		return err
	}
	req.ContentLength = encryptedStreamSize(fi.Size())
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("X-Sync-Session", sessID)
	req.Header.Set("X-Sync-Nonce", nonce)
	req.Header.Set("X-Sync-Mac", computeRequestMAC(authKey, http.MethodPut, path, sessID, nonce))
	req.Header.Set("X-Plain-Size", strconv.FormatInt(fi.Size(), 10))
	req.Header.Set("X-Track-Ext", ext)
	if kind == "audio" {
		metaJSON, _ := json.Marshal(meta)
		sealedMeta, err := encryptSingleBuffer(sessionKey, metaJSON)
		if err != nil {
			_ = pr.Close()
			_ = pw.Close()
			return err
		}
		req.Header.Set("X-Track-Meta", base64.StdEncoding.EncodeToString(sealedMeta))
	}

	go func() {
		var cb func(int64)
		if kind == "audio" {
			cb = onBytes
		}
		werr := writeEncryptedStream(pw, sessionKey, f, cb)
		_ = pw.CloseWithError(werr)
	}()

	resp, err := cli.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return fmt.Errorf("push %s %s failed (%d): %s", kind, trackID, resp.StatusCode, strings.TrimSpace(string(errBody)))
	}
	return nil
}

func postJSON(ctx context.Context, cli *http.Client, fullURL string, reqBody any, out any) (int, error) {
	b, err := json.Marshal(reqBody)
	if err != nil {
		return 0, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, fullURL, bytes.NewReader(b))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := cli.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if out != nil && resp.StatusCode == http.StatusOK {
		if err := json.NewDecoder(io.LimitReader(resp.Body, 1024*1024)).Decode(out); err != nil {
			return resp.StatusCode, err
		}
	} else {
		_, _ = io.Copy(io.Discard, resp.Body)
	}
	return resp.StatusCode, nil
}

func doAuthedPostBytes(ctx context.Context, cli *http.Client, baseURL, path, sessID string, authKey, body []byte) ([]byte, error) {
	nonce := fmt.Sprintf("%d-%s", time.Now().Unix(), randomHex(8))
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+path, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("X-Sync-Session", sessID)
	req.Header.Set("X-Sync-Nonce", nonce)
	req.Header.Set("X-Sync-Mac", computeRequestMAC(authKey, http.MethodPost, path, sessID, nonce))

	resp, err := cli.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	respBytes, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024*1024))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("http %d: %s", resp.StatusCode, strings.TrimSpace(string(respBytes)))
	}
	return respBytes, nil
}
