import type { Track } from './types'

export function fmtTime(sec: number): string {
  if (!isFinite(sec) || sec < 0) sec = 0
  const s = Math.floor(sec)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const r = s % 60
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`
  return `${m}:${String(r).padStart(2, '0')}`
}

export function fmtDuration(sec: number): string {
  const m = Math.round(sec / 60)
  if (m < 60) return `${m} min`
  const h = Math.floor(m / 60)
  return `${h} hr ${m % 60} min`
}

export function coverUrl(id: number): string {
  return `/cover/${id}`
}

export function norm(s: string): string {
  return s.toLocaleLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '').trim()
}

const ARTIST_SPLIT_RE = /\s*(?:,|;|\/|\s&\s|\sfeat\.?\s|\sft\.?\s|\sfeaturing\s)\s*/i
const TOPIC_SUFFIX_RE = /\s*-\s*topic\s*$/i

export function splitArtists(raw: string): string[] {
  if (!raw) return ['Unknown Artist']
  const parts = raw.split(ARTIST_SPLIT_RE)
  const seen = new Set<string>()
  const out: string[] = []
  for (let p of parts) {
    p = p.replace(TOPIC_SUFFIX_RE, '').trim()
    if (!p) continue
    const k = norm(p)
    if (!seen.has(k)) {
      seen.add(k)
      out.push(p)
    }
  }
  return out.length ? out : ['Unknown Artist']
}

export function hasArtist(t: Track, artistName: string): boolean {
  const target = norm(artistName)
  return splitArtists(t.artist).some((a) => norm(a) === target)
}

export function matches(t: Track, q: string): boolean {
  const n = norm(q)
  return norm(t.title).includes(n) || norm(t.artist).includes(n) || norm(t.album).includes(n)
}

export interface Group {
  key: string
  name: string
  sub: string
  tracks: Track[]
  coverId: number
  coverIds: number[]
}

export function groupBy(tracks: Track[], field: 'album' | 'artist'): Group[] {
  const m = new Map<string, Group>()
  if (field === 'artist') {
    for (const t of tracks) {
      for (const a of splitArtists(t.artist)) {
        if (!a || a === 'Unknown Artist') continue
        const key = norm(a)
        let g = m.get(key)
        if (!g) {
          g = { key, name: a, sub: '', tracks: [], coverId: 0, coverIds: [] }
          m.set(key, g)
        }
        g.tracks.push(t)
        if (t.hasCover) {
          if (!g.coverId) g.coverId = t.id
          if (g.coverIds.length < 4) g.coverIds.push(t.id)
        }
      }
    }
  } else {
    for (const t of tracks) {
      const name = (t.album || '').trim()
      if (!name) continue
      const key = norm(name)
      let g = m.get(key)
      if (!g) {
        const primary = splitArtists(t.artist)[0] ?? t.artist
        g = { key, name, sub: primary, tracks: [], coverId: 0, coverIds: [] }
        m.set(key, g)
      }
      g.tracks.push(t)
      if (t.hasCover) {
        if (!g.coverId) g.coverId = t.id
        if (g.coverIds.length < 4) g.coverIds.push(t.id)
      }
    }
    for (const g of m.values()) {
      const counts = new Map<string, { name: string; count: number }>()
      for (const t of g.tracks) {
        for (const a of splitArtists(t.artist)) {
          const k = norm(a)
          const cur = counts.get(k)
          if (cur) cur.count++
          else counts.set(k, { name: a, count: 1 })
        }
      }
      const sorted = [...counts.values()].sort((a, b) => b.count - a.count)
      if (sorted.length === 1) g.sub = sorted[0].name
      else if (sorted.length > 1 && sorted[0].count >= Math.ceil(g.tracks.length * 0.6)) g.sub = sorted[0].name
      else if (sorted.length > 1) g.sub = `${sorted[0].name} & others`
    }
  }
  return [...m.values()].sort((a, b) => a.name.localeCompare(b.name))
}

const STOP_WORDS = new Set([
  'the', 'and', 'for', 'from', 'with', 'feat', 'ver', 'version', 'original', 'soundtrack', 'ost', 'music', 'song', 'theme', 'edit', 'mix', 'remaster', 'remastered',
])

function titleTokens(title: string): string[] {
  return norm(title)
    .split(/[^\p{L}\p{N}]+/u)
    .filter((w) => w.length >= 3 && !STOP_WORDS.has(w))
}

export function recommendForPlaylist(
  playlistTracks: Track[],
  allTracks: Track[],
  seed = 0,
  limit = 8,
  playlistTrackMap: Record<number, number[]> = {},
): Track[] {
  const inPlaylist = new Set(playlistTracks.map((t) => t.id))
  const candidates = allTracks.filter((t) => !inPlaylist.has(t.id))
  if (candidates.length === 0) return []

  const artistFreq = new Map<string, number>()
  const albumFreq = new Map<string, number>()
  const tokenFreq = new Map<string, number>()
  const cooccurFreq = new Map<number, number>()

  for (const t of playlistTracks) {
    for (const a of splitArtists(t.artist)) {
      if (a && a !== 'Unknown Artist') {
        const k = norm(a)
        artistFreq.set(k, (artistFreq.get(k) ?? 0) + 1)
      }
    }
    if (t.album) {
      const ak = norm(t.album)
      albumFreq.set(ak, (albumFreq.get(ak) ?? 0) + 1)
    }
    for (const tok of titleTokens(t.title)) {
      tokenFreq.set(tok, (tokenFreq.get(tok) ?? 0) + 1)
    }
  }

  // Playlist co-occurrence across other playlists in the library
  for (const tids of Object.values(playlistTrackMap)) {
    if (!Array.isArray(tids) || tids.length < 2) continue
    let overlap = 0
    for (const id of tids) {
      if (inPlaylist.has(id)) overlap++
    }
    if (overlap > 0) {
      const weight = Math.min(overlap, 4)
      for (const id of tids) {
        if (!inPlaylist.has(id)) {
          cooccurFreq.set(id, (cooccurFreq.get(id) ?? 0) + weight)
        }
      }
    }
  }

  const scored = candidates.map((c) => {
    let score = 0
    for (const a of splitArtists(c.artist)) {
      const f = artistFreq.get(norm(a))
      if (f) score += 55 * Math.min(f, 3)
    }
    const co = cooccurFreq.get(c.id)
    if (co) score += 42 * Math.min(co, 4)
    if (c.album) {
      const f = albumFreq.get(norm(c.album))
      if (f) score += 38 * Math.min(f, 3)
    }
    for (const tok of titleTokens(c.title)) {
      const f = tokenFreq.get(tok)
      if (f) score += 16 * Math.min(f, 2)
    }
    if (c.liked) score += 12
    score += Math.min(c.playCount * 3, 18)

    const hash = (((c.id * 2654435761) ^ ((seed + 1) * 1013904223)) >>> 0) / 4294967295
    const jitter = seed === 0 ? (playlistTracks.length > 0 ? 28 : 50) : 260
    score += hash * jitter
    return { track: c, score }
  })

  scored.sort((a, b) => b.score - a.score)
  return scored.slice(0, limit).map((x) => x.track)
}

export function buildRadioMix(
  seedTrack: Track,
  allTracks: Track[],
  playlistTrackMap: Record<number, number[]> = {},
  mode: 'all' | 'familiar' | 'rediscover' | 'artist' = 'all',
  limit = 25,
): Track[] {
  const seedArtists = new Set(splitArtists(seedTrack.artist).map((a) => norm(a)))
  const seedAlbum = seedTrack.album ? norm(seedTrack.album) : ''
  const seedTokens = new Set(titleTokens(seedTrack.title))

  const cooccur = new Map<number, number>()
  for (const tids of Object.values(playlistTrackMap)) {
    if (Array.isArray(tids) && tids.includes(seedTrack.id)) {
      for (const id of tids) {
        if (id !== seedTrack.id) cooccur.set(id, (cooccur.get(id) ?? 0) + 1)
      }
    }
  }

  let pool = allTracks.filter((t) => t.id !== seedTrack.id)
  if (mode === 'familiar') {
    const fam = pool.filter((t) => t.playCount > 0 || t.liked)
    if (fam.length >= 6) pool = fam
  } else if (mode === 'rediscover') {
    const now = Math.floor(Date.now() / 1000)
    const deep = pool.filter((t) => t.playCount <= 1 || now - t.lastPlayed > 7 * 86400)
    if (deep.length >= 6) pool = deep
  } else if (mode === 'artist') {
    const sameArt = pool.filter((t) => splitArtists(t.artist).some((a) => seedArtists.has(norm(a))))
    if (sameArt.length >= 4) pool = sameArt
  }

  const scored = pool.map((c) => {
    let score = 0
    const cArtists = splitArtists(c.artist).map((a) => norm(a))
    const sharedArt = cArtists.filter((a) => seedArtists.has(a)).length
    if (sharedArt > 0) score += mode === 'artist' ? 140 : 65 * sharedArt
    const co = cooccur.get(c.id) ?? 0
    if (co > 0) score += 50 * Math.min(co, 3)
    if (seedAlbum && c.album && norm(c.album) === seedAlbum) score += 45
    for (const tok of titleTokens(c.title)) {
      if (seedTokens.has(tok)) score += 20
    }
    if (mode === 'familiar') {
      score += Math.min(c.playCount * 10, 60) + (c.liked ? 35 : 0)
    } else if (mode === 'rediscover') {
      if (c.playCount === 0) score += 45
      if (c.liked) score += 20
    } else {
      if (c.liked) score += 18
      score += Math.min(c.playCount * 4, 24)
    }
    const h = (((c.id * 2654435761) ^ (seedTrack.id * 1013904223)) >>> 0) / 4294967295
    score += h * 32
    return { track: c, score }
  })

  scored.sort((a, b) => b.score - a.score)
  return [seedTrack, ...scored.slice(0, Math.max(0, limit - 1)).map((x) => x.track)]
}

export function buildQuickPicks(allTracks: Track[], seed = 0): Track[] {
  if (allTracks.length === 0) return []
  const now = Math.floor(Date.now() / 1000)
  const scored = allTracks.map((t) => {
    let s = 0
    if (t.playCount > 0) s += Math.min(t.playCount * 14, 90)
    if (t.liked) s += 45
    if (t.lastPlayed > 0) {
      const ageDays = Math.max(0, (now - t.lastPlayed) / 86400)
      s += Math.max(0, 60 - ageDays * 3)
    }
    const h = (((t.id * 2654435761) ^ ((seed + 1) * 1013904223)) >>> 0) / 4294967295
    s += h * 40
    return { track: t, score: s }
  })
  scored.sort((a, b) => b.score - a.score)

  // Spread same-artist tracks slightly so the initial strip has great variety while preserving order
  const out: Track[] = []
  const deferred: Track[] = []
  const recentArtistCount = new Map<string, number>()
  for (const item of scored) {
    const primary = norm(splitArtists(item.track.artist)[0] ?? '')
    const cnt = recentArtistCount.get(primary) ?? 0
    if (cnt < 2) {
      out.push(item.track)
      recentArtistCount.set(primary, cnt + 1)
    } else {
      deferred.push(item.track)
    }
  }
  return [...out, ...deferred]
}

export function samplePool(pool: Track[], seed = 0, limit = 12): Track[] {
  if (pool.length === 0) return []
  const s = (seed + 1) * 1013904223
  return pool
    .map((t) => {
      const h = (((t.id * 2654435761) ^ s) >>> 0)
      return { t, h }
    })
    .sort((a, b) => a.h - b.h)
    .slice(0, limit)
    .map((x) => x.t)
}

export function shortId(x: unknown): string {
  return encodeURIComponent(String(x))
}

export function clamp(v: number, a: number, b: number): number {
  return Math.min(b, Math.max(a, v))
}

export const EQ_BANDS = ['31', '62', '125', '250', '500', '1k', '2k', '4k', '8k', '16k']
export const EQ_PRESETS: Record<string, number[]> = {
  Flat: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  'Bass boost': [6, 5, 4, 2, 0, 0, 0, 0, 0, 0],
  'Treble boost': [0, 0, 0, 0, 0, 1, 3, 4, 5, 6],
  Vocal: [-2, -2, -1, 1, 3, 3, 2, 1, 0, -1],
  Rock: [4, 3, 1, -1, -2, 0, 2, 3, 4, 4],
  Pop: [-1, 1, 3, 4, 3, 0, -1, -1, 1, 2],
  Electronic: [5, 4, 1, 0, -2, 2, 1, 2, 4, 5],
  Acoustic: [3, 3, 2, 1, 1, 1, 2, 2, 3, 2],
}

function createSmoothAxisScroller(node: HTMLElement, axis: 'x' | 'y') {
  let target = 0
  let current = 0
  let rafId = 0
  let lastTime = 0

  const getPos = () => (axis === 'x' ? node.scrollLeft : node.scrollTop)
  const setPos = (v: number) => {
    if (axis === 'x') node.scrollLeft = v
    else node.scrollTop = v
  }
  const getMax = () =>
    axis === 'x'
      ? Math.max(0, node.scrollWidth - node.clientWidth)
      : Math.max(0, node.scrollHeight - node.clientHeight)

  function step(now: number) {
    const actual = getPos()
    // If a massive external jump occurred (e.g. user clicked scrollbar track far away), sync immediately
    if (Math.abs(actual - current) > 60) {
      current = actual
      target = actual
      rafId = 0
      lastTime = 0
      return
    }

    const max = getMax()
    if (target > max) target = max
    if (target < 0) target = 0

    const dt = lastTime ? Math.min(50, Math.max(1, now - lastTime)) : 16.6
    lastTime = now

    const diff = target - current
    if (Math.abs(diff) < 0.4) {
      current = target
      setPos(target)
      rafId = 0
      lastTime = 0
      return
    }

    // Universal frame-rate-independent smoothing (adapts to 60Hz, 144Hz, 280Hz & multi-monitors)
    const alpha = 1 - Math.exp(-dt / 50)
    current += diff * alpha
    setPos(current)
    rafId = requestAnimationFrame(step)
  }

  function addDelta(rawDelta: number, deltaMode: number): boolean {
    const max = getMax()
    if (max <= 0) return false

    const actual = getPos()
    if (!rafId || Math.abs(actual - current) > 40) {
      current = actual
      target = actual
    }

    let px = rawDelta
    if (deltaMode === 1) {
      px *= 38
    } else if (deltaMode === 2) {
      const page = axis === 'x' ? node.clientWidth : node.clientHeight
      px *= page * 0.85
    } else if (Math.abs(px) > 0 && Math.abs(px) < 75) {
      px *= 1.45
    }

    const nextTarget = Math.min(max, Math.max(0, target + px))
    if (nextTarget === target && Math.abs(target - current) < 0.5) {
      return false
    }

    target = nextTarget
    if (!rafId) {
      lastTime = performance.now()
      rafId = requestAnimationFrame(step)
    }
    return true
  }

  function stop() {
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = 0
      lastTime = 0
    }
    current = getPos()
    target = current
  }

  function isAnimating() {
    return rafId !== 0
  }

  return { addDelta, stop, isAnimating }
}

/**
 * Svelte action: Click-to-activate smooth horizontal wheel scrolling on shelves/strips.
 * Activates when clicking inside the segment or the space just above the tracks (top margin 32px),
 * and stops when the mouse is moved outside that segment area.
 */
export function horizontalScroll(node: HTMLElement) {
  const scroller = createSmoothAxisScroller(node, 'x')
  let active = false

  const TOP_MARGIN = 32
  const BOTTOM_MARGIN = 14
  const SIDE_MARGIN = 16

  function isInsideZone(clientX: number, clientY: number): boolean {
    const rect = node.getBoundingClientRect()
    return (
      clientX >= rect.left - SIDE_MARGIN &&
      clientX <= rect.right + SIDE_MARGIN &&
      clientY >= rect.top - TOP_MARGIN &&
      clientY <= rect.bottom + BOTTOM_MARGIN
    )
  }

  function onWindowMouseDown(e: MouseEvent) {
    const inside = isInsideZone(e.clientX, e.clientY)
    active = inside
    if (inside) {
      scroller.stop()
    }
  }

  function onWindowMouseMove(e: MouseEvent) {
    if (!active) return
    if (!isInsideZone(e.clientX, e.clientY)) {
      active = false
    }
  }

  function onWheel(e: WheelEvent) {
    if (!active) return
    if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) return
    if (e.shiftKey || e.ctrlKey) return
    const maxScroll = node.scrollWidth - node.clientWidth
    if (maxScroll <= 0) return
    if (e.deltaY === 0) return

    e.preventDefault()
    e.stopPropagation()
    scroller.addDelta(e.deltaY, e.deltaMode)
  }

  window.addEventListener('mousedown', onWindowMouseDown, { capture: true })
  window.addEventListener('mousemove', onWindowMouseMove, { passive: true })
  node.addEventListener('wheel', onWheel, { passive: false })

  return {
    destroy() {
      scroller.stop()
      window.removeEventListener('mousedown', onWindowMouseDown, { capture: true })
      window.removeEventListener('mousemove', onWindowMouseMove)
      node.removeEventListener('wheel', onWheel)
    },
  }
}

/**
 * Svelte action: High-refresh-rate (280Hz+) smooth vertical mouse wheel scrolling.
 */
export function smoothScroll(node: HTMLElement) {
  const scroller = createSmoothAxisScroller(node, 'y')

  function onMouseDown() {
    scroller.stop()
  }

  function onWheel(e: WheelEvent) {
    if (e.defaultPrevented) return
    if (e.ctrlKey || e.shiftKey) return
    if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) return
    if (e.deltaY === 0) return
    if (node.scrollHeight <= node.clientHeight) return

    e.preventDefault()
    scroller.addDelta(e.deltaY, e.deltaMode)
  }

  node.addEventListener('mousedown', onMouseDown, { capture: true, passive: true })
  node.addEventListener('wheel', onWheel, { passive: false })

  return {
    destroy() {
      scroller.stop()
      node.removeEventListener('mousedown', onMouseDown, { capture: true })
      node.removeEventListener('wheel', onWheel)
    },
  }
}

