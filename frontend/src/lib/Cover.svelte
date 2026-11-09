<script lang="ts">
  import Icon from './Icon.svelte'
  import { coverUrl } from './util'

  let {
    id = 0,
    ids = null,
    icon = 'note',
    class: cls = '',
  }: { id?: number; ids?: number[] | null; icon?: string; class?: string } = $props()
  let failed = $state(false)
</script>

<div class="cover {cls}">
  {#if ids && ids.length >= 4}
    <div class="mosaic">
      {#each ids.slice(0, 4) as i}
        <div class="cell"><img src={coverUrl(i)} alt="" loading="lazy" /></div>
      {/each}
    </div>
  {:else if ids && ids.length > 0}
    <img src={coverUrl(ids[0])} alt="" loading="lazy" />
  {:else if id && !failed}
    <img src={coverUrl(id)} alt="" loading="lazy" onerror={() => (failed = true)} />
  {:else}
    <Icon name={icon} size={28} />
  {/if}
</div>
