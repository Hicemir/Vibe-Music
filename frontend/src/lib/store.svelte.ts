import * as Go from '../../wailsjs/go/main/App'
import { EventsOn } from '../../wailsjs/runtime/runtime'
import { buildRadioMix } from './util'
import type {
  Track, Playlist, PlayerState, QueueEntry, Settings, DownloadJob, YtdlpStatus, Route, MenuItem, DiscoverItem,
} from './types'

export { Go }

const emptyPlayer: PlayerState = {
  trackId: 0, playlistId: 0, playing: false, loaded: false, position: 0, duration: 0, volume: 80, muted: false,
  shuffle: false, repeat: 'off', queueIndex: -1, queueLen: 0, hasNext: false, hasPrev: false,
  sleepAt: 0, sleepEnd: false,
}

export type Modal =
  | { type: 'newPlaylist'; trackIds?: number[] }
  | { type: 'addToPlaylist'; trackIds: number[] }
  | { type: 'rename'; id: number; name: string }
  | { type: 'confirm'; title: string; message: string; confirmLabel: string; danger?: boolean; onConfirm: () => void }
  | { type: 'sleep' }

class Core {
  tracks = $state.raw<Track[]>([])
  byId = $state.raw<Map<number, Track>>(new Map())
  playlists = $state.raw<Playlist[]>([])
  playlistTrackMap = $state.raw<Record<number, number[]>>({})
  player = $state.raw<PlayerState>(emptyPlayer)
  queue = $state.raw<QueueEntry[]>([])
  radioSeed = $state.raw<{ trackId: number; mode: 'all' | 'familiar' | 'rediscover' | 'artist' } | null>(null)
  discoverItems = $state.raw<DiscoverItem[]>([])
  discoverLoading = $state(false)
  discoverSeed = $state(0)
  settings = $state.raw<Settings | null>(null)
  downloads = $state.raw<DownloadJob[]>([])
  ytdlp = $state.raw<YtdlpStatus>({ state: 'idle', version: '', path: '', message: '' })
  scan = $state.raw({ scanning: false, done: 0, total: 0 })
  loaded = $state(false)
  version = $state('0.4.4')
  windowMaximised = $state(true)
  coverExpanded = $state(false)

  route = $state.raw<Route>({ name: 'home', scrollTop: 0 })
  hist = $state.raw<Route[]>([{ name: 'home', scrollTop: 0 }])
  hpos = $state(0)
  query = $state('')
  targetScroll = $state(0)
  scroller: HTMLDivElement | null = null

  panel = $state<'none' | 'queue' | 'lyrics'>('none')
  mini = $state(false)
  modal = $state.raw<Modal | null>(null)
  menu = $state.raw<{ x: number; y: number; items: MenuItem[] } | null>(null)
  menuAnchor: HTMLElement | null = null
  toasts = $state.raw<{ id: number; text: string }[]>([])
  toastId = 0

  get current(): Track | undefined {
    return this.byId.get(this.player.trackId)
  }

  // ----- data loading -----
  async loadTracks() {
    const t = (await Go.GetTracks()) as unknown as Track[]
    this.tracks = t
    this.byId = new Map(t.map((x) => [x.id, x]))
  }
  async loadPlaylists() {
    this.playlists = ((await Go.GetPlaylists()) as unknown as Playlist[]) ?? []
    try {
      const m = (await (Go as any).GetAllPlaylistTracks?.()) as Record<number, number[]> | undefined
      if (m) this.playlistTrackMap = m
    } catch {}
  }

  async fetchOnlineDiscover(append = false) {
    if (this.discoverLoading) return
    this.discoverLoading = true
    try {
      const seed = append || this.discoverItems.length > 0 ? ++this.discoverSeed : this.discoverSeed
      const items = (((await (Go as any).GetOnlineDiscovery?.(seed)) as DiscoverItem[]) ?? [])
      if (append) {
        const seen = new Set(this.discoverItems.map((x) => x.videoId))
        const fresh = items.filter((x) => !seen.has(x.videoId))
        this.discoverItems = [...this.discoverItems, ...fresh]
      } else {
        this.discoverItems = items
      }
    } catch {
    } finally {
      this.discoverLoading = false
    }
  }

