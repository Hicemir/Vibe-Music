package main

const (
	syncProtocolVersion = 1
	syncDiscoveryMagic  = "VIBEMUSIC_SYNC_V1"
	syncDefaultPort     = 53318
	syncMulticastAddr   = "224.0.0.167"
	syncChunkSize       = 64 * 1024
)

type SyncPeer struct {
	DeviceID      string `json:"deviceId"`
	DeviceName    string `json:"deviceName"`
	Platform      string `json:"platform"`
	IP            string `json:"ip"`
	Port          int    `json:"port"`
	TrackCount    int    `json:"trackCount"`
	PlaylistCount int    `json:"playlistCount"`
	PubKeySpkiB64 string `json:"pubKeySpkiB64"`
	Trusted       bool   `json:"trusted"`
	LastSeen      int64  `json:"lastSeen"`
}

type TrustedSyncPeer struct {
	DeviceID      string `json:"deviceId"`
	DeviceName    string `json:"deviceName"`
	Platform      string `json:"platform"`
	PubKeySpkiB64 string `json:"pubKeySpkiB64"`
	PairedAt      int64  `json:"pairedAt"`
	LastSyncAt    int64  `json:"lastSyncAt"`
}

type SyncPairPrompt struct {
	PairID         string `json:"pairId"`
	DeviceID       string `json:"deviceId"`
	DeviceName     string `json:"deviceName"`
	Platform       string `json:"platform"`
	TrackCount     int    `json:"trackCount"`
	Pin            string `json:"pin"`
	TrustedDefault bool   `json:"trustedDefault"`
	Incoming       bool   `json:"incoming"`
}

type SyncStatus struct {
	State            string `json:"state"` // idle | discovering | pairing | syncing | done | error
	Phase            string `json:"phase"`
	PeerID           string `json:"peerId"`
	PeerName         string `json:"peerName"`
	Direction        string `json:"direction"`
	Pin              string `json:"pin"`
	Percent          int    `json:"percent"`
	CurrentFile      string `json:"currentFile"`
	TransferredBytes int64  `json:"transferredBytes"`
	TotalBytes       int64  `json:"totalBytes"`
	FilesDone        int    `json:"filesDone"`
	FilesTotal       int    `json:"filesTotal"`
	SpeedBps         int64  `json:"speedBps"`
	AddedTracks      int    `json:"addedTracks"`
	UpdatedTracks    int    `json:"updatedTracks"`
	DeletedTracks    int    `json:"deletedTracks"`
	SyncedPlaylists  int    `json:"syncedPlaylists"`
	Error            string `json:"error"`
	Message          string `json:"message"`
}

type SyncSelfInfo struct {
	DeviceID      string   `json:"deviceId"`
	DeviceName    string   `json:"deviceName"`
	Platform      string   `json:"platform"`
	Port          int      `json:"port"`
	LocalIPs      []string `json:"localIps"`
	TrackCount    int      `json:"trackCount"`
	PlaylistCount int      `json:"playlistCount"`
}

type SyncStateResponse struct {
	Self   SyncSelfInfo      `json:"self"`
	Status SyncStatus        `json:"status"`
	Peers  []SyncPeer        `json:"peers"`
	Prompt *SyncPairPrompt   `json:"prompt"`
}

type syncDiscoveryPacket struct {
	Magic          string `json:"magic"`
	Type           string `json:"type"` // announce | reply | reverse_request
	DeviceID       string `json:"deviceId"`
	DeviceName     string `json:"deviceName"`
	Platform       string `json:"platform"`
	Port           int    `json:"port"`
	TrackCount     int    `json:"trackCount"`
	PlaylistCount  int    `json:"playlistCount"`
	PubKeySpkiB64  string `json:"pubKeySpkiB64"`
	TargetDeviceID string `json:"targetDeviceId,omitempty"`
	SyncDeletions  bool   `json:"syncDeletions,omitempty"`
}

