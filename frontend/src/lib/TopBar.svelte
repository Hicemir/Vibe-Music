<script lang="ts">
  import Icon from './Icon.svelte'
  import { core, Go } from './store.svelte'

  let input: HTMLInputElement | undefined = $state()
  export function focusSearch() {
    input?.focus()
    input?.select()
  }

  function onInput() {
    if (core.route.name !== 'search') core.go({ name: 'search' })
  }

  function isInteractive(e: MouseEvent) {
    return !!(e.target as HTMLElement | null)?.closest('button, input, a, select, .search-box')
  }

  function onHeaderMouseDown(e: MouseEvent) {
    if (e.button === 0 && !isInteractive(e)) {
      Go.ArmWindowDrag()
    }
  }

  function onHeaderDblClick(e: MouseEvent) {
    if (!isInteractive(e)) {
      Go.ToggleMaximiseWindow()
    }
  }
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="topbar" onmousedown={onHeaderMouseDown} ondblclick={onHeaderDblClick}>
  <button class="round-btn" onclick={() => core.back()} disabled={core.hpos === 0} aria-label="Back"><Icon name="back" size={22} /></button>
  <button class="round-btn" onclick={() => core.forward()} disabled={core.hpos >= core.hist.length - 1} aria-label="Forward"><Icon name="forward" size={22} /></button>
  <div class="search-box">
    <Icon name="search" size={20} />
    <input bind:this={input} placeholder="What do you want to play?" bind:value={core.query} oninput={onInput} onfocus={onInput} />
    {#if core.query}
      <button onclick={() => (core.query = '')} aria-label="Clear"><Icon name="close" size={18} /></button>
    {/if}
  </div>
  {#if core.scan.scanning}
    <div class="scan-pill">Scanning library… {core.scan.done}{core.scan.total ? '/' + core.scan.total : ''}</div>
  {/if}
</div>
