<script lang="ts">
  import Icon from './Icon.svelte'
  import Cover from './Cover.svelte'
  import { core } from './store.svelte'
  import { trackMenu } from './menus'
  import { smoothScroll } from './util'

  const r = $derived(core.route)
  const t = $derived(core.current)
  const sorted = $derived(core.playlists.slice().sort((a, b) => Math.max(b.lastPlayed, b.createdAt) - Math.max(a.lastPlayed, a.createdAt)))
  const likedCount = $derived(core.tracks.reduce((n, t) => n + (t.liked ? 1 : 0), 0))

  function active(...names: string[]) {
    return names.includes(r.name)
  }
</script>

<div class="sidebar">
  <div class="box">
    <button class="brand" onclick={() => core.go({ name: 'home' })} title="Go to Home">
      <div class="logo">
        <svg viewBox="0 0 48 48" width="32" height="32" aria-hidden="true">
          <circle cx="24" cy="24" r="22.5" fill="#141619" />
          <circle cx="24" cy="24" r="21.7" fill="none" stroke="#46CF68" stroke-width="1.8" />
          <line x1="12.34" y1="17.09" x2="12.34" y2="23.28" stroke="#46CF68" stroke-width="2.0" stroke-linecap="round" />
          <line x1="16.46" y1="13.68" x2="16.46" y2="29.71" stroke="#46CF68" stroke-width="2.0" stroke-linecap="round" />
          <polyline points="20.54,23.09 23.95,39.98 27.79,20.11" fill="none" stroke="#46CF68" stroke-width="2.0" stroke-linecap="round" stroke-linejoin="round" />
          <line x1="31.54" y1="9.79" x2="31.54" y2="27.36" stroke="#46CF68" stroke-width="2.0" stroke-linecap="round" />
          <line x1="35.66" y1="15.46" x2="35.66" y2="23.28" stroke="#46CF68" stroke-width="2.0" stroke-linecap="round" />
        </svg>
      </div>
      <span class="brand-wordmark"><span class="vibe">Vibe</span><span class="music">Music</span></span>
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
    <div class="lib-list" use:smoothScroll>
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
        <Cover icon="heart-fill" class="thumb" size={128} />
        <div class="meta">
          <div class="n">{'Liked Songs'}</div>
          <div class="s">{likedCount} songs</div>
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
          <Cover ids={p.coverTracks && p.coverTracks.length ? p.coverTracks : null} icon="library" class="thumb" size={128} />
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
      <Cover id={t.hasCover ? t.id : ''} />
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
