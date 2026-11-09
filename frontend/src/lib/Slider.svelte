<script lang="ts">
  import { clamp } from './util'

  let {
    value,
    max = 100,
    disabled = false,
    onchange,
    oninput,
  }: {
    value: number
    max?: number
    disabled?: boolean
    onchange?: (v: number) => void
    oninput?: (v: number) => void
  } = $props()

  let el: HTMLDivElement
  let dragging = $state(false)
  let dragVal = $state(0)

  const shown = $derived(dragging ? dragVal : value)
  const pct = $derived(max > 0 ? clamp((shown / max) * 100, 0, 100) : 0)

  function pos(e: PointerEvent) {
    const r = el.getBoundingClientRect()
    return clamp((e.clientX - r.left) / r.width, 0, 1) * max
  }
  function down(e: PointerEvent) {
    if (disabled) return
    el.setPointerCapture(e.pointerId)
    dragging = true
    dragVal = pos(e)
    oninput?.(dragVal)
  }
  function move(e: PointerEvent) {
    if (!dragging) return
    dragVal = pos(e)
    oninput?.(dragVal)
  }
  function up(e: PointerEvent) {
    if (!dragging) return
    dragVal = pos(e)
    dragging = false
    onchange?.(dragVal)
  }
</script>

<div class="slider" class:drag={dragging} bind:this={el} onpointerdown={down} onpointermove={move} onpointerup={up} onpointercancel={up} role="slider" aria-valuenow={shown} tabindex="-1">
  <div class="rail"></div>
  <div class="fill" style="width:{pct}%"></div>
  <div class="knob" style="left:{pct}%"></div>
</div>
