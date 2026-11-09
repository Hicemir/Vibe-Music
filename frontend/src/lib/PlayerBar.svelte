<script lang="ts">
  import Icon from './Icon.svelte'
  import Cover from './Cover.svelte'
  import Slider from './Slider.svelte'
  import { core, Go } from './store.svelte'
  import { trackMenu } from './menus'
  import { fmtTime, splitArtists } from './util'

  const p = $derived(core.player)
  const t = $derived(core.current)
  let lastVol = 0
  function volInput(v: number) {
    const n = performance.now()
    if (n - lastVol > 60) {
      lastVol = n
      Go.SetVolume(Math.round(v))
    }
  }

  function sleepLabel() {
    if (p.sleepEnd) return 'End of track'
    if (p.sleepAt) {
      const m = Math.max(0, Math.ceil((p.sleepAt * 1000 - Date.now()) / 60000))
      return `${m} min`
    }
    return ''
  }
</script>

<div class="playerbar">
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div class="pb-left" oncontextmenu={(e) => t && core.openMenu(e, trackMenu(t))}>
    {#if t}
      {#if !core.coverExpanded}
        <div class="pb-thumb-wrap">
          <Cover id={t.hasCover ? t.id : 0} class="thumb" />
          <button
            class="cover-toggle-btn"
            onclick={() => (core.coverExpanded = true)}
            title="Expand cover"
            aria-label="Expand cover"
          >
            <Icon name="chevron-up" size={18} />
          </button>
        </div>
      {/if}
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
      <button class="icon-btn" class:on={t.liked} onclick={() => core.toggleLike(t.id)} title="Like (L)" aria-label="Like" style={t.liked ? 'color:var(--accent)' : ''}>
        <Icon name={t.liked ? 'heart-fill' : 'heart'} size={20} />
      </button>
      <button class="icon-btn" onclick={(e) => core.openMenu(e, trackMenu(t))} title="More options" aria-label="More options">
        <Icon name="more" size={20} />
      </button>
    {/if}
  </div>

  <div class="pb-center">
    <div class="pb-controls">
      <button class="icon-btn" class:on={p.shuffle} onclick={() => Go.SetShuffle(!p.shuffle)} title="Shuffle (S)" aria-label="Shuffle"><Icon name="shuffle" size={22} /></button>
      <button class="icon-btn" onclick={() => Go.Previous()} disabled={!t} title="Previous" aria-label="Previous"><Icon name="prev" size={26} /></button>
      <button class="main-play" onclick={() => core.toggle()} title="Play / Pause (Space)" aria-label="Play or pause">
        <Icon name={p.playing ? 'pause' : 'play'} size={26} />
      </button>
      <button class="icon-btn" onclick={() => Go.Next()} disabled={!t} title="Next" aria-label="Next"><Icon name="next" size={26} /></button>
      <button
        class="icon-btn"
        class:on={p.repeat !== 'off'}
        onclick={() => Go.SetRepeat(p.repeat === 'off' ? 'all' : p.repeat === 'all' ? 'one' : 'off')}
        title="Repeat: {p.repeat} (R)"
        aria-label="Repeat"><Icon name={p.repeat === 'one' ? 'repeat-one' : 'repeat'} size={22} /></button
      >
    </div>
    <div class="pb-seek">
      <span class="tm l">{fmtTime(p.position)}</span>
      <Slider value={p.position} max={p.duration || 1} disabled={!p.loaded} onchange={(v) => Go.Seek(v)} />
      <span class="tm r">{fmtTime(p.duration)}</span>
    </div>
  </div>

  <div class="pb-right">
    {#if p.sleepAt || p.sleepEnd}<span class="sleep-indicator">☾ {sleepLabel()}</span>{/if}
    <button class="icon-btn" class:on={core.panel === 'lyrics'} onclick={() => (core.panel = core.panel === 'lyrics' ? 'none' : 'lyrics')} title="Lyrics" aria-label="Lyrics"><Icon name="lyrics" size={20} /></button>
    <button class="icon-btn" class:on={core.panel === 'queue'} onclick={() => (core.panel = core.panel === 'queue' ? 'none' : 'queue')} title="Queue (Q)" aria-label="Queue"><Icon name="queue" size={20} /></button>
    <button class="icon-btn" class:on={p.sleepAt > 0 || p.sleepEnd} onclick={() => (core.modal = { type: 'sleep' })} title="Sleep timer" aria-label="Sleep timer"><Icon name="sleep" size={20} /></button>
    <div class="vol">
      <button
        class="icon-btn"
        onclick={() => Go.ToggleMute()}
        title="Mute / Unmute (M)"
        aria-label="Mute"><Icon name={p.muted || p.volume === 0 ? 'mute' : p.volume < 40 ? 'volume-low' : 'volume'} size={20} /></button
      >
      <div style="width:96px;display:flex">
        <Slider value={p.muted ? 0 : p.volume} max={100} onchange={(v) => Go.SetVolume(Math.round(v))} oninput={volInput} />
      </div>
    </div>
    <button class="icon-btn" onclick={() => { core.mini = true; Go.SetMiniMode(true) }} title="Mini player" aria-label="Mini player"><Icon name="mini" size={20} /></button>
  </div>
</div>