type syncInfoResponse struct {
	ProtocolVersion int    `json:"protocolVersion"`
	DeviceID        string `json:"deviceId"`
	DeviceName      string `json:"deviceName"`
	Platform        string `json:"platform"`
	Port            int    `json:"port"`
	TrackCount      int    `json:"trackCount"`
	PlaylistCount   int    `json:"playlistCount"`
	PubKeySpkiB64   string `json:"pubKeySpkiB64"`
}

type syncAuthInitRequest struct {
	InitDeviceID    string `json:"initDeviceId"`
	InitDeviceName  string `json:"initDeviceName"`
	InitPlatform    string `json:"initPlatform"`
	InitPubSpkiB64  string `json:"initPubSpkiB64"`
	InitEphSpkiB64  string `json:"initEphSpkiB64"`
	InitNonceHex    string `json:"initNonceHex"`
}

type syncAuthInitResponse struct {
	SessionID      string `json:"sessionId"`
	RespDeviceID   string `json:"respDeviceId"`
	RespDeviceName string `json:"respDeviceName"`
	RespPubSpkiB64 string `json:"respPubSpkiB64"`
	RespEphSpkiB64 string `json:"respEphSpkiB64"`
	RespNonceHex   string `json:"respNonceHex"`
	RespSigB64     string `json:"respSigB64"`
}

type syncAuthFinishRequest struct {
	SessionID  string `json:"sessionId"`
	InitSigB64 string `json:"initSigB64"`
}

type syncPairInitRequest struct {
	InitDeviceID    string `json:"initDeviceId"`
	InitDeviceName  string `json:"initDeviceName"`
	InitPlatform    string `json:"initPlatform"`
	InitTrackCount  int    `json:"initTrackCount"`
	InitPubSpkiB64  string `json:"initPubSpkiB64"`
	InitEphSpkiB64  string `json:"initEphSpkiB64"`
	InitNonceHex    string `json:"initNonceHex"`
}

type syncPairInitResponse struct {
	PairID         string `json:"pairId"`
	RespDeviceID   string `json:"respDeviceId"`
	RespDeviceName string `json:"respDeviceName"`
	RespPlatform   string `json:"respPlatform"`
	RespPubSpkiB64 string `json:"respPubSpkiB64"`
	RespEphSpkiB64 string `json:"respEphSpkiB64"`
	RespNonceHex   string `json:"respNonceHex"`
	RespSigB64     string `json:"respSigB64"`
}

type syncPairStatusRequest struct {
	PairID     string `json:"pairId"`
	InitSigB64 string `json:"initSigB64"`
	TrustPeer  bool   `json:"trustPeer"`
}

type syncPairStatusResponse struct {
	Status           string `json:"status"` // pending | accepted | declined | expired
	SessionID        string `json:"sessionId,omitempty"`
	ResponderTrusted bool   `json:"responderTrusted,omitempty"`
}

type syncProgressPing struct {
	Phase            string `json:"phase"`
	Percent          int    `json:"percent"`
	CurrentFile      string `json:"currentFile"`
	TransferredBytes int64  `json:"transferredBytes"`
	TotalBytes       int64  `json:"totalBytes"`
	FilesDone        int    `json:"filesDone"`
	FilesTotal       int    `json:"filesTotal"`
	SpeedBps         int64  `json:"speedBps"`
}

// ---------- manifest, merge & snapshot payloads ----------

type SyncTrack struct {
	ID            string  `json:"id"`
	YtID          string  `json:"ytId"`
	RelPath       string  `json:"relPath"`
	Title         string  `json:"title"`
	Artist        string  `json:"artist"`
	Album         string  `json:"album"`
	Duration      float64 `json:"duration"`
	Year          int     `json:"year"`
	AddedAt       int64   `json:"addedAt"`
	Liked         bool    `json:"liked"`
	PlayCount     int     `json:"playCount"`
	LastPlayed    int64   `json:"lastPlayed"`
	HasCover      bool    `json:"hasCover"`
	HasCoverFile  bool    `json:"hasCoverFile"`
	HasLyricsFile bool    `json:"hasLyricsFile"`
	Format        string  `json:"format"`
	Bitrate       int     `json:"bitrate"`
	FileSize      int64   `json:"fileSize"`
	MTimeUnix     int64   `json:"mtimeUnix"`
	UpdatedAt     int64   `json:"updatedAt"`
	Genre         string  `json:"genre"`
	GenreSource   string  `json:"genreSource"`
	Hidden        bool    `json:"hidden"`
}

