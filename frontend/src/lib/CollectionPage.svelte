<script lang="ts">
  import CollectionView from './CollectionView.svelte'
  import { core, Go } from './store.svelte'
  import { hasArtist, norm } from './util'
  import type { DiscoverItem, Track } from './types'

  const r = $derived(core.route)
  let plIds = $state<string[]>([])
  let radioOnlineItems = $state<DiscoverItem[]>([])
  let radioOnlineLoading = $state(false)
  let radioOnlineSeed = $state(0)

  $effect(() => {
    if (r.name === 'playlist') {
      void core.playlists
      const id = Number(r.id)
      const cached = core.playlistTrackMap[id]
      if (cached && plIds.length === 0) {
        plIds = cached
      }
      Go.GetPlaylistTrackIDs(id).then((x) => {
        const next = (x as string[]) ?? []
        plIds = next
        core.playlistTrackMap = { ...core.playlistTrackMap, [id]: next }
      })
    }
  })

  function loadArtistRadioOnline(artist: string, seed: number) {
    radioOnlineLoading = true
    ;(Go as any)
      .GetArtistOnlineDiscovery?.(artist, seed)
      .then((items: DiscoverItem[]) => {
        radioOnlineItems = items ?? []
      })
      .catch(() => {})
      .finally(() => {
        radioOnlineLoading = false
      })
  }

  $effect(() => {
    if (r.name === 'artistradio') {
      const artist = String(r.id)
      radioOnlineItems = []
      loadArtistRadioOnline(artist, radioOnlineSeed)
    } else {
      radioOnlineItems = []
      radioOnlineLoading = false
    }
  })

  const playlist = $derived(r.name === 'playlist' ? (core.playlists.find((p) => p.id === Number(r.id)) ?? null) : null)
  const quickPickSeedTrack = $derived(r.name === 'quickpick' ? (core.byId.get(String(r.id)) ?? null) : null)

  const tracks = $derived.by((): Track[] => {
    switch (r.name) {
      case 'playlist':
        return plIds.map((id) => core.byId.get(id)).filter((t): t is Track => !!t)
      case 'liked':
        return core.tracks.filter((t) => t.liked)
      case 'recent':
        return core.tracks.slice(0, 50)
      case 'allsongs':
        return core.tracks
      case 'mostplayed':
        return core.tracks.filter((t) => t.playCount > 0).sort((a, b) => b.playCount - a.playCount)
      case 'album': {
        const key = norm(String(r.id).split('\u0000')[0])
        return core.tracks.filter((t) => t.album && norm(t.album) === key).sort((a, b) => a.id.localeCompare(b.id))
      }
      case 'artist':
      case 'artistradio': {
        const name = String(r.id)
        return core.tracks.filter((t) => hasArtist(t, name))
      }
      case 'quickpick': {
        void core.quickPickSeeds
        void core.playlistTrackMap
        void core.genreStatus
        return quickPickSeedTrack ? core.getQuickPickMix(quickPickSeedTrack, 'all') : []
      }
    }
    return []
  })

  const coverIds = $derived(tracks.filter((t) => t.hasCover).slice(0, 4).map((t) => t.id))
  const title = $derived.by(() => {
    switch (r.name) {
      case 'playlist': return playlist?.name ?? 'Playlist'
      case 'liked': return 'Liked Songs'
      case 'recent': return 'Recently Added'
      case 'mostplayed': return 'Most Played'
      case 'allsongs': return 'All Songs'
      case 'album': return String(r.id).split('\u0000')[0]
      case 'artist': return String(r.id)
      case 'artistradio': return `${String(r.id)} Radio`
      case 'quickpick': return quickPickSeedTrack ? `${quickPickSeedTrack.title} Mix` : 'Quick Pick Mix'
    }
    return ''
  })
  const subtitle = $derived(
    r.name === 'quickpick' && quickPickSeedTrack ? `Quick pick radio mix based on ${quickPickSeedTrack.artist}` : '',
  )
  const kind = $derived(
    r.name === 'album'
      ? 'Album'
      : r.name === 'artist'
        ? 'Artist'
        : r.name === 'artistradio'
          ? 'Artist Radio'
          : r.name === 'quickpick'
            ? 'Quick Pick'
            : r.name === 'playlist'
              ? 'Playlist'
              : 'Collection',
  )
  const icon = $derived(
    r.name === 'liked' ? 'heart-fill' : r.name === 'artist' || r.name === 'artistradio' ? 'person' : r.name === 'album' ? 'album' : 'note',
  )
  const isQuickPickPlaying = $derived(
    r.name === 'quickpick' && quickPickSeedTrack ? core.isQuickPickPlaying(quickPickSeedTrack.id) : undefined,
  )

  function reorder(from: number, to: number) {
    const id = Number(r.id)
    const a = plIds.slice()
    const [m] = a.splice(from, 1)
    a.splice(to, 0, m)
    plIds = a
    core.playlistTrackMap = { ...core.playlistTrackMap, [id]: a }
    Go.SetPlaylistTracks(id, a)
  }
  function remove(t: Track) {
    const id = Number(r.id)
    plIds = plIds.filter((x) => x !== t.id)
    core.playlistTrackMap = { ...core.playlistTrackMap, [id]: plIds }
    Go.SetPlaylistTracks(id, plIds)
  }
  async function addTrack(t: Track) {
    const id = Number(r.id)
    if (plIds.includes(t.id)) return
    plIds = [...plIds, t.id]
    core.playlistTrackMap = { ...core.playlistTrackMap, [id]: plIds }
    await Go.AddToPlaylist(id, [t.id])
  }
</script>

{#key r.name + ':' + r.id}
  <CollectionView
    {kind}
    {title}
    {subtitle}
    {tracks}
    {coverIds}
    {playlist}
    {icon}
    round={r.name === 'artist'}
    isPlayingOverride={isQuickPickPlaying}
    onlineTracks={r.name === 'artistradio' ? radioOnlineItems : []}
    onlineLoading={r.name === 'artistradio' ? radioOnlineLoading : false}
    onrefreshonline={r.name === 'artistradio' ? () => loadArtistRadioOnline(String(r.id), ++radioOnlineSeed) : undefined}
    onrefreshmix={r.name === 'quickpick' && quickPickSeedTrack ? () => core.refreshQuickPickMix(quickPickSeedTrack.id) : undefined}
    refreshMixHint={r.name === 'quickpick' && quickPickSeedTrack
      ? `Want a different vibe around “${quickPickSeedTrack.title}”? Refresh to pull a new mix from your library.`
      : ''}
    onplayall={r.name === 'quickpick' && quickPickSeedTrack ? () => core.toggleQuickPick(quickPickSeedTrack) : undefined}
    onplaytrack={r.name === 'quickpick' && quickPickSeedTrack
      ? (playIds, idx) => {
          core.radioSeed = { trackId: quickPickSeedTrack.id, mode: 'all' }
          core.play(playIds, idx, { keepRadio: true })
        }
      : undefined}
    onreorder={r.name === 'playlist' ? reorder : undefined}
    onremove={r.name === 'playlist' ? remove : undefined}
    onadd={r.name === 'playlist' ? addTrack : undefined}
  />
{/key}
