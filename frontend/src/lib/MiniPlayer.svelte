<script lang="ts">
  import Icon from './Icon.svelte'
  import Cover from './Cover.svelte'
  import Slider from './Slider.svelte'
  import { core, Go } from './store.svelte'
  import { fmtTime } from './util'

  const p = $derived(core.player)
  const t = $derived(core.current)

  function expand() {
    core.mini = false
    Go.SetMiniMode(false)
  }
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
  class="mini"
  onmousedown={(e) => {
    if (e.button === 0 && !(e.target as HTMLElement).closest('button, .slider')) Go.ArmWindowDrag()
  }}
>
  <div class="row1">
    <Cover id={t && t.hasCover ? t.id : 0} class="thumb" />
    <div class="txt">
      <div class="t-title">{t ? t.title : 'Nothing playing'}</div>
      <div class="t-artist">{t ? t.artist : ''}</div>
    </div>
    <button class="icon-btn" onclick={expand} title="Back to full app" aria-label="Expand"><Icon name="expand" size={20} /></button>
    <button class="icon-btn" onclick={() => Go.CloseWindow()} title="Close" aria-label="Close"><Icon name="close" size={18} /></button>
  </div>
  <div class="pb-seek">
    <span class="tm l" style="width:34px">{fmtTime(p.position)}</span>
    <Slider value={p.position} max={p.duration || 1} disabled={!p.loaded} onchange={(v) => Go.Seek(v)} />
    <span class="tm r" style="width:34px">{fmtTime(p.duration)}</span>
  </div>
  <div class="ctrl">
    <button class="icon-btn" class:on={p.shuffle} onclick={() => Go.SetShuffle(!p.shuffle)} aria-label="Shuffle"><Icon name="shuffle" size={20} /></button>
    <button class="icon-btn" onclick={() => Go.Previous()} aria-label="Previous"><Icon name="prev" size={24} /></button>
    <button class="main-play" onclick={() => core.toggle()} aria-label="Play or pause"><Icon name={p.playing ? 'pause' : 'play'} size={24} /></button>
    <button class="icon-btn" onclick={() => Go.Next()} aria-label="Next"><Icon name="next" size={24} /></button>
    <button class="icon-btn" class:on={t?.liked} onclick={() => t && core.toggleLike(t.id)} aria-label="Like"><Icon name={t?.liked ? 'heart-fill' : 'heart'} size={20} /></button>
  </div>
</div>
