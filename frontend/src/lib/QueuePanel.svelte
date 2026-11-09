<script lang="ts">
  import Icon from './Icon.svelte'
  import Cover from './Cover.svelte'
  import { core, Go } from './store.svelte'
  import { fmtTime } from './util'

  let dragFrom = $state(-1)
  let dragOver = $state(-1)

  const idx = $derived(core.player.queueIndex)
  const current = $derived(core.queue[idx])
  const upcoming = $derived(core.queue.slice(idx + 1).map((e, i) => ({ e, i: idx + 1 + i })))
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
    <span>Queue</span>
    <button class="icon-btn" onclick={() => (core.panel = 'none')} aria-label="Close"><Icon name="close" size={20} /></button>
  </div>
  <div class="panel-body">
    {#if core.queue.length === 0}
      <div class="empty"><div class="big">Your queue is empty</div>Play something, or right-click a song and choose “Add to queue”.</div>
    {/if}
    {#if current && core.byId.get(current.tid)}
      {@const t = core.byId.get(current.tid)!}
      <div class="q-label">Now playing</div>
      <div class="q-row current">
        <Cover id={t.hasCover ? t.id : 0} class="thumb" />
        <div class="txt">
          <div class="t-title">{t.title}</div>
          <div class="t-artist">{t.artist}</div>
        </div>
        <span class="eq-anim" class:paused={!core.player.playing}><i></i><i></i><i></i></span>
      </div>
    {/if}
    {#if upcoming.length}
      <div class="q-label">
        <span>Next up · {upcoming.length}</span>
        <button class="link-btn" onclick={() => Go.ClearQueue()}>Clear queue</button>
      </div>
      {#each upcoming as { e, i } (e.qid)}
        {@const t = core.byId.get(e.tid)}
        {#if t}
          <div
            class="q-row"
            class:dragover={dragOver === i && dragFrom !== i}
            role="listitem"
            draggable="true"
            ondragstart={(ev) => {
              dragFrom = i
              ev.dataTransfer?.setData('text/plain', String(i))
            }}
            ondragover={(ev) => {
              if (dragFrom >= 0) {
                ev.preventDefault()
                dragOver = i
              }
            }}
            ondragend={() => {
              dragFrom = -1
              dragOver = -1
            }}
            ondrop={(ev) => {
              ev.preventDefault()
              if (dragFrom >= 0 && dragFrom !== i) Go.MoveInQueue(dragFrom, i)
              dragFrom = -1
              dragOver = -1
            }}
          >
            <span class="grip"><Icon name="drag" size={18} /></span>
            <Cover id={t.hasCover ? t.id : 0} class="thumb" />
            <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
            <div class="txt" ondblclick={() => Go.PlayQueueIndex(i)} onclick={() => Go.PlayQueueIndex(i)}>
              <div class="t-title">{t.title}</div>
              <div class="t-artist">{t.artist}</div>
            </div>
            <span class="t-dur">{fmtTime(t.duration)}</span>
            <button class="rm icon-btn" style="width:28px;height:28px" onclick={() => Go.RemoveFromQueue(e.qid)} aria-label="Remove"><Icon name="close" size={16} /></button>
          </div>
        {/if}
      {/each}
    {/if}
  </div>
</div>
