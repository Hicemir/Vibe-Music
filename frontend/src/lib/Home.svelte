<script lang="ts">
  import { tick } from 'svelte'
  import Icon from './Icon.svelte'
  import Card from './Card.svelte'
  import Cover from './Cover.svelte'
  import TrackList from './TrackList.svelte'
  import { core, Go } from './store.svelte'
  import { trackMenu } from './menus'
  import { buildQuickPicks, fmtTime } from './util'
  import type { DiscoverItem, Settings } from './types'

  const hour = new Date().getHours()
  const greeting = hour < 5 ? 'Late night vibes' : hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'

  const DEFAULT_ORDER = ['recentPlaylists', 'topTracks', 'quickPicks', 'discover', 'recentlyAdded', 'allMusic']

  let editingLayout = $state(false)
  let qpSeed = $state(0)
  let qpCols = $state(6)
  let qpScroller: HTMLDivElement | undefined = $state()
  let downloadingIds = $state<Record<string, boolean>>({})

  const homeOrder = $derived.by(() => {
    const raw = core.settings?.homeOrder
    if (!raw || !raw.length) return DEFAULT_ORDER
    const seen = new Set(raw)
    const out = [...raw]
    for (const k of DEFAULT_ORDER) {
      if (!seen.has(k)) out.push(k)
    }
    return out
  })

  const hiddenSet = $derived(new Set(core.settings?.homeHidden ?? ['discover']))
  const visibleSections = $derived(homeOrder.filter((id) => !hiddenSet.has(id)))

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
    const vis = visibleSections.slice()
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

  function setQpRows(mode: string) {
    saveSettingsPatch({ quickPicksRows: mode })
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

<div class="view-head">
  <div class="view-title">{greeting}</div>
  <div style="display:flex;align-items:center;gap:8px">
    {#if editingLayout}
      <button class="btn ghost" onclick={() => core.go({ name: 'settings' })}>
        <Icon name="settings" size={16} /> Add / Remove Sections
      </button>
    {/if}
    <button
      class="icon-btn edit-home-btn"
      class:active={editingLayout}
      onclick={() => (editingLayout = !editingLayout)}
      title={editingLayout ? 'Done reordering Home' : 'Reorder Home sections'}
      aria-label="Reorder Home sections"
    >
      <Icon name={editingLayout ? 'check' : 'edit'} size={18} />
    </button>
  </div>
</div>

{#if editingLayout}
  <div class="home-edit-banner">
    <span>Use the <b>↑</b> / <b>↓</b> arrows on any section below to reorder your Home page, or toggle sections in Settings.</span>
  </div>
{/if}

{#snippet reorderControls(secId: string)}
  {#if editingLayout}
    {@const idx = visibleSections.indexOf(secId)}
    <div class="sec-reorder">
      <button class="icon-btn" disabled={idx <= 0} onclick={() => moveSection(secId, -1)} title="Move section up" aria-label="Move up">
        <Icon name="chevron-up" size={18} />
      </button>
      <button class="icon-btn" disabled={idx < 0 || idx >= visibleSections.length - 1} onclick={() => moveSection(secId, 1)} title="Move section down" aria-label="Move down">
        <Icon name="chevron-down" size={18} />
      </button>
    </div>
  {/if}
{/snippet}

{#each visibleSections as sec (sec)}
  {#if sec === 'recentPlaylists'}
    <div class="home-block" class:editing={editingLayout}>
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
      <div class="section home-block" class:editing={editingLayout}>
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
        {/if}
      </div>
    {/if}

  {:else if sec === 'quickPicks'}
    {#if core.tracks.length > 0 || editingLayout}
      <div class="section home-block" class:editing={editingLayout}>
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

  {:else if sec === 'discover'}
    <div class="section home-block" class:editing={editingLayout}>
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
        <div class="shelf">
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
      <div class="section home-block" class:editing={editingLayout}>
        <div class="section-head">
          <h2>Recently added</h2>
          <div style="display:flex;align-items:center;gap:10px">
            {#if recentlyAdded.length > 0}
              <button class="link-btn" onclick={() => core.go({ name: 'recent' })}>Show all</button>
            {/if}
            {@render reorderControls('recentlyAdded')}
          </div>
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

  {:else if sec === 'allMusic'}
    <div class="section home-block" class:editing={editingLayout}>
      <div class="section-head">
        <h2>All your music <span style="color:var(--text-dim);font-weight:600;font-size:15px">· {core.tracks.length} songs</span></h2>
        <div style="display:flex;gap:10px;align-items:center">
          <button class="btn primary" disabled={!core.tracks.length} onclick={() => core.play(core.tracks.map((t) => t.id), -1)}>Play</button>
          <button class="btn" disabled={!core.tracks.length} onclick={() => core.play(core.tracks.map((t) => t.id), -1, { shuffle: true })}>Shuffle</button>
          {@render reorderControls('allMusic')}
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
  {/if}
{/each}
