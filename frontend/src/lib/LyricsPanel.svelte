<script lang="ts">
  import Icon from './Icon.svelte'
  import { core, Go } from './store.svelte'
  import { smoothScroll } from './util'
  import type { Lyrics } from './types'

  let lyrics = $state<Lyrics>({ synced: false, lines: [] })
  let loadedFor = 0
  let body: HTMLDivElement | undefined = $state()

  $effect(() => {
    const id = core.player.trackId
    if (id && id !== loadedFor) {
      loadedFor = id
      Go.GetLyrics(id).then((l) => (lyrics = { synced: !!l.synced, lines: l.lines ?? [] }))
    }
  })

  const lines = $derived(lyrics.lines ?? [])
  const active = $derived.by(() => {
    if (!lyrics.synced) return -1
    let a = -1
    for (let i = 0; i < lines.length; i++) if (lines[i].time <= core.player.position + 0.15) a = i
    return a
  })

  $effect(() => {
    if (active >= 0 && body) {
      const el = body.querySelector<HTMLElement>(`[data-i="${active}"]`)
      el?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    }
  })
</script>

<div class="panel">
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div
    class="panel-head"
    onmousedown={(e) => {
      if (e.button === 0 && !(e.target as HTMLElement).closest('button')) Go.ArmWindowDrag()
    }}
    ondblclick={(e) => {
      if (!(e.target as HTMLElement).closest('button')) Go.ToggleMaximiseWindow()
    }}
  >
    <span>Lyrics</span>
    <button class="icon-btn" onclick={() => (core.panel = 'none')} aria-label="Close"><Icon name="close" size={20} /></button>
  </div>
  <div class="panel-body" bind:this={body} use:smoothScroll>
    {#if !core.current}
      <div class="empty">Play a song to see its lyrics.</div>
    {:else if lines.length === 0}
      <div class="empty">
        <div class="big">No lyrics found</div>
        Put a <b>.lrc</b> file with the same name next to the song, or embed lyrics in its tags.
      </div>
    {:else if lyrics.synced}
      <div class="lyrics-body">
        {#each lines as l, i}
          <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
          <div class="ln synced" class:active={i === active} class:past={i < active} data-i={i} onclick={() => Go.Seek(l.time)}>{l.text || '♪'}</div>
        {/each}
      </div>
    {:else}
      <div class="lyrics-body">
        {#each lines as l}
          <div class="plain">{l.text || '\u00a0'}</div>
        {/each}
      </div>
    {/if}
  </div>
</div>
