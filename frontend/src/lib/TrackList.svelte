<script lang="ts">
  import { onMount } from 'svelte'
  import Icon from './Icon.svelte'
  import Cover from './Cover.svelte'
  import { core, Go } from './store.svelte'
  import { trackMenu } from './menus'
  import { fmtTime, splitArtists } from './util'
  import type { Track } from './types'

  let {
    tracks,
    playlistId = 0,
    showAlbum = true,
    sortable = false,
    reorderable = false,
    sortKey = $bindable<'' | 'title' | 'artist' | 'album' | 'duration' | 'addedAt'>(''),
    sortDir = $bindable(1),
    onreorder,
    onremove,
    numbered = true,
  }: {
    tracks: Track[]
    playlistId?: number
    showAlbum?: boolean
    sortable?: boolean
    reorderable?: boolean
    sortKey?: '' | 'title' | 'artist' | 'album' | 'duration' | 'addedAt'
    sortDir?: number
    onreorder?: (from: number, to: number) => void
    onremove?: (t: Track) => void
    numbered?: boolean
  } = $props()

  let shown = $state(120)
  let sentinel: HTMLDivElement
  let dragFrom = $state(-1)
  let dragOver = $state(-1)

  $effect(() => {
    const needed = Math.ceil((core.targetScroll || 0) / 56) + 80
    if (needed > shown) shown = needed
  })

  const list = $derived.by(() => {
    if (!sortable || !sortKey) return tracks
    if (sortKey === 'addedAt' && playlistId > 0) {
      return sortDir === 1 ? tracks : tracks.slice().reverse()
    }
    const k = sortKey
    const d = sortDir
    return tracks.slice().sort((a, b) => {
      const x = a[k]
      const y = b[k]
      if (typeof x === 'number' && typeof y === 'number') return (x - y) * d
      return String(x).localeCompare(String(y), undefined, { sensitivity: 'base' }) * d
    })
  })

  function setSort(k: typeof sortKey) {
    if (sortKey === k) {
      if (sortDir === 1) sortDir = -1
      else {
        sortKey = ''
        sortDir = 1
      }
    } else {
      sortKey = k
      sortDir = k === 'addedAt' ? -1 : 1
    }
  }

  function playAt(i: number) {
    const cur = core.player.trackId === list[i].id && core.player.loaded
    if (cur) {
      Go.TogglePlayPause()
      return
    }
    core.play(
      list.map((t) => t.id),
      i,
      { playlistId },
    )
  }

  onMount(() => {
    const io = new IntersectionObserver(
      (es) => {
        if (es.some((e) => e.isIntersecting)) shown += 120
      },
      { rootMargin: '600px' },
    )
    io.observe(sentinel)
    return () => io.disconnect()
  })

  function arrow(k: string) {
    return sortKey === k ? (sortDir === 1 ? ' ▲' : ' ▼') : ''
  }
</script>

<div class="tl-head" class:noalbum={!showAlbum}>
  <div style="text-align:center">#</div>
  {#if sortable}
    <div style="display:flex;align-items:center;gap:6px">
      <button class="sortable" onclick={() => setSort('title')}>Title{arrow('title')}</button>
      <span style="color:var(--text-faint)">/</span>
      <button class="sortable" onclick={() => setSort('artist')}>Artist{arrow('artist')}</button>
    </div>
    {#if showAlbum}<button class="sortable" onclick={() => setSort('album')}>Album{arrow('album')}</button>{/if}
    <span></span>
    <button class="sortable" style="text-align:right" onclick={() => setSort('duration')}>Time{arrow('duration')}</button>
  {:else}
    <div>Title</div>
    {#if showAlbum}<div>Album</div>{/if}
    <span></span>
    <div style="text-align:right">Time</div>
  {/if}
  <span></span>
</div>

{#each list.slice(0, shown) as t, i (t.id + ':' + i)}
  {@const current = core.player.trackId === t.id}
  <div
    class="track"
    class:noalbum={!showAlbum}
    class:playing={current}
    class:dragover={dragOver === i && dragFrom !== i}
    role="row"
    tabindex="-1"
    draggable={reorderable}
    ondragstart={(e) => {
      dragFrom = i
      e.dataTransfer?.setData('text/plain', String(i))
    }}
    ondragover={(e) => {
      if (reorderable && dragFrom >= 0) {
        e.preventDefault()
        dragOver = i
      }
    }}
    ondragend={() => {
      dragFrom = -1
      dragOver = -1
    }}
    ondrop={(e) => {
      e.preventDefault()
      if (reorderable && dragFrom >= 0 && dragFrom !== i) onreorder?.(dragFrom, i)
      dragFrom = -1
      dragOver = -1
    }}
    ondblclick={() => core.play(list.map((x) => x.id), i, { playlistId })}
    oncontextmenu={(e) => core.openMenu(e, trackMenu(t, { onRemove: onremove ? () => onremove(t) : undefined }))}
  >
    <div class="num">
      <span class="n">
        {#if current && core.player.loaded}
          <span class="eq-anim" class:paused={!core.player.playing}><i></i><i></i><i></i></span>
        {:else if numbered}
          {i + 1}
        {/if}
      </span>
      <button class="playbtn" onclick={() => playAt(i)} aria-label="Play">
        <Icon name={current && core.player.playing ? 'pause' : 'play'} size={20} />
      </button>
    </div>
    <div class="info">
      <Cover id={t.hasCover ? t.id : 0} class="thumb" />
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
    </div>
    {#if showAlbum}
      <div class="t-album">
        {#if t.album}
          <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions, a11y_missing_attribute -->
          <a onclick={() => core.go({ name: 'album', id: t.album })}>{t.album}</a>
        {/if}
      </div>
    {/if}
    <button class="like" class:on={t.liked} onclick={() => core.toggleLike(t.id)} aria-label="Like">
      <Icon name={t.liked ? 'heart-fill' : 'heart'} size={18} />
    </button>
    <div class="t-dur">{fmtTime(t.duration)}</div>
    <button class="more" onclick={(e) => core.openMenu(e, trackMenu(t, { onRemove: onremove ? () => onremove(t) : undefined }))} aria-label="More">
      <Icon name="more" size={20} />
    </button>
  </div>
{/each}
<div class="sentinel" bind:this={sentinel}></div>
{#if list.length === 0}
  <div class="empty"><div class="big">Nothing here yet</div></div>
{/if}