  // ----- navigation -----
  saveCurrentScroll() {
    if (!this.scroller) return
    const h = this.hist.slice()
    if (h[this.hpos]) {
      h[this.hpos] = { ...h[this.hpos], scrollTop: this.scroller.scrollTop }
      this.hist = h
    }
  }

  go(r: Route, push = true) {
    if (r.name !== 'search') this.query = ''
    if (push) {
      this.saveCurrentScroll()
      const h = this.hist.slice(0, this.hpos + 1)
      const last = h[h.length - 1]
      const nextRoute: Route = { ...r, scrollTop: 0 }
      if (!last || last.name !== r.name || last.id !== r.id) {
        h.push(nextRoute)
        this.hist = h
        this.hpos = h.length - 1
      } else {
        h[h.length - 1] = nextRoute
        this.hist = h
      }
      this.targetScroll = 0
      this.route = nextRoute
      this.scroller?.scrollTo({ top: 0 })
    } else {
      const target = r.scrollTop ?? 0
      this.targetScroll = target
      this.route = r
    }
  }

  lastNavAt = 0
  back() {
    const now = performance.now()
    if (now - this.lastNavAt < 100) return
    this.lastNavAt = now
    if (this.hpos > 0) {
      this.saveCurrentScroll()
      this.hpos--
      this.go(this.hist[this.hpos], false)
    }
  }
  forward() {
    const now = performance.now()
    if (now - this.lastNavAt < 100) return
    this.lastNavAt = now
    if (this.hpos < this.hist.length - 1) {
      this.saveCurrentScroll()
      this.hpos++
      this.go(this.hist[this.hpos], false)
    }
  }

  // ----- ui helpers -----
  toast(text: string) {
    const id = ++this.toastId
    this.toasts = [...this.toasts, { id, text }]
    setTimeout(() => (this.toasts = this.toasts.filter((t) => t.id !== id)), 3500)
  }
  openMenu(e: MouseEvent, items: MenuItem[]) {
    e.preventDefault()
    e.stopPropagation()
    const targetEl = e.target as HTMLElement | null
    const curEl = e.currentTarget as HTMLElement | null
    const btn = targetEl?.closest('button') ?? (curEl?.tagName === 'BUTTON' ? curEl : null) ?? curEl ?? targetEl
    if (e.type === 'click' && this.menu && btn && this.menuAnchor === btn) {
      this.closeMenu()
      return
    }
    this.menuAnchor = e.type === 'click' ? btn : null
    if (e.type === 'click' && btn && btn.tagName === 'BUTTON') {
      const r = btn.getBoundingClientRect()
      this.menu = { x: r.left, y: r.bottom + 6, items }
    } else {
      this.menu = { x: e.clientX, y: e.clientY, items }
    }
  }
  closeMenu() {
    this.menu = null
    this.menuAnchor = null
  }

