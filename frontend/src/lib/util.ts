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

export function coverUrl(id: string, size?: number): string {
  return size ? `/cover/${id}?s=${size}` : `/cover/${id}`
}

export function fallbackYtThumb(e: Event) {
  const img = e.currentTarget as HTMLImageElement | null
  if (!img) return
  if (e.type === 'load' && !(img.naturalWidth > 0 && img.naturalWidth <= 120)) return
  const src = img.currentSrc || img.src
  if (src.includes('/maxresdefault.jpg')) {
    img.src = src.replace('/maxresdefault.jpg', '/sddefault.jpg')
  } else if (src.includes('/sddefault.jpg')) {
    img.src = src.replace('/sddefault.jpg', '/hqdefault.jpg')
  }
}

export function norm(s: string): string {
  return s.toLocaleLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '').trim()
}

const ARTIST_SPLIT_RE = /\s*(?:,|、|;|\/|&|\s+x\s+|×|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b)\s*/i
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
  coverId: string
  coverIds: string[]
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
          g = { key, name: a, sub: '', tracks: [], coverId: '', coverIds: [] }
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
        g = { key, name, sub: primary, tracks: [], coverId: '', coverIds: [] }
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
  return metaTokens(title)
}

function metaTokens(title: string, album = ''): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const w of norm(`${title} ${album}`).split(/[^\p{L}\p{N}]+/u)) {
    if (!w || STOP_WORDS.has(w)) continue
    if (w.length < 3 && !(w.length >= 2 && /[^\x00-\x7F]/.test(w))) continue
    if (!seen.has(w)) {
      seen.add(w)
      out.push(w)
    }
  }
  return out
}

// ---------- genres (multi-genre with sub-genres) ----------

export interface GenreNode { name: string; parent: string }
export interface GenreLink { a: string; b: string; w: number }

const CONTEXT_GENRES = new Set([
  'Soundtrack',
  'Film Score',
  'Video Game Music',
  'Anime',
  'TV Soundtrack',
  'Musical Theatre',
  'Instrumental',
])

let PARENT = new Map<string, string>()
let LINKS = new Map<string, [string, number][]>()
let CLASHES = new Map<string, Map<string, number>>()
const EXPANDED = new Map<string, Map<string, number>>()
const PARENT_DECAY = 0.6
const CLASH_HOP_DECAY = 0.92

/** Loads the genre tree from the backend (single source of truth for aliases, parents, cousin links, and clashes). */
export function setGenreTaxonomy(nodes: GenreNode[], links: GenreLink[], clashes: GenreLink[] = []) {
  PARENT = new Map(nodes.map((n) => [n.name, n.parent]))
  LINKS = new Map()
  for (const l of links) {
    if (!LINKS.has(l.a)) LINKS.set(l.a, [])
    if (!LINKS.has(l.b)) LINKS.set(l.b, [])
    LINKS.get(l.a)!.push([l.b, l.w])
    LINKS.get(l.b)!.push([l.a, l.w])
  }
  CLASHES = new Map()
  for (const c of clashes) {
    if (!CLASHES.has(c.a)) CLASHES.set(c.a, new Map())
    if (!CLASHES.has(c.b)) CLASHES.set(c.b, new Map())
    CLASHES.get(c.a)!.set(c.b, Math.max(CLASHES.get(c.a)!.get(c.b) ?? 0, c.w))
    CLASHES.get(c.b)!.set(c.a, Math.max(CLASHES.get(c.b)!.get(c.a) ?? 0, c.w))
  }
  EXPANDED.clear()
}

/** A genre plus everything it implies: itself 1.0, parent 0.6, grandparent 0.36 … and cousins by link weight. */
function expandGenre(g: string): Map<string, number> {
  let m = EXPANDED.get(g)
  if (m) return m
  m = new Map<string, number>()
  let w = 1
  for (let cur: string | undefined = g, guard = 0; cur && guard < 6; cur = PARENT.get(cur), guard++) {
    if ((m.get(cur) ?? 0) < w) m.set(cur, w)
    for (const [other, lw] of LINKS.get(cur) ?? []) {
      if ((m.get(other) ?? 0) < w * lw) m.set(other, w * lw)
    }
    w *= PARENT_DECAY
  }
  EXPANDED.set(g, m)
  return m
}

