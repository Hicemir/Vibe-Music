<script lang="ts">
  import Icon from './Icon.svelte'
  import { coverUrl } from './util'

  let {
    id = '',
    ids = null,
    icon = 'note',
    class: cls = '',
    size = 0,
  }: { id?: string; ids?: string[] | null; icon?: string; class?: string; size?: number } = $props()
  let failedId = $state('')
  const failed = $derived(!!id && failedId === id)
  const cellSize = $derived(size > 0 && size <= 128 ? 128 : 300)
  const singleDim = $derived(size || 600)
</script>

<div class="cover {cls}">
  {#if ids && ids.length >= 4}
    <div class="mosaic">
      {#each ids.slice(0, 4) as i}
        <div class="cell"><img src={coverUrl(i, cellSize)} width={cellSize} height={cellSize} alt="" loading="lazy" decoding="async" /></div>
      {/each}
    </div>
  {:else if ids && ids.length > 0}
    <img src={coverUrl(ids[0], size || undefined)} width={singleDim} height={singleDim} alt="" loading="lazy" decoding="async" />
  {:else if id && !failed}
    <img src={coverUrl(id, size || undefined)} width={singleDim} height={singleDim} alt="" loading="lazy" decoding="async" onerror={() => (failedId = id)} />
  {:else}
    <Icon name={icon} size={28} />
  {/if}
</div>
