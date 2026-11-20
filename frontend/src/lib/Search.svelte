<script lang="ts">
  import { tick } from 'svelte'
  import Icon from './Icon.svelte'
  import Card from './Card.svelte'
  import TrackList from './TrackList.svelte'
  import { core, Go } from './store.svelte'
  import { attachShelfNav, getShelfTargetLeft, groupBy, matches, norm, scrollShelfContainer } from './util'

  const SHELF_INITIAL = 12
  const SHELF_STEP = 12

  let filter = $state<'all' | 'songs' | 'albums' | 'artists' | 'playlists'>('all')
  const q = $derived(core.query.trim())

  const songs = $derived(q ? core.tracks.filter((t) => matches(t, q)) : [])
  const albums = $derived(q ? groupBy(songs.length ? core.tracks : [], 'album').filter((g) => norm(g.name).includes(norm(q))) : [])
  const artists = $derived(q ? groupBy(core.tracks, 'artist').filter((g) => norm(g.name).includes(norm(q))) : [])
  const playlists = $derived(q ? core.playlists.filter((p) => norm(p.name).includes(norm(q))) : [])
  const groupCap = $derived(filter === 'all' ? 36 : 100)

  const shelfEls: Record<string, HTMLElement | undefined> = {}
  let shelfScrollState = $state<Record<string, { left: boolean; right: boolean }>>({})
  let shelfShown = $state<Record<string, number>>({})

  $effect(() => {
    void q
    void filter
    shelfShown = {}
  })

  function shelfTotal(secId: string): number {
    switch (secId) {
      case 'artists':
        return Math.min(artists.length, groupCap)
      case 'albums':
        return Math.min(albums.length, groupCap)
      case 'playlists':
        return playlists.length
      default:
        return 0
    }
  }

  function updateShelfState(secId: string) {
    const el = shelfEls[secId]
    if (!el) return
    const total = shelfTotal(secId)
    const shown = shelfShown[secId] ?? SHELF_INITIAL
    if (shown < total && el.scrollLeft + el.clientWidth >= el.scrollWidth - 600) {
      shelfShown = { ...shelfShown, [secId]: Math.min(total, shown + SHELF_STEP) }
    }
    const left = el.scrollLeft > 2
    const right = el.scrollLeft + el.clientWidth < el.scrollWidth - 2 || shown < total
    const cur = shelfScrollState[secId]
    if (!cur || cur.left !== left || cur.right !== right) {
      shelfScrollState[secId] = { left, right }
    }
  }

  function shelfNav(node: HTMLElement, secId: string) {
    return attachShelfNav(node, secId, shelfEls, updateShelfState)
  }

  async function scrollShelf(secId: string, dir: -1 | 1) {
    const el = shelfEls[secId]
    if (!el) return
    const total = shelfTotal(secId)
    const shown = shelfShown[secId] ?? SHELF_INITIAL
    if (dir === 1 && shown < total && getShelfTargetLeft(el) + el.clientWidth * 2 >= el.scrollWidth - 300) {
      shelfShown = { ...shelfShown, [secId]: Math.min(total, shown + SHELF_STEP) }
      await tick()
    }
    scrollShelfContainer(el, dir)
  }

  async function playPlaylist(id: number) {
    const ids = ((await Go.GetPlaylistTrackIDs(id)) as string[]) ?? []
    core.play(ids, -1, { playlistId: id })
  }
</script>

{#snippet shelfArrows(secId: string)}
  {@const st = shelfScrollState[secId] ?? { left: false, right: false }}
  <div class="shelf-arrows">
    <button
      class="shelf-arrow"
      disabled={!st.left}
      onclick={() => scrollShelf(secId, -1)}
      title="Scroll left"
      aria-label="Scroll left"
    >
      <Icon name="back" size={20} />
    </button>
    <button
      class="shelf-arrow"
      disabled={!st.right}
      onclick={() => scrollShelf(secId, 1)}
      title="Scroll right"
      aria-label="Scroll right"
    >
      <Icon name="forward" size={20} />
    </button>
  </div>
{/snippet}

<div class="view-head">
  <div class="view-title">Search</div>
  <div class="search-chips">
    {#each ['all', 'songs', 'albums', 'artists', 'playlists'] as f}
      <button class="chip" class:on={filter === f} onclick={() => (filter = f as typeof filter)}>{f[0].toUpperCase() + f.slice(1)}</button>
    {/each}
  </div>
</div>

{#if !q}
  <div class="empty"><div class="big">Search your library</div>Songs, artists, albums and playlists.</div>
{:else if !songs.length && !albums.length && !artists.length && !playlists.length}
  <div class="empty"><div class="big">No results for “{q}”</div>Try a different spelling.</div>
{:else}
  {#if (filter === 'all' || filter === 'artists') && artists.length}
    <div class="section" style="margin-top:8px">
      <div class="section-head">
        <h2>Artists</h2>
        {@render shelfArrows('artists')}
      </div>
      <div class="shelf" use:shelfNav={'artists'}>
        {#each artists.slice(0, Math.min(groupCap, shelfShown.artists ?? SHELF_INITIAL)) as g (g.key)}
          <Card title={g.name} sub="{g.tracks.length} songs" id={g.coverId} icon="person" round onclick={() => core.go({ name: 'artist', id: g.name })} onplay={() => core.play(g.tracks.map((t) => t.id), -1)} />
        {/each}
      </div>
    </div>
  {/if}
  {#if (filter === 'all' || filter === 'albums') && albums.length}
    <div class="section">
      <div class="section-head">
        <h2>Albums</h2>
        {@render shelfArrows('albums')}
      </div>
      <div class="shelf" use:shelfNav={'albums'}>
        {#each albums.slice(0, Math.min(groupCap, shelfShown.albums ?? SHELF_INITIAL)) as g (g.key)}
          <Card title={g.name} sub={g.sub} id={g.coverId} icon="album" onclick={() => core.go({ name: 'album', id: g.name })} onplay={() => core.play(g.tracks.map((t) => t.id), -1)} />
        {/each}
      </div>
    </div>
  {/if}
  {#if (filter === 'all' || filter === 'playlists') && playlists.length}
    <div class="section">
      <div class="section-head">
        <h2>Playlists</h2>
        {@render shelfArrows('playlists')}
      </div>
      <div class="shelf" use:shelfNav={'playlists'}>
        {#each playlists.slice(0, shelfShown.playlists ?? SHELF_INITIAL) as p (p.id)}
          <Card title={p.name} sub="{p.trackCount} songs" ids={p.coverTracks && p.coverTracks.length ? p.coverTracks : null} icon="library" onclick={() => core.go({ name: 'playlist', id: p.id })} onplay={() => playPlaylist(p.id)} />
        {/each}
      </div>
    </div>
  {/if}
  {#if (filter === 'all' || filter === 'songs') && songs.length}
    <div class="section">
      <div class="section-head"><h2>Songs</h2></div>
      <TrackList tracks={songs} />
    </div>
  {/if}
{/if}