function pairPosSim(ga: string, gb: string): number {
  const ea = expandGenre(ga)
  const eb = expandGenre(gb)
  let best = 0
  for (const [node, wa] of ea) {
    const wb = eb.get(node)
    if (wb !== undefined && wa * wb > best) best = wa * wb
  }
  return best
}

function pairClash(ga: string, gb: string): number {
  if (pairPosSim(ga, gb) >= 0.15) return 0
  let best = 0
  let wa = 1
  for (let curA: string | undefined = ga, gaGuard = 0; curA && gaGuard < 6; curA = PARENT.get(curA), gaGuard++) {
    const mapA = CLASHES.get(curA)
    if (mapA) {
      let wb = 1
      for (let curB: string | undefined = gb, gbGuard = 0; curB && gbGuard < 6; curB = PARENT.get(curB), gbGuard++) {
        const cw = mapA.get(curB)
        if (cw !== undefined && wa * wb * cw > best) best = wa * wb * cw
        wb *= CLASH_HOP_DECAY
      }
    }
    wa *= CLASH_HOP_DECAY
  }
  return best
}

/** Style clash penalty 0..1 between two genre lists (ignores pure media-context tags and matched hybrid styles). */
export function genreClash(a: string[], b: string[]): number {
  const styleA = a.filter((g) => !CONTEXT_GENRES.has(g))
  const styleB = b.filter((g) => !CONTEXT_GENRES.has(g))
  if (!styleA.length || !styleB.length) return 0

  let best = 0
  for (const ga of styleA) {
    if (styleB.some((y) => pairPosSim(ga, y) >= 0.5)) continue
    for (const gb of styleB) {
      if (styleA.some((x) => pairPosSim(x, gb) >= 0.5)) continue
      const c = pairClash(ga, gb)
      if (c > best) best = c
    }
  }
  return best
}

/** Similarity 0..1 between two genre lists, weighting style over context and subtracting style clashes. */
export function genreSim(a: string[], b: string[]): number {
  if (!a.length || !b.length) return 0
  let rawPos = 0
  for (const ga of a) {
    for (const gb of b) {
      const p = pairPosSim(ga, gb)
      if (p > rawPos) rawPos = p
    }
  }

  const styleA = a.filter((g) => !CONTEXT_GENRES.has(g))
  const styleB = b.filter((g) => !CONTEXT_GENRES.has(g))
  const ctxA = a.filter((g) => CONTEXT_GENRES.has(g))
  const ctxB = b.filter((g) => CONTEXT_GENRES.has(g))

  let stylePos = 0
  for (const ga of styleA) {
    for (const gb of styleB) {
      const p = pairPosSim(ga, gb)
      if (p > stylePos) stylePos = p
    }
  }
  let ctxPos = 0
  for (const ga of ctxA) {
    for (const gb of ctxB) {
      const p = pairPosSim(ga, gb)
      if (p > ctxPos) ctxPos = p
    }
  }

  let basePos = rawPos
  if (styleA.length && styleB.length && ctxA.length && ctxB.length) {
    basePos = stylePos * 0.75 + ctxPos * 0.25
  } else if (styleA.length && styleB.length) {
    basePos = Math.max(stylePos, rawPos)
  }

  const clash = genreClash(a, b)
  return Math.max(0, basePos - clash * 0.9)
}

export type GenreRelation = 'same' | 'related' | 'unknown' | 'clash'

export function genreRelation(a: string[], b: string[]): GenreRelation {
  if (!a.length || !b.length) return 'unknown'
  const s = genreSim(a, b)
  const c = genreClash(a, b)
  if (c >= 0.45 && s < 0.35) return 'clash'
  return s >= 0.5 ? 'same' : s >= 0.2 ? 'related' : 'clash'
}

/** Genres of a track, falling back to the dominant genres of the same artist's other tracks. */
export function effectiveGenres(t: Track, all: Track[]): string[] {
  if (t.genres?.length) return t.genres
  const artists = new Set(splitArtists(t.artist).map((a) => norm(a)))
  const counts = new Map<string, number>()
  for (const o of all) {
    if (!o.genres?.length) continue
    if (splitArtists(o.artist).some((a) => artists.has(norm(a)))) for (const g of o.genres) counts.set(g, (counts.get(g) ?? 0) + 1)
  }
  const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1])
  if (!sorted.length) return []
  return sorted.filter((e) => e[1] >= sorted[0][1] * 0.5).slice(0, 3).map((e) => e[0])
}