  // ----- playback shortcuts -----
  play(ids: number[], start = -1, opts: { shuffle?: boolean; playlistId?: number; keepRadio?: boolean } = {}) {
    if (!ids.length) return
    if (!opts.keepRadio) this.radioSeed = null
    const shuffle = opts.shuffle ?? this.player.shuffle
    Go.PlayTracks(ids, start, shuffle, opts.playlistId ?? 0)
  }
  async togglePlaylist(id: number, shuffle?: boolean) {
    if (this.player.playlistId === id && this.player.queueLen > 0) {
      if (this.player.playing) Go.Pause()
      else Go.Resume()
      return
    }
    const ids = ((await Go.GetPlaylistTrackIDs(id)) as number[]) ?? []
    this.play(ids, -1, { playlistId: id, shuffle })
  }
  toggleLikedSongs() {
    if (this.player.playlistId === -1 && this.player.queueLen > 0) {
      if (this.player.playing) Go.Pause()
      else Go.Resume()
      return
    }
    const ids = this.tracks.filter((t) => t.liked).map((t) => t.id)
    this.play(ids, -1, { playlistId: -1 })
  }
  startRadio(seedTrack: Track, mode: 'all' | 'familiar' | 'rediscover' | 'artist' = 'all') {
    const mix = buildRadioMix(seedTrack, this.tracks, this.playlistTrackMap, mode, 25)
    this.radioSeed = { trackId: seedTrack.id, mode }
    this.play(
      mix.map((x) => x.id),
      0,
      { shuffle: false, keepRadio: true },
    )
    this.toast(`Started radio mix for “${seedTrack.title}”`)
  }
  setRadioMode(mode: 'all' | 'familiar' | 'rediscover' | 'artist') {
    if (!this.radioSeed) return
    const seedTrack = this.byId.get(this.radioSeed.trackId) ?? this.current
    if (!seedTrack) return
    this.radioSeed = { trackId: seedTrack.id, mode }
    const mix = buildRadioMix(seedTrack, this.tracks, this.playlistTrackMap, mode, 25)
    const curId = this.player.trackId || seedTrack.id
    const upcomingIds = mix.map((x) => x.id).filter((id) => id !== curId)
    Go.ClearQueue()
    if (upcomingIds.length > 0) Go.AddToQueue(upcomingIds)
  }
  playInstant(id: number) {
    Go.PlayInstant(id)
  }
  toggle() {
    Go.TogglePlayPause()
  }

  async toggleLike(id: number) {
    await Go.ToggleLike(id)
  }

