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

export function recommendForPlaylist(playlistTracks: Track[], allTracks: Track[], seed = 0, limit = 8): Track[] {
  const inPlaylist = new Set(playlistTracks.map((t) => t.id))
  const candidates = allTracks.filter((t) => !inPlaylist.has(t.id))
  if (candidates.length === 0) return []

  const artistFreq = new Map<string, number>()
  const albumFreq = new Map<string, number>()
  const tokenFreq = new Map<string, number>()

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

  const scored = candidates.map((c) => {
    let score = 0
    for (const a of splitArtists(c.artist)) {
      const f = artistFreq.get(norm(a))
      if (f) score += 50 * Math.min(f, 3)
    }
    if (c.album) {
      const f = albumFreq.get(norm(c.album))
      if (f) score += 35 * Math.min(f, 3)
    }
    for (const tok of titleTokens(c.title)) {
      const f = tokenFreq.get(tok)
      if (f) score += 14 * Math.min(f, 2)
    }
    if (c.liked) score += 10
    score += Math.min(c.playCount * 2, 12)

    const hash = (((c.id * 2654435761) ^ ((seed + 1) * 1013904223)) >>> 0) / 4294967295
    const jitter = seed === 0 ? (playlistTracks.length > 0 ? 28 : 50) : 260
    score += hash * jitter
    return { track: c, score }
  })

  scored.sort((a, b) => b.score - a.score)
  return scored.slice(0, limit).map((x) => x.track)
}

export function samplePool(pool: Track[], seed = 0, limit = 12): Track[] {
  if (pool.length <= limit) return pool
  if (seed === 0) return pool.slice(0, limit)
  return pool
    .map((t, idx) => {
      const h = (((t.id * 2654435761 + idx * 374761393) ^ (seed * 1013904223)) >>> 0)
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
