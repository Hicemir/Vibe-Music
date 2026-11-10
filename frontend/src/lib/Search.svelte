<script lang="ts">
  import Card from './Card.svelte'
  import TrackList from './TrackList.svelte'
  import { core, Go } from './store.svelte'
  import { groupBy, horizontalScroll, matches, norm } from './util'

  let filter = $state<'all' | 'songs' | 'albums' | 'artists' | 'playlists'>('all')
  const q = $derived(core.query.trim())

  const songs = $derived(q ? core.tracks.filter((t) => matches(t, q)) : [])
  const albums = $derived(q ? groupBy(songs.length ? core.tracks : [], 'album').filter((g) => norm(g.name).includes(norm(q))) : [])
  const artists = $derived(q ? groupBy(core.tracks, 'artist').filter((g) => norm(g.name).includes(norm(q))) : [])
  const playlists = $derived(q ? core.playlists.filter((p) => norm(p.name).includes(norm(q))) : [])

  async function playPlaylist(id: number) {
    const ids = ((await Go.GetPlaylistTrackIDs(id)) as number[]) ?? []
    core.play(ids, -1, { playlistId: id })
  }
</script>

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
      <div class="section-head"><h2>Artists</h2></div>
      <div class="shelf" use:horizontalScroll>
        {#each artists.slice(0, filter === 'all' ? 12 : 100) as g (g.key)}
          <Card title={g.name} sub="{g.tracks.length} songs" id={g.coverId} icon="person" round onclick={() => core.go({ name: 'artist', id: g.name })} onplay={() => core.play(g.tracks.map((t) => t.id), -1)} />
        {/each}
      </div>
    </div>
  {/if}
  {#if (filter === 'all' || filter === 'albums') && albums.length}
    <div class="section">
      <div class="section-head"><h2>Albums</h2></div>
      <div class="shelf" use:horizontalScroll>
        {#each albums.slice(0, filter === 'all' ? 12 : 100) as g (g.key)}
          <Card title={g.name} sub={g.sub} id={g.coverId} icon="album" onclick={() => core.go({ name: 'album', id: g.name })} onplay={() => core.play(g.tracks.map((t) => t.id), -1)} />
        {/each}
      </div>
    </div>
  {/if}
  {#if (filter === 'all' || filter === 'playlists') && playlists.length}
    <div class="section">
      <div class="section-head"><h2>Playlists</h2></div>
      <div class="shelf" use:horizontalScroll>
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