  // ----- WebAudio fallback engine (used on Windows) -----
  webAudio: HTMLAudioElement | null = null
  audioCtx: AudioContext | null = null
  eqFilters: BiquadFilterNode[] = []
  eqEnabled = false
  eqValues: number[] = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]

  ensureWebAudio() {
    if (this.webAudio) return this.webAudio
    const a = new Audio()
    a.preload = 'auto'
    a.crossOrigin = 'anonymous'
    a.addEventListener('loadedmetadata', () => {
      ;(Go as any).OnAudioLoaded?.(Number.isFinite(a.duration) ? a.duration : 0)
    })
    a.addEventListener('timeupdate', () => {
      ;(Go as any).OnAudioTime?.(a.currentTime || 0, Number.isFinite(a.duration) ? a.duration : 0)
    })
    a.addEventListener('play', () => {
      if (this.audioCtx && this.audioCtx.state === 'suspended') {
        this.audioCtx.resume().catch(() => {})
      }
      ;(Go as any).OnAudioPause?.(false)
    })
    a.addEventListener('pause', () => {
      if (!a.ended) (Go as any).OnAudioPause?.(true)
    })
    a.addEventListener('ended', () => {
      ;(Go as any).OnAudioEOF?.()
    })
    a.addEventListener('error', () => {
      ;(Go as any).OnAudioError?.(a.error?.message || 'playback error')
    })
    try {
      const ctx = new AudioContext()
      const src = ctx.createMediaElementSource(a)
      const freqs = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
      const filters = freqs.map((f, i) => {
        const node = ctx.createBiquadFilter()
        node.type = 'peaking'
        node.frequency.value = f
        node.Q.value = 1.4
        node.gain.value = this.eqEnabled ? (this.eqValues[i] ?? 0) : 0
        return node
      })
      let prev: AudioNode = src
      for (const flt of filters) {
        prev.connect(flt)
        prev = flt
      }
      prev.connect(ctx.destination)
      this.audioCtx = ctx
      this.eqFilters = filters
    } catch {}
    this.webAudio = a
    return a
  }

  handleWebPlayerCmd(msg: any) {
    if (!msg || !msg.cmd) return
    const a = this.ensureWebAudio()
    switch (msg.cmd) {
      case 'load':
        a.src = msg.url
        a.play().catch((err) => (Go as any).OnAudioError?.(String(err)))
        break
      case 'stop':
        a.pause()
        a.removeAttribute('src')
        a.load()
        break
      case 'pause':
        if (msg.pause) a.pause()
        else a.play().catch(() => {})
        break
      case 'seek':
        if (typeof msg.sec === 'number' && Number.isFinite(msg.sec)) {
          try {
            a.currentTime = msg.sec
          } catch {}
        }
        break
      case 'volume':
        a.volume = Math.max(0, Math.min(1, Number(msg.vol) || 0))
        break
      case 'eq':
        this.eqEnabled = !!msg.enabled
        if (Array.isArray(msg.eq)) this.eqValues = msg.eq
        this.eqFilters.forEach((flt, i) => {
          flt.gain.value = this.eqEnabled ? (this.eqValues[i] ?? 0) : 0
        })
        break
    }
  }

  updateMediaSession(s: PlayerState) {
    if (!('mediaSession' in navigator)) return
    const ms = navigator.mediaSession
    ms.playbackState = s.playing && s.loaded ? 'playing' : s.loaded ? 'paused' : 'none'
    const tr = this.byId.get(s.trackId)
    if (tr && typeof MediaMetadata !== 'undefined') {
      ms.metadata = new MediaMetadata({
        title: tr.title,
        artist: tr.artist,
        album: tr.album,
        artwork: tr.hasCover ? [{ src: `/cover/${tr.id}`, sizes: '512x512', type: 'image/jpeg' }] : [],
      })
    }
  }

  async init() {
    EventsOn('library:changed', () => this.loadTracks())
    EventsOn('playlists:changed', () => this.loadPlaylists())
    EventsOn('nav:back', () => this.back())
    EventsOn('nav:forward', () => this.forward())
    EventsOn('window:maximised', (m: boolean) => (this.windowMaximised = m))
    EventsOn('webplayer:cmd', (msg: any) => this.handleWebPlayerCmd(msg))
    EventsOn('player:state', (s: PlayerState) => {
      this.player = s
      this.updateMediaSession(s)
    })
    if ('mediaSession' in navigator) {
      try {
        navigator.mediaSession.setActionHandler('play', () => Go.Resume())
        navigator.mediaSession.setActionHandler('pause', () => Go.Pause())
        navigator.mediaSession.setActionHandler('previoustrack', () => Go.Previous())
        navigator.mediaSession.setActionHandler('nexttrack', () => Go.Next())
        navigator.mediaSession.setActionHandler('seekto', (d) => {
          if (typeof d.seekTime === 'number') Go.Seek(d.seekTime)
        })
      } catch {}
    }
    EventsOn('player:queue', (q: { items: QueueEntry[] | null; index: number }) => (this.queue = q.items ?? []))
    EventsOn('library:scan', (s: { scanning: boolean; done: number; total: number }) => (this.scan = s))
    EventsOn('settings:changed', (s: Settings) => (this.settings = s))
    EventsOn('app:toast', (t: string) => this.toast(t))
    EventsOn('ytdlp:status', (s: YtdlpStatus) => (this.ytdlp = s))
    EventsOn('track:liked', (id: number, liked: boolean) => {
      const t = this.byId.get(id)
      if (!t) return
      const nt = { ...t, liked }
      this.byId = new Map(this.byId).set(id, nt)
      this.tracks = this.tracks.map((x) => (x.id === id ? nt : x))
    })
    EventsOn('download:update', (j: DownloadJob) => {
      const i = this.downloads.findIndex((d) => d.id === j.id)
      if (i >= 0) {
        const n = this.downloads.slice()
        n[i] = j
        this.downloads = n
      } else this.downloads = [...this.downloads, j]
    })
    EventsOn('download:cleared', async () => (this.downloads = ((await Go.GetDownloads()) as unknown as DownloadJob[]) ?? []))

    await Promise.all([this.loadTracks(), this.loadPlaylists()])
    this.settings = (await Go.GetSettings()) as unknown as Settings
    this.player = (await Go.GetPlayerState()) as unknown as PlayerState
    const q = (await Go.GetQueue()) as unknown as { items: QueueEntry[] | null }
    this.queue = q.items ?? []
    this.downloads = ((await Go.GetDownloads()) as unknown as DownloadJob[]) ?? []
    this.ytdlp = (await Go.GetYtdlpStatus()) as unknown as YtdlpStatus
    try {
      const v = await Go.GetVersion()
      if (v) this.version = v
    } catch {}
    this.loaded = true
  }
}

export const core = new Core()
