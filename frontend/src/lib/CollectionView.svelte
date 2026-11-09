<script lang="ts">
  import Icon from './Icon.svelte'
  import Cover from './Cover.svelte'
  import TrackList from './TrackList.svelte'
  import { core, Go } from './store.svelte'
  import { trackMenu, tracksMenu } from './menus'
  import { fmtDuration, fmtTime, matches, recommendForPlaylist, samplePool, splitArtists } from './util'
  import type { MenuItem, Playlist, Track } from './types'

  let {
    kind,
    title,
    tracks,
    coverIds = [],
    playlist = null,
    round = false,
    icon = 'note',
    sortable = true,
    onreorder,
    onremove,
    onadd,
  }: {
    kind: string
    title: string
    tracks: Track[]
    coverIds?: number[]
    playlist?: Playlist | null
    round?: boolean
    icon?: string
    sortable?: boolean
    onreorder?: (from: number, to: number) => void
    onremove?: (t: Track) => void
    onadd?: (t: Track) => void
  } = $props()

  let filter = $state('')
  let addQuery = $state('')
  let findSeed = $state(0)
  let recSeed = $state(0)
  let sortKey = $state<'' | 'title' | 'artist' | 'album' | 'duration' | 'addedAt'>('')
  let sortDir = $state(1)

  const sortOptions = $derived([
    { val: '', label: playlist ? 'Custom order' : 'Default order' },
    { val: 'addedAt:-1', label: playlist ? 'Order added (Newest first)' : 'Recently added' },
    { val: 'addedAt:1', label: playlist ? 'Order added (First to last)' : 'Oldest added' },
    { val: 'title:1', label: 'Title (A–Z)' },
    { val: 'title:-1', label: 'Title (Z–A)' },
    { val: 'artist:1', label: 'Artist (A–Z)' },
    { val: 'artist:-1', label: 'Artist (Z–A)' },
    { val: 'album:1', label: 'Album (A–Z)' },
    { val: 'duration:1', label: 'Duration (Shortest)' },
    { val: 'duration:-1', label: 'Duration (Longest)' },
  ])

  const sortSelectValue = $derived(sortKey ? `${sortKey}:${sortDir}` : '')
  const currentSortLabel = $derived(sortOptions.find((o) => o.val === sortSelectValue)?.label ?? 'Custom order')

  function applySort(val: string) {
    if (!val) {
      sortKey = ''
      sortDir = 1
      return
    }
    const [k, d] = val.split(':')
    sortKey = k as typeof sortKey
    sortDir = Number(d) || 1
  }

  function openSortMenu(e: MouseEvent) {
    const items: MenuItem[] = sortOptions.map((o) => ({
      label: o.label,
      icon: o.val === sortSelectValue ? 'check' : 'blank',
      action: () => applySort(o.val),
    }))
    core.openMenu(e, items)
  }

  const ids = $derived(tracks.map((t) => t.id))
  const inPlaylistSet = $derived(new Set(ids))
  const visible = $derived(filter.trim() ? tracks.filter((t) => matches(t, filter)) : tracks)
  const total = $derived(tracks.reduce((s, t) => s + t.duration, 0))
  const playlistId = $derived(playlist?.id ?? 0)
  const isCurrentCollection = $derived(core.player.playing && ids.includes(core.player.trackId))

  const searchResults = $derived.by(() => {
    if (!playlist) return []
    const q = addQuery.trim()
    const pool = core.tracks.filter((t) => !inPlaylistSet.has(t.id))
    if (!q) return samplePool(pool, findSeed, 12)
    return samplePool(
      pool.filter((t) => matches(t, q)),
      findSeed,
      24,
    )
  })

  const suggestions = $derived.by(() => {
    if (!playlist) return []
    return recommendForPlaylist(tracks, core.tracks, recSeed, 8)
  })

  function playAll() {
    if (isCurrentCollection) {
      Go.Pause()
      return
    }
    core.play(ids, -1, { playlistId })
  }
  function shuffleAll() {
    core.play(ids, -1, { shuffle: true, playlistId })
  }

  function moreMenu(e: MouseEvent) {
    const items: MenuItem[] = []
    if (playlist) {
      const p = playlist
      if (ids.length > 0) {
        items.push(
          { label: 'Play next', icon: 'queue', action: () => Go.PlayNext(ids) },
          { label: 'Add to queue', icon: 'plus', action: () => Go.AddToQueue(ids) },
          { separator: true },
        )
      }
      items.push(
        { label: 'Rename', icon: 'edit', action: () => (core.modal = { type: 'rename', id: p.id, name: p.name }) },
        { label: 'Export as M3U8', icon: 'export', action: () => Go.ExportPlaylist(p.id, p.name) },
        {
          label: 'Delete playlist',
          icon: 'trash',
          danger: true,
          action: () =>
            (core.modal = {
              type: 'confirm',
              title: 'Delete playlist?',
              message: `“${p.name}” will be deleted. Your music files stay untouched.`,
              confirmLabel: 'Delete',
              danger: true,
              onConfirm: () => {
                Go.DeletePlaylist(p.id)
                core.back()
              },
            }),
        },
      )
    } else {
      items.push(...tracksMenu(ids))
    }
    core.openMenu(e, items)
  }