type SyncPlaylist struct {
	SyncUUID   string   `json:"syncUuid"`
	Name       string   `json:"name"`
	CreatedAt  int64    `json:"createdAt"`
	LastPlayed int64    `json:"lastPlayed"`
	TrackIDs   []string `json:"trackIds"`
}

type SyncPlayEvent struct {
	TrackID  string `json:"trackId"`
	PlayedAt int64  `json:"playedAt"`
}

type SyncArtistGenre struct {
	ArtistKey string `json:"artistKey"`
	Genre     string `json:"genre"`
	Source    string `json:"source"`
	CheckedAt int64  `json:"checkedAt"`
}

type SyncTrackContext struct {
	CacheKey  string `json:"cacheKey"`
	Hints     string `json:"hints"`
	UpdatedAt int64  `json:"updatedAt"`
}

type SyncManifest struct {
	DeviceID            string             `json:"deviceId"`
	DeviceName          string             `json:"deviceName"`
	Platform            string             `json:"platform"`
	GeneratedAt         int64              `json:"generatedAt"`
	LibraryUnmounted    bool               `json:"libraryUnmounted"`
	Tracks              []SyncTrack        `json:"tracks"`
	Playlists           []SyncPlaylist     `json:"playlists"`
	PlayHistory         []SyncPlayEvent    `json:"playHistory"`
	HiddenTrackIDs      []string           `json:"hiddenTrackIds"`
	ArtistGenres        []SyncArtistGenre  `json:"artistGenres"`
	TrackContexts       []SyncTrackContext `json:"trackContexts"`
	SyncedYtCollections []string           `json:"syncedYtCollections"`
}

type SyncSnapshotTrack struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Artist      string `json:"artist"`
	Album       string `json:"album"`
	Year        int    `json:"year"`
	Liked       bool   `json:"liked"`
	PlayCount   int    `json:"playCount"`
	LastPlayed  int64  `json:"lastPlayed"`
	Genre       string `json:"genre"`
	GenreSource string `json:"genreSource"`
	Hidden      bool   `json:"hidden"`
	UpdatedAt   int64  `json:"updatedAt"`
}

type SyncSnapshotPlaylist struct {
	SyncUUID   string   `json:"syncUuid"`
	Name       string   `json:"name"`
	LastPlayed int64    `json:"lastPlayed"`
	TrackIDs   []string `json:"trackIds"`
}

type SyncSnapshot struct {
	SyncedAt  int64                           `json:"syncedAt"`
	Tracks    map[string]SyncSnapshotTrack    `json:"tracks"`
	Playlists map[string]SyncSnapshotPlaylist `json:"playlists"`
}

type SyncCommitPayload struct {
	SenderDeviceID       string             `json:"senderDeviceId"`
	SenderDeviceName     string             `json:"senderDeviceName"`
	SenderPlatform       string             `json:"senderPlatform"`
	TracksMeta           []SyncTrack        `json:"tracksMeta"`
	DeletedTrackIDs      []string           `json:"deletedTrackIds"`
	Playlists            []SyncPlaylist     `json:"playlists"`
	DeletedPlaylistUUIDs []string           `json:"deletedPlaylistUuids"`
	PlayHistory          []SyncPlayEvent    `json:"playHistory"`
	HiddenTrackIDs       []string           `json:"hiddenTrackIds"`
	ArtistGenres         []SyncArtistGenre  `json:"artistGenres"`
	TrackContexts        []SyncTrackContext `json:"trackContexts"`
	SyncedYtCollections  []string           `json:"syncedYtCollections"`
	NewSnapshot          SyncSnapshot       `json:"newSnapshot"`
}
