<script lang="ts">
  import { tick } from 'svelte'
  import Icon from './Icon.svelte'
  import Card from './Card.svelte'
  import Cover from './Cover.svelte'
  import TrackList from './TrackList.svelte'
  import { core, Go } from './store.svelte'
  import { trackMenu } from './menus'
  import { buildQuickPicks, fmtTime, hasArtist, horizontalScroll, norm, splitArtists } from './util'
  import type { DiscoverItem, Settings } from './types'

  const hour = new Date().getHours()
  const greeting = hour < 5 ? 'Late night vibes' : hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'

  const DEFAULT_MOVABLE = ['recentPlaylists', 'topTracks', 'quickPicks', 'artistRadios', 'discover', 'recentlyAdded']
  const DEFAULT_ORDER = [...DEFAULT_MOVABLE, 'allMusic']

  const SECTION_LIST: { id: string; title: string; sub: string }[] = [
    { id: 'recentPlaylists', title: 'Recent playlists', sub: 'Top 4 recently played playlists' },
    { id: 'topTracks', title: 'Your top tracks', sub: 'Most played songs shelf' },
    { id: 'quickPicks', title: 'Quick picks', sub: 'Instant local radio mix cards' },
    { id: 'artistRadios', title: 'Artist radios', sub: 'Artist mixes + online discovery' },
    { id: 'discover', title: 'Discover new music', sub: 'YouTube Music online suggestions' },
    { id: 'recentlyAdded', title: 'Recently added', sub: 'Newest tracks in your library' },
    { id: 'allMusic', title: 'All your music', sub: 'Always pinned at the bottom of Home' },
  ]

  let editingLayout = $state(false)
  let showSectionsMenu = $state(false)
  let showAllHomeTracks = $state(false)
  let dragSec = $state<string | null>(null)
  let dragOverSec = $state<string | null>(null)
  let qpSeed = $state(0)
  let qpCols = $state(6)
  let qpScroller: HTMLDivElement | undefined = $state()
  let downloadingIds = $state<Record<string, boolean>>({})

  const homeOrder = $derived.by(() => {
    const raw = core.settings?.homeOrder
    if (!raw || !raw.length) return DEFAULT_ORDER
    const seen = new Set<string>(['allMusic'])
    const out: string[] = []
    for (const k of raw) {
      if (k !== 'allMusic' && !seen.has(k)) {
        seen.add(k)
        out.push(k)
      }
    }
    for (const k of DEFAULT_MOVABLE) {
      if (!seen.has(k)) {
        seen.add(k)
        out.push(k)
      }
    }
    out.push('allMusic')
    return out
  })

  const hiddenSet = $derived(new Set(core.settings?.homeHidden ?? ['discover']))
  const visibleSections = $derived(homeOrder.filter((id) => !hiddenSet.has(id)))
  const movableVisible = $derived(visibleSections.filter((id) => id !== 'allMusic'))

  const qpRowsMode = $derived(core.settings?.quickPicksRows || '1')
  const effectiveQuickRows = $derived.by(() => {
    if (qpRowsMode === '2') return 2
    if (qpRowsMode === '3') return 3
    if (qpRowsMode === 'auto') {
      let r = 1
      if (hiddenSet.has('topTracks')) r++
      if (hiddenSet.has('recentlyAdded')) r++
      return r
    }
    return 1
  })

  const allQuickPicks = $derived(buildQuickPicks(core.tracks, qpSeed))
  const quickPickCount = $derived(qpCols * effectiveQuickRows)
  const visibleQuickPicks = $derived(allQuickPicks.slice(0, quickPickCount))
  const hasMoreQuickPicks = $derived(visibleQuickPicks.length < allQuickPicks.length)

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

  const artistRadios = $derived.by(() => {
    const map = new Map<string, { name: string; score: number; count: number; covers: number[]; trackIds: number[] }>()
    for (const t of core.tracks) {
      const sc = t.playCount * 10 + (t.liked ? 25 : 0) + 1
      for (const a of splitArtists(t.artist)) {
        if (!a || a === 'Unknown Artist') continue
        const k = norm(a)
        let entry = map.get(k)
        if (!entry) {
          entry = { name: a, score: 0, count: 0, covers: [], trackIds: [] }
          map.set(k, entry)
        }
        entry.score += sc
        entry.count++
        entry.trackIds.push(t.id)
        if (t.hasCover && entry.covers.length < 4 && !entry.covers.includes(t.id)) {
          entry.covers.push(t.id)
        }
      }
    }
    return [...map.values()].sort((a, b) => b.score - a.score).slice(0, 10)
  })

  $effect(() => {
    if (!hiddenSet.has('discover') && core.discoverItems.length === 0 && !core.discoverLoading && core.tracks.length > 0) {
      core.fetchOnlineDiscover(false)
    }
  })

  async function saveSettingsPatch(patch: Partial<Settings>) {
    if (!core.settings) return
    const next = { ...core.settings, ...patch }
    core.settings = next
    const res = (await Go.SaveSettings(next as never)) as unknown as Settings
    core.settings = res
  }

  function moveSection(secId: string, dir: -1 | 1) {
    if (secId === 'allMusic') return
    const vis = movableVisible.slice()
    const i = vis.indexOf(secId)
    const j = i + dir
    if (i < 0 || j < 0 || j >= vis.length) return
    const a = vis[i]
    const b = vis[j]
    const full = homeOrder.slice()
    const ia = full.indexOf(a)
    const ib = full.indexOf(b)
    if (ia >= 0 && ib >= 0) {
      full[ia] = b
      full[ib] = a
      saveSettingsPatch({ homeOrder: full })
    }
  }

  function onSecDragStart(e: DragEvent, secId: string) {
    if (!editingLayout || secId === 'allMusic') return
    dragSec = secId
    if (e.dataTransfer) {
      e.dataTransfer.effectAllowed = 'move'
      e.dataTransfer.setData('text/plain', secId)
    }
  }

  function onSecDragOver(e: DragEvent, secId: string) {
    if (!editingLayout || !dragSec || secId === 'allMusic' || dragSec === secId) return
    e.preventDefault()
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
    dragOverSec = secId
  }

  function onSecDrop(e: DragEvent, targetSec: string) {
    e.preventDefault()
    const fromSec = dragSec
    dragSec = null
    dragOverSec = null
    if (!fromSec || fromSec === targetSec || targetSec === 'allMusic') return
    const full = homeOrder.filter((x) => x !== 'allMusic')
    const fromIdx = full.indexOf(fromSec)
    const toIdx = full.indexOf(targetSec)
    if (fromIdx < 0 || toIdx < 0) return
    full.splice(fromIdx, 1)
    full.splice(toIdx, 0, fromSec)
    full.push('allMusic')
    saveSettingsPatch({ homeOrder: full })
  }

  function onSecDragEnd() {
    dragSec = null
    dragOverSec = null
  }

  function setQpRows(mode: string) {
    saveSettingsPatch({ quickPicksRows: mode })
  }

  function toggleHomeSection(id: string) {
    const cur = new Set(core.settings?.homeHidden ?? ['discover'])
    if (cur.has(id)) cur.delete(id)
    else cur.add(id)
    saveSettingsPatch({ homeHidden: [...cur] })
  }

  async function loadMoreQuickPicks() {
    qpCols += 4
    await tick()
    qpScroller?.scrollBy({ left: 540, behavior: 'smooth' })
  }

  async function downloadDiscover(d: DiscoverItem) {
    if (downloadingIds[d.videoId]) return
    downloadingIds = { ...downloadingIds, [d.videoId]: true }
    const err = await Go.StartDownload(d.url, 'track')
    if (err) {
      core.toast(err)
      const next = { ...downloadingIds }
      delete next[d.videoId]
      downloadingIds = next
    } else {
      core.toast(`Downloading “${d.title}”`)
    }
  }

  async function playPlaylist(id: number) {
    await core.togglePlaylist(id)
  }

  function openExternal(url: string) {
    ;(Go as any).OpenInBrowser?.(url)
  }