</script>

<div class="pl-header">
  <div class="big-art" style={round ? 'border-radius:50%' : ''}>
    <Cover ids={coverIds.length ? coverIds : null} {icon} />
  </div>
  <div style="min-width:0">
    <div class="kind">{kind}</div>
    <h1>{title}</h1>
    <div class="meta">
      {tracks.length} song{tracks.length === 1 ? '' : 's'}{#if total > 0} · {fmtDuration(total)}{/if}
    </div>
  </div>
</div>

<div class="action-bar">
  <button class="big-play" onclick={playAll} disabled={tracks.length === 0} aria-label="Play">
    <Icon name={isCurrentCollection ? 'pause' : 'play'} size={32} />
  </button>
  <button class="icon-btn" class:on={core.player.shuffle} onclick={shuffleAll} disabled={tracks.length === 0} title="Shuffle play" aria-label="Shuffle">
    <Icon name="shuffle" size={30} />
  </button>
  <button class="icon-btn" onclick={moreMenu} aria-label="More options"><Icon name="more" size={30} /></button>
  {#if tracks.length > 0}
    <div class="action-right">
      {#if sortable}
        <button class="sort-btn" class:active={!!sortKey} onclick={openSortMenu} title="Sort tracks">
          <Icon name="sort" size={16} />
          <span>{currentSortLabel}</span>
          <Icon name="chevron-down" size={16} />
        </button>
      {/if}
      <input class="pill-input filter" placeholder="Filter in list" bind:value={filter} />
    </div>
  {/if}
</div>

<TrackList
  tracks={visible}
  {playlistId}
  {sortable}
  bind:sortKey
  bind:sortDir
  reorderable={!!onreorder && !filter.trim() && !sortKey}
  {onreorder}
  {onremove}
  showAlbum={true}
/>

{#if playlist && onadd}
  <div class="pl-bottom">
    <div class="section">
      <div class="section-head">
        <div>
          <h2>Let's find something for your playlist</h2>
          <div class="hint" style="margin-top:4px">Search your library, hover a cover to preview it, or click Refresh to pull up new songs.</div>
        </div>
        <button class="btn ghost" style="display:flex;align-items:center;gap:6px" onclick={() => findSeed++}>
          <Icon name="refresh" size={16} /> Refresh
        </button>
      </div>
      <div class="search-box" style="margin:12px 0 16px;width:min(480px,100%)">
        <Icon name="search" size={20} />
        <input placeholder="Search songs, artists, or albums in your library…" bind:value={addQuery} />
        {#if addQuery}
          <button onclick={() => (addQuery = '')} aria-label="Clear"><Icon name="close" size={18} /></button>
        {/if}
      </div>
      {#if searchResults.length === 0}
        <div class="hint" style="padding:12px 0">
          {addQuery.trim() ? `No matching songs outside this playlist for “${addQuery}”.` : 'Every song in your library is already in this playlist!'}
        </div>
      {:else}
        <div class="add-list">
          {#each searchResults as t (t.id)}
            {@const isPlaying = core.player.trackId === t.id && core.player.playing}
            <div
              class="add-row"
              class:playing={core.player.trackId === t.id}
              role="row"
              tabindex="-1"
              ondblclick={() => core.playInstant(t.id)}
              oncontextmenu={(e) => core.openMenu(e, trackMenu(t))}
            >
              <div class="add-thumb">
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
              <div class="txt">
                <div class="t-title">{t.title}</div>
                <div class="t-artist">
                  {#each splitArtists(t.artist) as a, ai}
                    {#if ai > 0}, {/if}
                    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions, a11y_missing_attribute -->
                    <a onclick={() => core.go({ name: 'artist', id: a })}>{a}</a>
                  {/each}
                </div>
              </div>
              <div class="t-album">
                {#if t.album}
                  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions, a11y_missing_attribute -->
                  <a onclick={() => core.go({ name: 'album', id: t.album })}>{t.album}</a>
                {/if}
              </div>
              <button class="like" class:on={t.liked} onclick={() => core.toggleLike(t.id)} title="Like" aria-label="Like">
                <Icon name={t.liked ? 'heart-fill' : 'heart'} size={18} />
              </button>
              <div class="t-dur">{fmtTime(t.duration)}</div>
              <button class="btn ghost add-btn" onclick={() => onadd(t)}>+ Add</button>
              <button class="more" onclick={(e) => core.openMenu(e, trackMenu(t))} title="More options" aria-label="More options">
                <Icon name="more" size={20} />
              </button>
            </div>
          {/each}
        </div>
      {/if}
    </div>

    {#if suggestions.length > 0}
      <div class="section">
        <div class="section-head">
          <div>
            <h2>Suggested songs</h2>
            <div class="hint" style="margin-top:4px">
              {tracks.length > 0 ? 'Based on artists, soundtracks, and titles in this playlist' : 'Popular and liked songs from your library'}
            </div>
          </div>
          <button class="btn ghost" style="display:flex;align-items:center;gap:6px" onclick={() => recSeed++}>
            <Icon name="refresh" size={16} /> Refresh
          </button>
        </div>
        <div class="add-list">
          {#each suggestions as t (t.id)}
            {@const isPlaying = core.player.trackId === t.id && core.player.playing}
            <div
              class="add-row"
              class:playing={core.player.trackId === t.id}
              role="row"
              tabindex="-1"
              ondblclick={() => core.playInstant(t.id)}
              oncontextmenu={(e) => core.openMenu(e, trackMenu(t))}
            >
              <div class="add-thumb">
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
              <div class="txt">
                <div class="t-title">{t.title}</div>
                <div class="t-artist">
                  {#each splitArtists(t.artist) as a, ai}
                    {#if ai > 0}, {/if}
                    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions, a11y_missing_attribute -->
                    <a onclick={() => core.go({ name: 'artist', id: a })}>{a}</a>
                  {/each}
                </div>
              </div>
              <div class="t-album">
                {#if t.album}
                  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions, a11y_missing_attribute -->
                  <a onclick={() => core.go({ name: 'album', id: t.album })}>{t.album}</a>
                {/if}
              </div>
              <button class="like" class:on={t.liked} onclick={() => core.toggleLike(t.id)} title="Like" aria-label="Like">
                <Icon name={t.liked ? 'heart-fill' : 'heart'} size={18} />
              </button>
              <div class="t-dur">{fmtTime(t.duration)}</div>
              <button class="btn ghost add-btn" onclick={() => onadd(t)}>+ Add</button>
              <button class="more" onclick={(e) => core.openMenu(e, trackMenu(t))} title="More options" aria-label="More options">
                <Icon name="more" size={20} />
              </button>
            </div>
          {/each}
        </div>
      </div>
    {/if}
  </div>
{/if}
