<script lang="ts">
  import Icon from './Icon.svelte'
  import { core } from './store.svelte'

  let el: HTMLDivElement | undefined = $state()
  const m = $derived(core.menu)

  // keep the menu within the window
  const pos = $derived.by(() => {
    if (!m) return { x: 0, y: 0 }
    const w = 240
    const h = m.items.length * 40 + 12
    return { x: Math.min(m.x, window.innerWidth - w - 8), y: Math.min(m.y, window.innerHeight - h - 8) }
  })
</script>

<svelte:window onclick={() => core.closeMenu()} oncontextmenu={() => core.closeMenu()} onblur={() => core.closeMenu()} onkeydown={(e) => e.key === 'Escape' && core.closeMenu()} />

{#if m}
  <div class="menu" bind:this={el} style="left:{pos.x}px;top:{pos.y}px" role="menu">
    {#each m.items as it}
      {#if it.separator}
        <hr />
      {:else}
        <button
          class:danger={it.danger}
          disabled={it.disabled}
          onclick={(e) => {
            e.stopPropagation()
            core.closeMenu()
            it.action?.()
          }}
        >
          {#if it.icon}<Icon name={it.icon} size={18} />{/if}{it.label}
        </button>
      {/if}
    {/each}
  </div>
{/if}
