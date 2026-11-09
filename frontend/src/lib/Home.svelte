<script lang="ts">
  import Icon from './Icon.svelte'
  import Card from './Card.svelte'
  import Cover from './Cover.svelte'
  import TrackList from './TrackList.svelte'
  import { core, Go } from './store.svelte'
  import { trackMenu } from './menus'

  const hour = new Date().getHours()
  const greeting = hour < 5 ? 'Late night vibes' : hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'

  const recent = $derived(
    core.playlists
      .filter((p) => p.lastPlayed > 0)
      .sort((a, b) => b.lastPlayed - a.lastPlayed)
      .slice(0, 4),
  )
  const recentlyAdded = $derived(core.tracks.slice(0, 14))
  const mostPlayed = $derived(
    core.tracks
      .filter((t) => t.playCount > 0)
      .sort((a, b) => b.playCount - a.playCount)
      .slice(0, 14),
  )

  async function playPlaylist(id: number) {
    const ids = ((await Go.GetPlaylistTrackIDs(id)) as number[]) ?? []
    core.play(ids, -1, { playlistId: id })
  }
</script>

<div class="view-head">
  <div class="view-title">{greeting}</div>
</div>

{#if recent.length > 0}
  <div class="recent-grid">
    {#each recent as p (p.id)}
      <div class="recent-card" role="button" tabindex="0" onclick={() => core.go({ name: 'playlist', id: p.id })} onkeydown={(e) => e.key === 'Enter' && core.go({ name: 'playlist', id: p.id })}>
        <Cover ids={p.coverTracks && p.coverTracks.length ? p.coverTracks : null} icon="library" class="thumb" />
        <div class="t">{p.name}</div>
        <button
          class="play-fab"
          aria-label="Play"
          onclick={(e) => {
            e.stopPropagation()
            playPlaylist(p.id)
          }}><Icon name="play" size={24} /></button
        >
      </div>
    {/each}
  </div>
{:else}
  <div class="card-surface" style="padding:18px 20px;background:var(--bg-elev2);color:var(--text-dim)">
    Playlists you play will show up here. <button class="link-btn" onclick={() => core.go({ name: 'playlists' })}>Go to your playlists →</button>
  </div>
{/if}

{#if mostPlayed.length > 0}
  <div class="section">
    <div class="section-head">
      <h2>Your top tracks</h2>
      <button class="link-btn" onclick={() => core.go({ name: 'mostplayed' })}>Show all</button>
    </div>
    <div class="shelf">
      {#each mostPlayed as t, i (t.id)}
        <Card
          title={t.title}
          sub={t.artist}
          id={t.hasCover ? t.id : 0}
          playing={core.player.trackId === t.id && core.player.playing}
          onclick={() => core.play(mostPlayed.map((x) => x.id), i)}
          onplay={() => core.playInstant(t.id)}
          onmore={(e) => core.openMenu(e, trackMenu(t))}
          oncontext={(e) => core.openMenu(e, trackMenu(t))}
        />
      {/each}
    </div>
  </div>
{/if}

{#if recentlyAdded.length > 0}
  <div class="section">
    <div class="section-head">
      <h2>Recently added</h2>
      <button class="link-btn" onclick={() => core.go({ name: 'recent' })}>Show all</button>
    </div>
    <div class="shelf">
      {#each recentlyAdded as t, i (t.id)}
        <Card
          title={t.title}
          sub={t.artist}
          id={t.hasCover ? t.id : 0}
          playing={core.player.trackId === t.id && core.player.playing}
          onclick={() => core.play(recentlyAdded.map((x) => x.id), i)}
          onplay={() => core.playInstant(t.id)}
          onmore={(e) => core.openMenu(e, trackMenu(t))}
          oncontext={(e) => core.openMenu(e, trackMenu(t))}
        />
      {/each}
    </div>
  </div>
{/if}

<div class="section">
  <div class="section-head">
    <h2>All your music <span style="color:var(--text-dim);font-weight:600;font-size:15px">· {core.tracks.length} songs</span></h2>
    <div style="display:flex;gap:10px">
      <button class="btn primary" disabled={!core.tracks.length} onclick={() => core.play(core.tracks.map((t) => t.id), -1)}>Play</button>
      <button class="btn" disabled={!core.tracks.length} onclick={() => core.play(core.tracks.map((t) => t.id), -1, { shuffle: true })}>Shuffle</button>
    </div>
  </div>
  {#if core.tracks.length === 0}
    <div class="empty">
      <div class="big">{core.scan.scanning ? 'Scanning your music…' : 'No music found yet'}</div>
      {#if !core.scan.scanning}
        Add folders in <button class="link-btn" onclick={() => core.go({ name: 'settings' })}>Settings</button> or
        <button class="link-btn" onclick={() => core.go({ name: 'downloads' })}>download some tracks</button>.
      {/if}
    </div>
  {:else}
    <TrackList tracks={core.tracks} sortable />
  {/if}
</div>
