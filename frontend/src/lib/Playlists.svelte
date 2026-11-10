<script lang="ts">
  import Card from './Card.svelte'
  import { core, Go } from './store.svelte'

  const liked = $derived(core.tracks.filter((t) => t.liked))
  const likedCovers = $derived(liked.filter((t) => t.hasCover).slice(0, 4).map((t) => t.id))

  async function importM3U() {
    const err = await Go.ImportM3U()
    if (err) core.toast(err)
  }
</script>

<div class="view-head" style="display:flex;align-items:center;justify-content:space-between;gap:12px">
  <div class="view-title">Your playlists</div>
  <div style="display:flex;gap:10px">
    <button class="btn ghost" onclick={importM3U}>Import M3U</button>
    <button class="btn primary" onclick={() => (core.modal = { type: 'newPlaylist' })}>+ New playlist</button>
  </div>
</div>

<div class="grid">
  <Card
    title="Liked Songs"
    sub="{liked.length} songs"
    ids={likedCovers.length ? likedCovers : null}
    icon="heart-fill"
    playing={core.player.playlistId === -1 && core.player.playing}
    onclick={() => core.go({ name: 'liked' })}
    onplay={() => core.toggleLikedSongs()}
  />
  {#each core.playlists as p (p.id)}
    <Card
      title={p.name}
      sub="{p.trackCount} songs"
      ids={p.coverTracks && p.coverTracks.length ? p.coverTracks : null}
      icon="library"
      playing={core.player.playlistId === p.id && core.player.playing}
      onclick={() => core.go({ name: 'playlist', id: p.id })}
      onplay={() => core.togglePlaylist(p.id)}
    />
  {/each}
  <button class="card" style="display:grid;place-items:center;min-height:200px;border:2px dashed var(--border);background:transparent;color:var(--text-dim)" onclick={() => (core.modal = { type: 'newPlaylist' })}>
    <div style="text-align:center;font-weight:700"><div style="font-size:34px;line-height:1">+</div>Create playlist</div>
  </button>
</div>

{#if core.playlists.length === 0}
  <p class="hint" style="margin-top:20px">
    Tip: right-click any song → “Add to playlist…”, or download a whole playlist from the Download tab and it will appear here automatically.
  </p>
{/if}
