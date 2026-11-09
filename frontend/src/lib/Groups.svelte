<script lang="ts">
  import Card from './Card.svelte'
  import { core } from './store.svelte'
  import { groupBy } from './util'

  let { mode }: { mode: 'albums' | 'artists' } = $props()

  const groups = $derived(groupBy(core.tracks, mode === 'albums' ? 'album' : 'artist'))
</script>

<div class="view-head"><div class="view-title">{mode === 'albums' ? 'Albums' : 'Artists'}</div></div>

{#if groups.length === 0}
  <div class="empty"><div class="big">Nothing to show</div>Tracks need {mode === 'albums' ? 'album' : 'artist'} tags.</div>
{/if}

<div class="grid">
  {#each groups as g (g.key)}
    <Card
      title={g.name}
      sub={mode === 'albums' ? g.sub || `${g.tracks.length} songs` : `${g.tracks.length} song${g.tracks.length === 1 ? '' : 's'}`}
      id={g.coverId}
      icon={mode === 'albums' ? 'album' : 'person'}
      round={mode === 'artists'}
      onclick={() => core.go(mode === 'albums' ? { name: 'album', id: g.name } : { name: 'artist', id: g.name })}
      onplay={() => core.play(g.tracks.map((t) => t.id), -1)}
    />
  {/each}
</div>
