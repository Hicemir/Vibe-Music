<script lang="ts">
  import Cover from './Cover.svelte'
  import Icon from './Icon.svelte'

  let {
    title,
    sub = '',
    id = 0,
    ids = null,
    icon = 'note',
    round = false,
    playing = false,
    onclick,
    onplay,
    onmore,
    oncontext,
  }: {
    title: string
    sub?: string
    id?: number
    ids?: number[] | null
    icon?: string
    round?: boolean
    playing?: boolean
    onclick: () => void
    onplay?: () => void
    onmore?: (e: MouseEvent) => void
    oncontext?: (e: MouseEvent) => void
  } = $props()
</script>

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="card" class:round role="button" tabindex="0" {onclick} oncontextmenu={oncontext ?? onmore} onkeydown={(e) => e.key === 'Enter' && onclick()}>
  <div class="art">
    <Cover {id} {ids} {icon} />
    {#if onmore}
      <button
        class="card-more"
        title="More options"
        aria-label="More options"
        onclick={(e) => {
          e.stopPropagation()
          onmore(e)
        }}><Icon name="more" size={18} /></button
      >
    {/if}
    {#if onplay}
      <button
        class="play-fab"
        class:playing
        aria-label="Play"
        onclick={(e) => {
          e.stopPropagation()
          onplay?.()
        }}><Icon name={playing ? 'pause' : 'play'} size={24} /></button
      >
    {/if}
  </div>
  <div class="title" class:playing>{title}</div>
  {#if sub}<div class="sub">{sub}</div>{/if}
</div>
