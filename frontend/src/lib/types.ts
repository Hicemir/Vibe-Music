export interface Track {
  id: number
  path: string
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
}

export interface Playlist {
  id: number
  name: string
  createdAt: number
  lastPlayed: number
  trackCount: number
  duration: number
  coverTracks: number[] | null
}

export interface PlayerState {
  trackId: number
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
  tid: number
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

export interface Lyrics {
  synced: boolean
  lines: { time: number; text: string }[] | null
}

export type RouteName =
  | 'home' | 'search' | 'playlists' | 'playlist' | 'liked' | 'recent' | 'mostplayed' | 'allsongs'
  | 'albums' | 'album' | 'artists' | 'artist' | 'downloads' | 'settings'

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
