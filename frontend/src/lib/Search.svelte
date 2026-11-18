<script lang="ts">
  import Icon from './Icon.svelte'
  import Card from './Card.svelte'
  import TrackList from './TrackList.svelte'
  import { core, Go } from './store.svelte'
  import { groupBy, matches, norm } from './util'

  let filter = $state<'all' | 'songs' | 'albums' | 'artists' | 'playlists'>('all')
  const q = $derived(core.query.trim())

  const songs = $derived(q ? core.tracks.filter((t) => matches(t, q)) : [])
  const albums = $derived(q ? groupBy(songs.length ? core.tracks : [], 'album').filter((g) => norm(g.name).includes(norm(q))) : [])
  const artists = $derived(q ? groupBy(core.tracks, 'artist').filter((g) => norm(g.name).includes(norm(q))) : [])
  const playlists = $derived(q ? core.playlists.filter((p) => norm(p.name).includes(norm(q))) : [])

  const shelfEls: Record<string, HTMLElement | undefined> = {}
  let shelfScrollState = $state<Record<string, { left: boolean; right: boolean }>>({})

  function updateShelfState(secId: string) {
    const el = shelfEls[secId]
    if (!el) return
    const left = el.scrollLeft > 2
    const right = el.scrollLeft + el.clientWidth < el.scrollWidth - 2
    const cur = shelfScrollState[secId]
    if (!cur || cur.left !== left || cur.right !== right) {
      shelfScrollState[secId] = { left, right }
    }
  }

  function shelfNav(node: HTMLElement, secId: string) {
    let currentId = secId
    shelfEls[currentId] = node
    const update = () => updateShelfState(currentId)
    const ro = new ResizeObserver(update)
    const mo = new MutationObserver(() => requestAnimationFrame(update))
    node.addEventListener('scroll', update, { passive: true })
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
        node.removeEventListener('scroll', update)
        ro.disconnect()
        mo.disconnect()
        if (shelfEls[currentId] === node) delete shelfEls[currentId]
      },
    }
  }

  function scrollShelf(secId: string, dir: -1 | 1) {
    const el = shelfEls[secId]
    if (!el) return
    const cardStride = 186
    const cardsPerPage = Math.max(1, Math.floor((el.clientWidth + 18) / cardStride))
    el.scrollBy({ left: dir * cardsPerPage * cardStride, behavior: 'smooth' })
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
        {#each artists.slice(0, filter === 'all' ? 36 : 100) as g (g.key)}
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
        {#each albums.slice(0, filter === 'all' ? 36 : 100) as g (g.key)}
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
        {#each playlists as p (p.id)}
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
