<script lang="ts">
  import Icon from './Icon.svelte'
  import Cover from './Cover.svelte'
  import { core } from './store.svelte'
  import { trackMenu } from './menus'

  const r = $derived(core.route)
  const t = $derived(core.current)
  const sorted = $derived(core.playlists.slice().sort((a, b) => Math.max(b.lastPlayed, b.createdAt) - Math.max(a.lastPlayed, a.createdAt)))

  function active(...names: string[]) {
    return names.includes(r.name)
  }
</script>

<div class="sidebar">
  <div class="box">
    <button class="brand" onclick={() => core.go({ name: 'home' })} title="Go to Home">
      <div class="logo"><Icon name="play" size={18} /></div>
      <span>Vibe Music</span>
      <span class="ver-badge">v{core.version}</span>
    </button>
    <button class="nav-item" class:active={active('home')} onclick={() => core.go({ name: 'home' })}><Icon name="home" size={22} />Home</button>
    <button class="nav-item" class:active={active('search')} onclick={() => core.go({ name: 'search' })}><Icon name="search" size={22} />Search</button>
    <button class="nav-item" class:active={active('playlists', 'playlist', 'liked')} onclick={() => core.go({ name: 'playlists' })}><Icon name="library" size={22} />Playlists</button>
    <button class="nav-item" class:active={active('albums', 'album')} onclick={() => core.go({ name: 'albums' })}><Icon name="album" size={22} />Albums</button>
    <button class="nav-item" class:active={active('artists', 'artist')} onclick={() => core.go({ name: 'artists' })}><Icon name="person" size={22} />Artists</button>
    <button class="nav-item" class:active={active('downloads')} onclick={() => core.go({ name: 'downloads' })}><Icon name="download" size={22} />Download</button>
    <button class="nav-item" class:active={active('settings')} onclick={() => core.go({ name: 'settings' })}><Icon name="settings" size={22} />Settings</button>
  </div>
  <div class="box lib">
    <div class="lib-head">
      <span>Your playlists</span>
      <button class="icon-btn" style="width:28px;height:28px" onclick={() => (core.modal = { type: 'newPlaylist' })} title="New playlist" aria-label="New playlist"><Icon name="plus" size={20} /></button>
    </div>
    <div class="lib-list">
      <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
      <div
        class="pl-item"
        class:active={r.name === 'liked'}
        class:playing={core.player.playlistId === -1}
        role="button"
        tabindex="0"
        onclick={() => core.go({ name: 'liked' })}
        onkeydown={(e) => e.key === 'Enter' && core.go({ name: 'liked' })}
      >
        <Cover icon="heart-fill" class="thumb" />
        <div class="meta">
          <div class="n">{'Liked Songs'}</div>
          <div class="s">{core.tracks.filter((t) => t.liked).length} songs</div>
        </div>
        <button
          class="pl-play-btn"
          class:playing={core.player.playlistId === -1 && core.player.playing}
          onclick={(e) => {
            e.stopPropagation()
            core.toggleLikedSongs()
          }}
          title={core.player.playlistId === -1 && core.player.playing ? 'Pause' : 'Play'}
          aria-label={core.player.playlistId === -1 && core.player.playing ? 'Pause' : 'Play'}
        >
          <Icon name={core.player.playlistId === -1 && core.player.playing ? 'pause' : 'play'} size={18} />
        </button>
      </div>
      {#each sorted as p (p.id)}
        {@const isPlPlaying = core.player.playlistId === p.id && core.player.playing}
        <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
        <div
          class="pl-item"
          class:active={r.name === 'playlist' && Number(r.id) === p.id}
          class:playing={core.player.playlistId === p.id}
          role="button"
          tabindex="0"
          onclick={() => core.go({ name: 'playlist', id: p.id })}
          onkeydown={(e) => e.key === 'Enter' && core.go({ name: 'playlist', id: p.id })}
        >
          <Cover ids={p.coverTracks && p.coverTracks.length ? p.coverTracks : null} icon="library" class="thumb" />
          <div class="meta">
            <div class="n">{p.name}</div>
            <div class="s">{p.trackCount} songs</div>
          </div>
          <button
            class="pl-play-btn"
            class:playing={isPlPlaying}
            onclick={(e) => {
              e.stopPropagation()
              core.togglePlaylist(p.id)
            }}
            title={isPlPlaying ? 'Pause' : 'Play'}
            aria-label={isPlPlaying ? 'Pause' : 'Play'}
          >
            <Icon name={isPlPlaying ? 'pause' : 'play'} size={18} />
          </button>
        </div>
      {/each}
    </div>
  </div>
  {#if core.coverExpanded && t}
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div class="expanded-cover-box" oncontextmenu={(e) => core.openMenu(e, trackMenu(t))}>
      <Cover id={t.hasCover ? t.id : 0} />
      <button
        class="cover-toggle-btn"
        onclick={() => (core.coverExpanded = false)}
        title="Collapse cover"
        aria-label="Collapse cover"
      >
        <Icon name="chevron-down" size={20} />
      </button>
    </div>
  {/if}
</div>