export function recommendForPlaylist(
  playlistTracks: Track[],
  allTracks: Track[],
  seed = 0,
  limit = 8,
  playlistTrackMap: Record<number, string[]> = {},
): Track[] {
  const inPlaylist = new Set(playlistTracks.map((t) => t.id))
  const candidates = allTracks.filter((t) => !inPlaylist.has(t.id))
  if (candidates.length === 0) return []

  const artistFreq = new Map<string, number>()
  const albumFreq = new Map<string, number>()
  const tokenFreq = new Map<string, number>()
  const cooccurFreq = new Map<string, number>()
  const genreFreq = new Map<string, number>()
  let genreTrackTotal = 0

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
    for (const tok of metaTokens(t.title, t.album)) {
      tokenFreq.set(tok, (tokenFreq.get(tok) ?? 0) + 1)
    }
    const eff = effectiveGenres(t, allTracks)
    if (eff.length) {
      genreTrackTotal++
      for (const g of eff) genreFreq.set(g, (genreFreq.get(g) ?? 0) + 1)
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

  // Genres that characterize the playlist (>= 20% of tagged tracks, at least the top one)
  const sortedG = [...genreFreq.entries()].sort((a, b) => b[1] - a[1])
  const topGenres: string[] = sortedG
    .filter((e, i) => i === 0 || e[1] >= Math.max(1, genreTrackTotal * 0.2))
    .map((e) => e[0])
  const weightedGenreAffinity = (gs: string[]): number => {
    if (!gs.length || genreTrackTotal === 0) return 0
    let w = 0
    for (const [pg, pw] of sortedG) w += pw * genreSim(gs, [pg])
    return Math.min(1, w / genreTrackTotal)
  }

  const scored = candidates.map((c) => {
    // Priority 1: File tags (artist, album, title/album tokens)
    let artistScore = 0
    for (const a of splitArtists(c.artist)) {
      const f = artistFreq.get(norm(a))
      if (f) artistScore += 120 * Math.min(f, 3)
    }
    artistScore = Math.min(artistScore, 360)

    let albumScore = 0
    if (c.album) {
      const f = albumFreq.get(norm(c.album))
      if (f) albumScore = Math.min(285, 95 * Math.min(f, 3))
    }

    let tokenScore = 0
    for (const tok of metaTokens(c.title, c.album)) {
      const f = tokenFreq.get(tok)
      if (f) tokenScore += 32 * Math.min(f, 2)
    }
    tokenScore = Math.min(tokenScore, 128)
    const tagScore = artistScore + albumScore + tokenScore

    // Priority 2: Genre similarity & style clash penalty
    const cGenres = effectiveGenres(c, allTracks)
    const simTop = topGenres.length && cGenres.length ? genreSim(cGenres, topGenres) : 0
    const clashTop = topGenres.length && cGenres.length ? genreClash(cGenres, topGenres) : 0
    const simAvg = weightedGenreAffinity(cGenres)
    const sim = Math.max(simTop, simAvg)
    const rel: GenreRelation =
      !topGenres.length || !cGenres.length
        ? 'unknown'
        : clashTop >= 0.45 && sim < 0.35
          ? 'clash'
          : sim >= 0.5
            ? 'same'
            : sim >= 0.2
              ? 'related'
              : 'clash'
    const genreScore = simTop * 90 + simAvg * 60 - clashTop * 80

    // Priority 3: Other signals (co-occurrence, likes, plays, jitter)
    const co = cooccurFreq.get(c.id) ?? 0
    const coScore = co > 0 ? 28 * Math.min(co, 4) : 0
    const popScore = (c.liked ? 12 : 0) + Math.min(c.playCount * 3, 18)
    const hash = (((hashId(c.id) * 2654435761) ^ ((seed + 1) * 1013904223)) >>> 0) / 4294967295
    const jitter = seed === 0 ? (playlistTracks.length > 0 ? 20 : 45) : 95
    const score = tagScore + genreScore + coScore + popScore + hash * jitter

    let tier = 0
    if (playlistTracks.length > 0) {
      if (artistScore > 0 || albumScore > 0) tier = 0
      else if (tokenScore > 0 && rel !== 'clash') tier = 1
      else if (rel === 'same') tier = 2
      else if (rel === 'related' || (tokenScore > 0 && rel === 'clash')) tier = 3
      else if (rel === 'unknown') tier = 4
      else tier = 5
    }
    return { track: c, score, tier }
  })

  // Strict priority order: file tags -> genre match -> other signals
  scored.sort((a, b) => a.tier - b.tier || b.score - a.score)
  return scored.slice(0, limit).map((x) => x.track)
}

export function buildRadioMix(
  seedTrack: Track,
  allTracks: Track[],
  playlistTrackMap: Record<number, string[]> = {},
  mode: 'all' | 'familiar' | 'rediscover' | 'artist' = 'all',
  limit = 25,
  seed = 0,
): Track[] {
  const seedArtists = new Set(splitArtists(seedTrack.artist).map((a) => norm(a)))
  const seedAlbum = seedTrack.album ? norm(seedTrack.album) : ''
  const seedTokens = new Set(metaTokens(seedTrack.title, seedTrack.album))
  const seedGenres = effectiveGenres(seedTrack, allTracks)

  const cooccur = new Map<string, number>()
  for (const tids of Object.values(playlistTrackMap)) {
    if (Array.isArray(tids) && tids.includes(seedTrack.id)) {
      for (const id of tids) {
        if (id !== seedTrack.id) cooccur.set(id, (cooccur.get(id) ?? 0) + 1)
      }
    }
  }

  const now = Math.floor(Date.now() / 1000)
  const pool = allTracks.filter((t) => t.id !== seedTrack.id)

  const tierOf = (sharedArt: number, sameAlbum: boolean, sharedTok: number, rel: GenreRelation): number => {
    if (mode === 'artist') {
      if (sharedArt > 0) return 0
      if (sameAlbum || sharedTok > 0) return 1
      return rel === 'same' ? 2 : rel === 'related' ? 3 : rel === 'unknown' ? 4 : 5
    }
    if (sharedArt > 0 || sameAlbum) return 0
    if (sharedTok > 0 && rel !== 'clash') return 1
    if (rel === 'same') return 2
    if (rel === 'related' || (sharedTok > 0 && rel === 'clash')) return 3
    if (rel === 'unknown') return 4
    return 5
  }

  const scored = pool.map((c) => {
    // Priority 1: File tags (artist, album, title/album tokens)
    const cArtists = splitArtists(c.artist).map((a) => norm(a))
    const sharedArt = cArtists.filter((a) => seedArtists.has(a)).length
    const sameAlbum = Boolean(seedAlbum && c.album && norm(c.album) === seedAlbum)
    let sharedTok = 0
    for (const tok of metaTokens(c.title, c.album)) {
      if (seedTokens.has(tok)) sharedTok++
    }
    const tagScore =
      (sharedArt > 0 ? (mode === 'artist' ? 240 : 120) * sharedArt : 0) +
      (sameAlbum ? 95 : 0) +
      Math.min(sharedTok, 3) * 32

    // Priority 2: Genre similarity & style clash penalty
    const cGenres = effectiveGenres(c, allTracks)
    const sim = seedGenres.length && cGenres.length ? genreSim(seedGenres, cGenres) : 0
    const clash = seedGenres.length && cGenres.length ? genreClash(seedGenres, cGenres) : 0
    const rel = genreRelation(seedGenres, cGenres)
    const genreScore = sim * 110 - clash * 80

    // Priority 3: Other signals (co-occurrence, familiarity/rediscovery, jitter)
    const co = cooccur.get(c.id) ?? 0
    let otherScore = co > 0 ? 28 * Math.min(co, 3) : 0
    if (mode === 'familiar') {
      otherScore += Math.min(c.playCount * 10, 60) + (c.liked ? 35 : 0)
    } else if (mode === 'rediscover') {
      if (c.playCount === 0) otherScore += 45
      if (c.liked) otherScore += 20
    } else {
      if (c.liked) otherScore += 18
      otherScore += Math.min(c.playCount * 4, 24)
    }
    const seedSalt = seed > 0 ? Math.imul(seed, 2246822519) : 0
    const h = (((hashId(c.id) * 2654435761) ^ ((hashId(seedTrack.id) * 1013904223) + seedSalt)) >>> 0) / 4294967295
    otherScore += h * (seed > 0 ? 180 : 24)

    let pref = 0
    if (mode === 'familiar' && !(c.playCount > 0 || c.liked)) pref = 1
    if (mode === 'rediscover' && !(c.playCount <= 1 || now - c.lastPlayed > 7 * 86400)) pref = 1

    let tier = tierOf(sharedArt, sameAlbum, sharedTok, rel)
    if (seed > 0 && mode !== 'artist' && tier <= 2) tier = 0
    return { track: c, score: tagScore + genreScore + otherScore, tier, pref }
  })

  scored.sort((a, b) => a.tier - b.tier || a.pref - b.pref || b.score - a.score)
  return [seedTrack, ...scored.slice(0, Math.max(0, limit - 1)).map((x) => x.track)]
}

/**
 * Quick picks: most listened songs first (play count, then recency), then liked-but-unplayed songs,
 * then the rest ordered by file tags (artist/album/title affinity), then genre affinity, then other signals.
 */
export function buildQuickPicks(
  allTracks: Track[],
  seed = 0,
  playlistTrackMap: Record<number, string[]> = {},
): Track[] {
  if (allTracks.length === 0) return []

  const inPlaylistCount = new Map<string, number>()
  for (const tids of Object.values(playlistTrackMap)) {
    if (!Array.isArray(tids)) continue
    for (const id of new Set(tids)) {
      inPlaylistCount.set(id, (inPlaylistCount.get(id) ?? 0) + 1)
    }
  }

  const played = allTracks
    .filter((t) => t.playCount > 0)
    .sort((a, b) => b.playCount - a.playCount || b.lastPlayed - a.lastPlayed || Number(b.liked) - Number(a.liked))
  const rest = allTracks.filter((t) => t.playCount === 0)
  const liked = rest.filter((t) => t.liked)
  const others = rest.filter((t) => !t.liked)

  // Taste profile from listening history, likes, and playlists (falls back to library if no activity yet)
  const artistWeight = new Map<string, number>()
  const albumWeight = new Map<string, number>()
  const tokenWeight = new Map<string, number>()
  const genreWeight = new Map<string, number>()
  let totalArtistW = 0
  let totalAlbumW = 0
  let totalTokenW = 0
  let totalGenreW = 0

  const addProfileTrack = (t: Track, w: number) => {
    for (const a of splitArtists(t.artist)) {
      if (a && a !== 'Unknown Artist') {
        const k = norm(a)
        artistWeight.set(k, (artistWeight.get(k) ?? 0) + w)
        totalArtistW += w
      }
    }
    if (t.album) {
      const ak = norm(t.album)
      albumWeight.set(ak, (albumWeight.get(ak) ?? 0) + w)
      totalAlbumW += w
    }
    for (const tok of metaTokens(t.title, t.album)) {
      tokenWeight.set(tok, (tokenWeight.get(tok) ?? 0) + w)
      totalTokenW += w
    }
    for (const g of effectiveGenres(t, allTracks)) {
      genreWeight.set(g, (genreWeight.get(g) ?? 0) + w)
      totalGenreW += w
    }
  }

  let hasActivity = false
  for (const t of allTracks) {
    const plW = inPlaylistCount.get(t.id) ?? 0
    const w = t.playCount * 2 + (t.liked ? 3 : 0) + Math.min(plW, 3)
    if (w > 0) {
      hasActivity = true
      addProfileTrack(t, w)
    }
  }
  if (!hasActivity) {
    for (const t of allTracks) addProfileTrack(t, 1)
  }

  const genreProfile = [...genreWeight.entries()]
  const genreStats = (gs: string[]): { pos: number; net: number; clashes: boolean } => {
    if (!gs.length || totalGenreW === 0) return { pos: 0, net: 0, clashes: false }
    let posW = 0
    let clashW = 0
    for (const [pg, pw] of genreProfile) {
      posW += pw * genreSim(gs, [pg])
      clashW += pw * genreClash(gs, [pg])
    }
    const pos = Math.min(1, posW / totalGenreW)
    const neg = Math.min(1, clashW / totalGenreW)
    const net = pos - neg * 0.9
    return { pos, net, clashes: neg >= 0.4 && net < 0.1 }
  }
  const jitter = (id: string) => (((hashId(id) * 2654435761) ^ ((seed + 1) * 1013904223)) >>> 0) / 4294967295

  const rankTail = (list: Track[]) =>
    list
      .map((t) => {
        // Priority 1: File tags (artist, album, title/album tokens)
        let artW = 0
        for (const a of splitArtists(t.artist)) {
          if (a && a !== 'Unknown Artist') artW = Math.max(artW, artistWeight.get(norm(a)) ?? 0)
        }
        const artAff = totalArtistW > 0 && artW > 0 ? Math.min(1, (artW * 4) / totalArtistW) : 0
        const albW = t.album ? albumWeight.get(norm(t.album)) ?? 0 : 0
        const albAff = totalAlbumW > 0 && albW > 0 ? Math.min(1, (albW * 5) / totalAlbumW) : 0
        let tokW = 0
        for (const tok of metaTokens(t.title, t.album)) tokW += tokenWeight.get(tok) ?? 0
        const tokAff = totalTokenW > 0 && tokW > 0 ? Math.min(1, (tokW * 6) / totalTokenW) : 0
        const hasTagMatch = artW > 0 || albW > 0 || tokW > 0
        const tagScore = artAff * 120 + albAff * 90 + tokAff * 45

        // Priority 2: Genre affinity (with style clash penalty)
        const gs = genreStats(effectiveGenres(t, allTracks))
        const genreScore = gs.net * 100

        // Priority 3: Other signals (playlist membership + jitter)
        const plBonus = Math.min(inPlaylistCount.get(t.id) ?? 0, 3) * 8
        const tier =
          hasTagMatch && !gs.clashes
            ? 0
            : !gs.clashes && gs.net >= 0.35
              ? 1
              : !gs.clashes && gs.pos > 0
                ? 2
                : !gs.clashes
                  ? 3
                  : hasTagMatch
                    ? 2
                    : 4
        return { t, tier, s: tagScore + genreScore + plBonus + jitter(t.id) * 22 }
      })
      .sort((a, b) => a.tier - b.tier || b.s - a.s)
      .map((x) => x.t)

  // Spread same-artist tracks so picks stay varied instead of flooding one artist.
  const spread = (list: Track[]): Track[] => {
    const out: Track[] = []
    const deferred: Track[] = []
    const counts = new Map<string, number>()
    for (const t of list) {
      const primary = norm(splitArtists(t.artist)[0] ?? '')
      const c = counts.get(primary) ?? 0
      if (c < 2) {
        out.push(t)
        counts.set(primary, c + 1)
      } else deferred.push(t)
    }
    return [...out, ...deferred]
  }

  return [...spread(played), ...spread(rankTail(liked)), ...spread(rankTail(others))]
}

export function samplePool(pool: Track[], seed = 0, limit = 12): Track[] {
  if (pool.length === 0) return []
  const s = (seed + 1) * 1013904223
  return pool
    .map((t) => {
      const h = (((hashId(t.id) * 2654435761) ^ s) >>> 0)
      return { t, h }
    })
    .sort((a, b) => a.h - b.h)
    .slice(0, limit)
    .map((x) => x.t)
}

export function shortId(x: unknown): string {
  return encodeURIComponent(String(x))
}

interface ShelfAnimState {
  rafId: number
  targetLeft: number
  cancel: () => void
}

const shelfAnims = new WeakMap<HTMLElement, ShelfAnimState>()

export function getShelfTargetLeft(el: HTMLElement | undefined): number {
  if (!el) return 0
  return shelfAnims.get(el)?.targetLeft ?? el.scrollLeft
}

export function animateShelfScroll(el: HTMLElement | undefined, targetLeft: number) {
  if (!el) return
  const maxScroll = Math.max(0, el.scrollWidth - el.clientWidth)
  const clamped = clamp(targetLeft, 0, maxScroll)

  const prev = shelfAnims.get(el)
  if (prev) prev.cancel()

  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    el.scrollLeft = clamped
    return
  }

  const startLeft = el.scrollLeft
  const dist = clamped - startLeft
  if (Math.abs(dist) < 1) {
    el.scrollLeft = clamped
    return
  }

  const duration = clamp(360 + Math.abs(dist) * 0.22, 420, 700)
  const startTime = performance.now()
  let rafId = 0

  const cleanup = () => {
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = 0
    }
    el.removeEventListener('wheel', onInterrupt)
    el.removeEventListener('pointerdown', onInterrupt)
    el.removeEventListener('touchstart', onInterrupt)
    if (shelfAnims.get(el)?.cancel === cancel) {
      shelfAnims.delete(el)
    }
  }

  const cancel = () => cleanup()
  const onInterrupt = () => cleanup()

  el.addEventListener('wheel', onInterrupt, { passive: true, once: true })
  el.addEventListener('pointerdown', onInterrupt, { passive: true, once: true })
  el.addEventListener('touchstart', onInterrupt, { passive: true, once: true })

  const state: ShelfAnimState = { rafId: 0, targetLeft: clamped, cancel }
  shelfAnims.set(el, state)

  const tickFrame = (now: number) => {
    const t = Math.min(1, Math.max(0, (now - startTime) / duration))
    const eased = 1 - Math.pow(1 - t, 3)
    el.scrollLeft = startLeft + dist * eased
    if (t < 1) {
      rafId = requestAnimationFrame(tickFrame)
      state.rafId = rafId
    } else {
      el.scrollLeft = clamped
      cleanup()
    }
  }

  rafId = requestAnimationFrame(tickFrame)
  state.rafId = rafId
}

