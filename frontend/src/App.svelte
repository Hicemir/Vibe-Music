<script lang="ts">
  import { onMount, tick } from 'svelte'
  import { core, Go } from './lib/store.svelte'
  import Icon from './lib/Icon.svelte'
  import Sidebar from './lib/Sidebar.svelte'
  import TopBar from './lib/TopBar.svelte'
  import PlayerBar from './lib/PlayerBar.svelte'
  import QueuePanel from './lib/QueuePanel.svelte'
  import LyricsPanel from './lib/LyricsPanel.svelte'
  import MiniPlayer from './lib/MiniPlayer.svelte'
  import MenuLayer from './lib/MenuLayer.svelte'
  import ModalLayer from './lib/ModalLayer.svelte'
  import Home from './lib/Home.svelte'
  import Playlists from './lib/Playlists.svelte'
  import CollectionPage from './lib/CollectionPage.svelte'
  import Groups from './lib/Groups.svelte'
  import Search from './lib/Search.svelte'
  import Downloads from './lib/Downloads.svelte'
  import SettingsView from './lib/SettingsView.svelte'

  let topbar: TopBar | undefined = $state()
  let scroller: HTMLDivElement | undefined = $state()
  let topBarVisible = $state(false)
  const r = $derived(core.route)

  function onWindowMouseMove(e: MouseEvent) {
    if (e.clientY <= 12) {
      topBarVisible = true
    } else if (topBarVisible && e.clientY > 40) {
      topBarVisible = false
    }
  }

  function onHoverBarMouseDown(e: MouseEvent) {
    if (e.button === 0 && !(e.target as HTMLElement | null)?.closest('button')) {
      Go.ArmWindowDrag()
    }
  }

  function onHoverBarDblClick(e: MouseEvent) {
    if (!(e.target as HTMLElement | null)?.closest('button')) {
      Go.ToggleMaximiseWindow()
    }
  }

  onMount(() => {
    core.init()
  })

  $effect(() => {
    core.scroller = scroller ?? null
  })

  // Restore saved scroll position on Back/Forward, or scroll to top on new navigation
  $effect(() => {
    void core.route
    const target = core.targetScroll
    tick().then(() => {
      if (scroller) {
        scroller.scrollTop = target
        requestAnimationFrame(() => {
          if (scroller) scroller.scrollTop = target
        })
      }
    })
  })

  function typing(e: KeyboardEvent) {
    const el = e.target as HTMLElement
    return el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)
  }

  function onMouseButton(e: MouseEvent) {
    if (e.button === 3) {
      e.preventDefault()
      if (e.type === 'mousedown') core.back()
    } else if (e.button === 4) {
      e.preventDefault()
      if (e.type === 'mousedown') core.forward()
    }
  }

  function onKey(e: KeyboardEvent) {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') {
      e.preventDefault()
      topbar?.focusSearch()
      return
    }
    if (e.altKey && !e.ctrlKey && !e.metaKey) {
      if (e.key === 'ArrowLeft') {
        e.preventDefault()
        core.back()
        return
      }
      if (e.key === 'ArrowRight') {
        e.preventDefault()
        core.forward()
        return
      }
    }
    if (typing(e) || e.ctrlKey || e.altKey || e.metaKey) return
    const p = core.player
    switch (e.key) {
      case ' ':
        e.preventDefault()
        core.toggle()
        break
      case 'ArrowRight':
        e.preventDefault()
        e.shiftKey ? Go.Next() : Go.SeekRelative(5)
        break
      case 'ArrowLeft':
        e.preventDefault()
        e.shiftKey ? Go.Previous() : Go.SeekRelative(-5)
        break
      case 'ArrowUp':
        e.preventDefault()
        Go.SetVolume(Math.min(100, p.volume + 5))
        break
      case 'ArrowDown':
        e.preventDefault()
        Go.SetVolume(Math.max(0, p.volume - 5))
        break
      case 'm':
      case 'M':
        e.preventDefault()
        Go.ToggleMute()
        break
      case 's':
      case 'S':
        Go.SetShuffle(!p.shuffle)
        break
      case 'r':
      case 'R':
        Go.SetRepeat(p.repeat === 'off' ? 'all' : p.repeat === 'all' ? 'one' : 'off')
        break
      case 'l':
      case 'L':
        if (p.trackId) core.toggleLike(p.trackId)
        break
      case 'q':
      case 'Q':
        core.panel = core.panel === 'queue' ? 'none' : 'queue'
        break
    }
  }
</script>

<svelte:window
  onkeydown={onKey}
  onmousemove={onWindowMouseMove}
  onmousedown={onMouseButton}
  onmouseup={onMouseButton}
  oncontextmenu={(e) => { if (!(e.target as HTMLElement).closest('input,textarea')) e.preventDefault() }}
/>

{#if !core.mini}
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div
    class="hover-top-zone"
    class:show={topBarVisible}
    onmouseenter={() => (topBarVisible = true)}
    onmouseleave={(e) => { if (e.clientY > 36) topBarVisible = false }}
    onmousedown={onHoverBarMouseDown}
    ondblclick={onHoverBarDblClick}
  >
    <div class="hover-topbar">
      <div class="hover-win-controls">
        <button class="hover-wb-btn" onclick={() => Go.MinimiseWindow()} title="Minimize" aria-label="Minimize">
          <Icon name="chevron-down" size={18} />
        </button>
        <button class="hover-wb-btn" onclick={() => Go.ToggleMaximiseWindow()} title={core.windowMaximised ? 'Restore' : 'Maximize'} aria-label="Maximize or restore">
          <Icon name="chevron-up" size={18} />
        </button>
        <button
          class="hover-wb-btn close"
          onmousedown={(e) => e.stopPropagation()}
          onclick={() => Go.CloseWindow()}
          title="Close"
          aria-label="Close"
        >
          <Icon name="close" size={18} />
        </button>
      </div>
    </div>
  </div>
{/if}

{#if core.mini}
  <MiniPlayer />
{:else}
  <div class="shell" class:with-panel={core.panel !== 'none'}>
    <Sidebar />
    <div class="main">
      <TopBar bind:this={topbar} />
      <div class="scroll" bind:this={scroller}>
        {#if !core.loaded}
          <div class="empty">Loading…</div>
        {:else if r.name === 'home'}
          <Home />
        {:else if r.name === 'playlists'}
          <Playlists />
        {:else if r.name === 'playlist' || r.name === 'liked' || r.name === 'recent' || r.name === 'mostplayed' || r.name === 'allsongs' || r.name === 'album' || r.name === 'artist' || r.name === 'artistradio'}
          <CollectionPage />
        {:else if r.name === 'albums'}
          <Groups mode="albums" />
        {:else if r.name === 'artists'}
          <Groups mode="artists" />
        {:else if r.name === 'search'}
          <Search />
        {:else if r.name === 'downloads'}
          <Downloads />
        {:else if r.name === 'settings'}
          <SettingsView />
        {/if}
      </div>
    </div>
    {#if core.panel === 'queue'}
      <QueuePanel />
    {:else if core.panel === 'lyrics'}
      <LyricsPanel />
    {/if}
    <PlayerBar />
  </div>
{/if}

<MenuLayer />
<ModalLayer />
<div class="toasts">
  {#each core.toasts as t (t.id)}<div class="toast">{t.text}</div>{/each}
</div>