</script>

<div class="view-head" style="display:flex;align-items:center;justify-content:space-between;gap:12px">
  <div class="view-title">{greeting}</div>
  <div style="display:flex;align-items:center;gap:8px;position:relative">
    {#if editingLayout}
      <div class="sec-popover-wrap">
        <button
          class="btn ghost sec-menu-btn"
          class:active={showSectionsMenu}
          onclick={(e) => {
            e.stopPropagation()
            showSectionsMenu = !showSectionsMenu
          }}
        >
          <Icon name="settings" size={16} />
          <span>Add / Remove Sections</span>
          <Icon name="chevron-down" size={16} />
        </button>

        {#if showSectionsMenu}
          <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
          <div class="sec-popover" onclick={(e) => e.stopPropagation()}>
            <div class="sec-popover-head">
              <span>Home Sections</span>
              <button class="link-btn" onclick={() => { showSectionsMenu = false; core.go({ name: 'settings' }) }}>All settings →</button>
            </div>
            <div class="sec-popover-list">
              {#each SECTION_LIST as item (item.id)}
                {@const checked = !hiddenSet.has(item.id)}
                <button class="sec-check-row" class:on={checked} onclick={() => toggleHomeSection(item.id)}>
                  <span class="sec-checkbox" class:checked>
                    {#if checked}<Icon name="check" size={14} />{/if}
                  </span>
                  <div class="sec-check-txt">
                    <div class="t">{item.title}</div>
                    <div class="s">{item.sub}</div>
                  </div>
                </button>
              {/each}
            </div>
          </div>
        {/if}
      </div>
    {/if}
    <button
      class="icon-btn edit-home-btn"
      class:active={editingLayout}
      onclick={() => {
        editingLayout = !editingLayout
        if (!editingLayout) showSectionsMenu = false
      }}
      title={editingLayout ? 'Done customizing Home' : 'Customize Home layout'}
      aria-label="Customize Home layout"
    >
      <Icon name={editingLayout ? 'check' : 'edit'} size={18} />
    </button>
  </div>
</div>

<svelte:window onclick={() => { if (showSectionsMenu) showSectionsMenu = false }} />

{#if editingLayout}
  <div class="home-edit-banner">
    <span>Hold & drag any section (or use <b>↑</b> / <b>↓</b>) to reorder your Home page. <b>All your music</b> stays pinned at the bottom.</span>
  </div>
{/if}

{#snippet reorderControls(secId: string)}
  {#if editingLayout && secId !== 'allMusic'}
    {@const idx = movableVisible.indexOf(secId)}
    <div class="sec-reorder">
      <span class="sec-drag-grip" title="Hold and drag to reorder">
        <Icon name="drag" size={18} />
      </span>
      <button class="icon-btn" disabled={idx <= 0} onclick={() => moveSection(secId, -1)} title="Move section up" aria-label="Move up">
        <Icon name="chevron-up" size={18} />
      </button>
      <button class="icon-btn" disabled={idx < 0 || idx >= movableVisible.length - 1} onclick={() => moveSection(secId, 1)} title="Move section down" aria-label="Move down">
        <Icon name="chevron-down" size={18} />
      </button>
    </div>
  {/if}
{/snippet}

{#each visibleSections as sec (sec)}
  {#if sec === 'recentPlaylists'}
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div
      class="home-block"
      class:editing={editingLayout}
      class:sec-dragover={dragOverSec === 'recentPlaylists'}
      draggable={editingLayout}
      ondragstart={(e) => onSecDragStart(e, 'recentPlaylists')}
      ondragover={(e) => onSecDragOver(e, 'recentPlaylists')}
      ondrop={(e) => onSecDrop(e, 'recentPlaylists')}
      ondragend={onSecDragEnd}
    >
      {#if editingLayout}
        <div class="section-head" style="margin-bottom:8px">
          <h2>Recent playlists</h2>
          {@render reorderControls('recentPlaylists')}
        </div>
      {/if}
      {#if recent.length > 0}
        <div class="recent-grid">
          {#each recent as p (p.id)}
            {@const isPlPlaying = core.player.playlistId === p.id && core.player.playing}
            <div class="recent-card" class:playing={core.player.playlistId === p.id} role="button" tabindex="0" onclick={() => core.go({ name: 'playlist', id: p.id })} onkeydown={(e) => e.key === 'Enter' && core.go({ name: 'playlist', id: p.id })}>
              <Cover ids={p.coverTracks && p.coverTracks.length ? p.coverTracks : null} icon="library" class="thumb" />
              <div class="t">{p.name}</div>
              <button
                class="play-fab"
                class:playing={isPlPlaying}
                aria-label={isPlPlaying ? 'Pause' : 'Play'}
                onclick={(e) => {
                  e.stopPropagation()
                  playPlaylist(p.id)
                }}><Icon name={isPlPlaying ? 'pause' : 'play'} size={24} /></button
              >
            </div>
          {/each}
        </div>
      {:else}
        <div class="card-surface" style="padding:18px 20px;background:var(--bg-elev2);color:var(--text-dim)">
          Playlists you play will show up here. <button class="link-btn" onclick={() => core.go({ name: 'playlists' })}>Go to your playlists →</button>
        </div>
      {/if}
    </div>

  {:else if sec === 'topTracks'}
    {#if mostPlayed.length > 0 || editingLayout}
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div
        class="section home-block"
        class:editing={editingLayout}
        class:sec-dragover={dragOverSec === 'topTracks'}
        draggable={editingLayout}
        ondragstart={(e) => onSecDragStart(e, 'topTracks')}
        ondragover={(e) => onSecDragOver(e, 'topTracks')}
        ondrop={(e) => onSecDrop(e, 'topTracks')}
        ondragend={onSecDragEnd}
      >
        <div class="section-head">
          <h2>Your top tracks</h2>
          <div style="display:flex;align-items:center;gap:10px">
            {#if mostPlayed.length > 0}
              <button class="link-btn" onclick={() => core.go({ name: 'mostplayed' })}>Show all</button>
            {/if}
            {@render reorderControls('topTracks')}
          </div>
        </div>
        {#if mostPlayed.length === 0}
          <div class="hint">Play some tracks and your most listened songs will appear here.</div>
        {:else}
          <div class="shelf" use:horizontalScroll>
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
        {/if}
      </div>
    {/if}

  {:else if sec === 'quickPicks'}
    {#if core.tracks.length > 0 || editingLayout}
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div
        class="section home-block"
        class:editing={editingLayout}
        class:sec-dragover={dragOverSec === 'quickPicks'}
        draggable={editingLayout}
        ondragstart={(e) => onSecDragStart(e, 'quickPicks')}
        ondragover={(e) => onSecDragOver(e, 'quickPicks')}
        ondrop={(e) => onSecDrop(e, 'quickPicks')}
        ondragend={onSecDragEnd}
      >
        <div class="section-head">
          <div>
            <h2>Quick picks</h2>
            <div class="hint" style="margin-top:2px">Click any song to start a local radio mix, or hover the cover to preview.</div>
          </div>
          <div style="display:flex;align-items:center;gap:8px">
            {#if editingLayout}
              <div class="seg qp-rows-seg" title="Quick picks row height">
                {#each [{ v: '1', l: '1x' }, { v: '2', l: '2x' }, { v: '3', l: '3x' }, { v: 'auto', l: 'Auto' }] as opt}
                  <button class:on={qpRowsMode === opt.v} onclick={() => setQpRows(opt.v)}>{opt.l}</button>
                {/each}
              </div>
            {/if}
            <button
              class="btn ghost"
              style="display:flex;align-items:center;gap:6px"
              onclick={() => {
                qpSeed++
                qpCols = 6
                qpScroller?.scrollTo({ left: 0, behavior: 'smooth' })
              }}
              title="Shuffle quick picks"
            >
              <Icon name="refresh" size={16} /> Refresh
            </button>
            {#if hasMoreQuickPicks}
              <button class="btn ghost" style="display:flex;align-items:center;gap:6px" onclick={loadMoreQuickPicks}>
                <Icon name="plus" size={16} /> More
              </button>
            {/if}
            {@render reorderControls('quickPicks')}
          </div>
        </div>

        <div
          class="qp-strip"
          bind:this={qpScroller}
          use:horizontalScroll
          style="--qp-rows: {effectiveQuickRows}"
        >
          {#each visibleQuickPicks as t (t.id)}
            {@const isPlaying = core.player.trackId === t.id && core.player.playing}
            <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
            <div
              class="qp-card"
              class:playing={core.player.trackId === t.id}
              onclick={() => core.startRadio(t)}
              oncontextmenu={(e) => core.openMenu(e, trackMenu(t))}
            >
              <div class="qp-thumb">
                <Cover id={t.hasCover ? t.id : 0} class="thumb" />
                <button
                  class="mini-play-fab"
                  class:playing={isPlaying}
                  onclick={(e) => {
                    e.stopPropagation()
                    core.playInstant(t.id)
                  }}
                  title="Try song (plays without touching queue)"
                  aria-label="Try song"
                >
                  <Icon name={isPlaying ? 'pause' : 'play'} size={16} />
                </button>
              </div>
              <div class="qp-txt">
                <div class="t-title">{t.title}</div>
                <div class="t-artist">{t.artist}</div>
              </div>
              <button
                class="more qp-more"
                onclick={(e) => {
                  e.stopPropagation()
                  core.openMenu(e, trackMenu(t))
                }}
                title="More options"
                aria-label="More options"
              >
                <Icon name="more" size={18} />
              </button>
            </div>
          {/each}

          {#if hasMoreQuickPicks}
            <button class="qp-more-tile" onclick={loadMoreQuickPicks} title="Generate more quick picks">
              <Icon name="plus" size={22} />
              <span>More</span>
            </button>
          {/if}
        </div>
      </div>
    {/if}

  {:else if sec === 'artistRadios'}
    {#if artistRadios.length > 0 || editingLayout}
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div
        class="section home-block"
        class:editing={editingLayout}
        class:sec-dragover={dragOverSec === 'artistRadios'}
        draggable={editingLayout}
        ondragstart={(e) => onSecDragStart(e, 'artistRadios')}
        ondragover={(e) => onSecDragOver(e, 'artistRadios')}
        ondrop={(e) => onSecDrop(e, 'artistRadios')}
        ondragend={onSecDragEnd}
      >
        <div class="section-head">
          <div>
            <h2>Artist radios</h2>
            <div class="hint" style="margin-top:2px">Non-stop artist mixes combining your local songs with new online discoveries.</div>
          </div>
          {@render reorderControls('artistRadios')}
        </div>
        {#if artistRadios.length === 0}
          <div class="hint">Add or play some music and artist radio stations will appear here.</div>
        {:else}
          <div class="shelf" use:horizontalScroll>
            {#each artistRadios as ar (ar.name)}
              {@const seedTrack = core.tracks.find((t) => hasArtist(t, ar.name))}
              <Card
                title="{ar.name} Radio"
                sub="{ar.count} local song{ar.count === 1 ? '' : 's'} + online mix"
                ids={ar.covers.length > 1 ? ar.covers : null}
                id={ar.covers.length === 1 ? ar.covers[0] : 0}
                icon="person"
                onclick={() => core.go({ name: 'artistradio', id: ar.name })}
                onplay={() => {
                  if (seedTrack) core.startRadio(seedTrack, 'artist')
                }}
              />
            {/each}
          </div>
        {/if}
      </div>
    {/if}

  {:else if sec === 'discover'}
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div
      class="section home-block"
      class:editing={editingLayout}
      class:sec-dragover={dragOverSec === 'discover'}
      draggable={editingLayout}
      ondragstart={(e) => onSecDragStart(e, 'discover')}
      ondragover={(e) => onSecDragOver(e, 'discover')}
      ondrop={(e) => onSecDrop(e, 'discover')}
      ondragend={onSecDragEnd}
    >
      <div class="section-head">
        <div>
          <h2>Discover new music</h2>
          <div class="hint" style="margin-top:2px">YouTube Music recommendations based on your library that you don't own yet.</div>
        </div>
        <div style="display:flex;align-items:center;gap:8px">
          <button
            class="btn ghost"
            disabled={core.discoverLoading}
            style="display:flex;align-items:center;gap:6px"
            onclick={() => core.fetchOnlineDiscover(false)}
          >
            <Icon name="refresh" size={16} /> {core.discoverLoading ? 'Loading…' : 'Refresh'}
          </button>
          <button
            class="btn ghost"
            disabled={core.discoverLoading}
            style="display:flex;align-items:center;gap:6px"
            onclick={() => core.fetchOnlineDiscover(true)}
          >
            <Icon name="plus" size={16} /> More
          </button>
          {@render reorderControls('discover')}
        </div>
      </div>
      {#if core.discoverLoading && core.discoverItems.length === 0}
        <div class="hint" style="padding:18px 0">Finding songs you might like from YouTube Music…</div>
      {:else if core.discoverItems.length === 0}
        <div class="hint" style="padding:18px 0">
          No online suggestions loaded yet. Click <b>Refresh</b> to fetch recommendations from your library tracks.
        </div>
      {:else}
        <div class="shelf" use:horizontalScroll>
          {#each core.discoverItems as d (d.videoId)}
            <div class="card discover-card">
              <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
              <div class="art discover-art" onclick={() => openExternal(d.url)} title="Try out in browser (YouTube)">
                <img src={d.thumbUrl} alt="" loading="lazy" style="width:100%;height:100%;object-fit:cover;display:block" />
                <div class="discover-try-overlay">
                  <div class="discover-try-pill">
                    <Icon name="external" size={16} />
                    <span>Open in browser</span>
                  </div>
                </div>
              </div>
              <div class="title" title={d.title}>{d.title}</div>
              <div class="sub">{d.artist}{d.duration > 0 ? ` · ${fmtTime(d.duration)}` : ''}</div>
              <button
                class="btn ghost discover-dl-btn"
                disabled={!!downloadingIds[d.videoId]}
                onclick={() => downloadDiscover(d)}
              >
                <Icon name={downloadingIds[d.videoId] ? 'check' : 'download'} size={15} />
                {downloadingIds[d.videoId] ? 'Queued' : 'Download'}
              </button>
            </div>
          {/each}
        </div>
      {/if}
    </div>

  {:else if sec === 'recentlyAdded'}
    {#if recentlyAdded.length > 0 || editingLayout}
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div
        class="section home-block"
        class:editing={editingLayout}
        class:sec-dragover={dragOverSec === 'recentlyAdded'}
        draggable={editingLayout}
        ondragstart={(e) => onSecDragStart(e, 'recentlyAdded')}
        ondragover={(e) => onSecDragOver(e, 'recentlyAdded')}
        ondrop={(e) => onSecDrop(e, 'recentlyAdded')}
        ondragend={onSecDragEnd}
      >
        <div class="section-head">
          <h2>Recently added</h2>
          <div style="display:flex;align-items:center;gap:10px">
            {#if recentlyAdded.length > 0}
              <button class="link-btn" onclick={() => core.go({ name: 'recent' })}>Show all</button>
            {/if}
            {@render reorderControls('recentlyAdded')}
          </div>
        </div>
        <div class="shelf" use:horizontalScroll>
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

  {:else if sec === 'allMusic'}
    <div class="section home-block" class:editing={editingLayout}>
      <div class="section-head">
        <h2>All your music <span style="color:var(--text-dim);font-weight:600;font-size:15px">· {core.tracks.length} songs</span></h2>
        <div style="display:flex;gap:10px;align-items:center">
          {#if core.tracks.length > 50}
            <button class="btn ghost" onclick={() => (showAllHomeTracks = !showAllHomeTracks)}>
              {showAllHomeTracks ? 'Show 50' : `Show all (${core.tracks.length})`}
            </button>
          {/if}
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
        <TrackList tracks={showAllHomeTracks ? core.tracks : core.tracks.slice(0, 50)} sortable />
        {#if !showAllHomeTracks && core.tracks.length > 50}
          <div style="display:flex;justify-content:center;margin-top:16px">
            <button class="btn ghost" onclick={() => (showAllHomeTracks = true)}>
              Show all {core.tracks.length} songs
            </button>
          </div>
        {/if}
      {/if}
    </div>
  {/if}
{/each}