export function scrollShelfContainer(el: HTMLElement | undefined, dir: -1 | 1) {
  if (!el) return
  const first = el.firstElementChild as HTMLElement | null
  const gap = parseFloat(getComputedStyle(el).columnGap) || 20
  const cardW = first ? first.getBoundingClientRect().width : 240
  const cardStride = cardW + gap
  const cardsPerPage = Math.max(1, Math.floor((el.clientWidth + gap) / cardStride))
  const baseLeft = getShelfTargetLeft(el)
  animateShelfScroll(el, baseLeft + dir * cardsPerPage * cardStride)
}

export function attachShelfNav(
  node: HTMLElement,
  secId: string,
  shelfEls: Record<string, HTMLElement | undefined>,
  onUpdate: (secId: string) => void,
) {
  let currentId = secId
  let rafId = 0
  shelfEls[currentId] = node
  const update = () => onUpdate(currentId)
  const onScroll = () => {
    if (rafId) return
    rafId = requestAnimationFrame(() => {
      rafId = 0
      update()
    })
  }
  const ro = new ResizeObserver(update)
  const mo = new MutationObserver(() => requestAnimationFrame(update))
  node.addEventListener('scroll', onScroll, { passive: true })
  ro.observe(node)
  mo.observe(node, { childList: true })
  requestAnimationFrame(update)
  return {
    update(newId: string) {
      if (newId !== currentId) {
        if (shelfEls[currentId] === node) delete shelfEls[currentId]
        currentId = newId
        shelfEls[currentId] = node
        requestAnimationFrame(update)
      }
    },
    destroy() {
      if (rafId) cancelAnimationFrame(rafId)
      node.removeEventListener('scroll', onScroll)
      ro.disconnect()
      mo.disconnect()
      if (shelfEls[currentId] === node) delete shelfEls[currentId]
    },
  }
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
  let cachedMax = 0
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

    if (target > cachedMax) target = cachedMax
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
    const alpha = 1 - Math.exp(-dt / 85)
    current += diff * alpha
    setPos(current)
    rafId = requestAnimationFrame(step)
  }

  function addDelta(rawDelta: number, deltaMode: number): boolean {
    const max = getMax()
    cachedMax = max
    if (max <= 0) return false

    const actual = getPos()
    if (!rafId || Math.abs(actual - current) > 40) {
      current = actual
      target = actual
    }

    let px = rawDelta
    if (deltaMode === 1) {
      px *= 24
    } else if (deltaMode === 2) {
      const page = axis === 'x' ? node.clientWidth : node.clientHeight
      px *= page * 0.65
    } else if (Math.abs(px) >= 75) {
      px *= 0.65
    } else if (Math.abs(px) >= 16) {
      px *= 0.92
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


/** Stable 32-bit FNV-1a hash of a canonical track id (used for deterministic jitter). */
export function hashId(id: string): number {
  let h = 2166136261
  for (let i = 0; i < id.length; i++) {
    h ^= id.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}
