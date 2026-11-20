export interface Track {
  id: string
  ytId: string
  title: string
  artist: string
  album: string
  duration: number
  year: number
  addedAt: number
  liked: boolean
  playCount: number
  hasCover: boolean
  format: string
  bitrate: number
  lastPlayed: number
  genres: string[]
  genreSource: string
}

export interface Playlist {
  id: number
  name: string
  createdAt: number
  lastPlayed: number
  trackCount: number
  duration: number
  coverTracks: string[] | null
}

export interface PlayerState {
  trackId: string
  playlistId: number
  playing: boolean
  loaded: boolean
  position: number
  duration: number
  volume: number
  muted: boolean
  shuffle: boolean
  repeat: 'off' | 'all' | 'one'
  queueIndex: number
  queueLen: number
  hasNext: boolean
  hasPrev: boolean
  sleepAt: number
  sleepEnd: boolean
}

export interface QueueEntry {
  qid: number
  tid: string
}

export interface AISettings {
  enabled: boolean
  autoOnNew: boolean
  provider: string
  baseUrl: string
  apiKey: string
  model: string
  localModelId: string
  localCustomGguf: string
  localGpu: boolean
  allowCustomGenres: boolean
  webSearch: boolean
  geminiGrounding: boolean
}

export interface AIProviderPreset {
  id: string
  name: string
  kind: 'local' | 'openai' | 'gemini' | 'anthropic' | string
  local: boolean
  needsKey: boolean
  defaultBaseUrl: string
  defaultModel: string
  note: string
}

export interface LocalAIModel {
  id: string
  name: string
  sizeMb: number
  minRam: string
  url: string
  filename: string
  note: string
}

export interface LocalAIStatus {
  runtimeReady: boolean
  runtimeVersion: string
  runtimeVariant: string
  vulkanAvail: boolean
  installedModels: string[]
  diskUsageBytes: number
  state: 'idle' | 'downloading' | 'extracting' | 'starting' | 'running' | 'error' | string
  phase: string
  percent: number
  downloadedBytes: number
  totalBytes: number
  message: string
  error: string
}

export interface AITestResult {
  ok: boolean
  message: string
  latencyMs: number
}

export interface GenreStatus {
  running: boolean
  mode: string
  phase: string
  done: number
  total: number
  identified: number
  aiIdentified: number
  unidentified: number
  unchecked: number
  pendingAI: number
  message: string
  error: string
}

export interface GenreTargets {
  missing: number
  allAuto: number
  pendingAI: number
  total: number
}

export interface Settings {
  musicDirs: string[]
  downloadDir: string
  downloadFormat: string
  volume: number
  shuffle: boolean
  repeat: string
  fadeSeconds: number
  eqEnabled: boolean
  eq: number[]
  autoUpdateYtdlp: boolean
  closeToTray: boolean
  normalize: boolean
  homeOrder: string[]
  homeHidden: string[]
  quickPicksRows: string
  cookieBrowser: string
  browserBackground: boolean
  ai: AISettings
}

export interface DiscoverItem {
  videoId: string
  title: string
  artist: string
  duration: number
  thumbUrl: string
  url: string
}

export interface DownloadJob {
  id: number
  url: string
  mode: 'track' | 'playlist'
  status: 'queued' | 'running' | 'done' | 'error' | 'cancelled'
  title: string
  playlist: string
  percent: number
  speed: string
  index: number
  total: number
  done: number
  error: string
  startedAt: number
}

export interface YtdlpStatus {
  state: 'idle' | 'updating' | 'ok' | 'error'
  version: string
  path: string
  message: string
}

export type YtCollectionType = 'LIKED_MUSIC' | 'PLAYLIST' | 'ALBUM'

export interface YtMusicCollection {
  id: string
  browseId: string
  playlistUrl: string
  title: string
  subtitle: string
  trackCountHint: number
  thumbnailUrl: string
  type: YtCollectionType | string
}

export interface YtMusicStatus {
  loggedIn: boolean
  syncing: boolean
  message: string
}

export interface YtMusicLibraryResponse {
  collections: YtMusicCollection[]
  syncedIds: string[]
  error: string
}

export interface Lyrics {
  synced: boolean
  lines: { time: number; text: string }[] | null
}

export type RouteName =
  | 'home' | 'search' | 'playlists' | 'playlist' | 'liked' | 'recent' | 'mostplayed' | 'allsongs'
  | 'albums' | 'album' | 'artists' | 'artist' | 'artistradio' | 'quickpick' | 'downloads' | 'settings'

export interface Route {
  name: RouteName
  id?: number | string
  scrollTop?: number
}

export interface MenuItem {
  label?: string
  icon?: string
  danger?: boolean
  separator?: boolean
  disabled?: boolean
  action?: () => void
}
