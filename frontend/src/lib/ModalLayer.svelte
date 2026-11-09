<script lang="ts">
  import Cover from './Cover.svelte'
  import { core, Go } from './store.svelte'

  const m = $derived(core.modal)
  let name = $state('')
  let lastKey = ''

  $effect(() => {
    const key = m ? m.type + (m.type === 'rename' ? m.id : '') : ''
    if (key !== lastKey) {
      lastKey = key
      name = m?.type === 'rename' ? m.name : ''
    }
  })

  function close() {
    core.modal = null
  }

  async function createPlaylist() {
    if (m?.type !== 'newPlaylist') return
    const n = name.trim()
    if (!n) return
    const id = await Go.CreatePlaylist(n, m.trackIds ?? [])
    close()
    if (id && !m.trackIds?.length) core.go({ name: 'playlist', id })
    else core.toast(`Created “${n}”`)
  }

  async function addTo(id: number, pname: string) {
    if (m?.type !== 'addToPlaylist') return
    const n = await Go.AddToPlaylist(id, m.trackIds)
    close()
    core.toast(n > 0 ? `Added ${n} track(s) to “${pname}”` : `Already in “${pname}”`)
  }

  async function createAndAdd() {
    if (m?.type !== 'addToPlaylist') return
    const ids = m.trackIds
    core.modal = { type: 'newPlaylist', trackIds: ids }
  }

  async function rename() {
    if (m?.type !== 'rename') return
    const n = name.trim()
    if (n) await Go.RenamePlaylist(m.id, n)
    close()
  }

  const sleepOptions = [
    { label: '15 minutes', v: 15 },
    { label: '30 minutes', v: 30 },
    { label: '45 minutes', v: 45 },
    { label: '1 hour', v: 60 },
    { label: '2 hours', v: 120 },
    { label: 'End of this track', v: -1 },
  ]
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && m && close()} />

{#if m}
  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
  <div class="overlay" onclick={close}>
    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
    <div class="modal" onclick={(e) => e.stopPropagation()}>
      {#if m.type === 'newPlaylist'}
        <h3>New playlist</h3>
        <!-- svelte-ignore a11y_autofocus -->
        <input class="pill-input" placeholder="Playlist name" bind:value={name} autofocus onkeydown={(e) => e.key === 'Enter' && createPlaylist()} />
        <div class="row">
          <button class="btn ghost" onclick={close}>Cancel</button>
          <button class="btn primary" disabled={!name.trim()} onclick={createPlaylist}>Create</button>
        </div>
      {:else if m.type === 'rename'}
        <h3>Rename playlist</h3>
        <!-- svelte-ignore a11y_autofocus -->
        <input class="pill-input" bind:value={name} autofocus onkeydown={(e) => e.key === 'Enter' && rename()} />
        <div class="row">
          <button class="btn ghost" onclick={close}>Cancel</button>
          <button class="btn primary" disabled={!name.trim()} onclick={rename}>Save</button>
        </div>
      {:else if m.type === 'addToPlaylist'}
        <h3>Add to playlist</h3>
        <div class="list">
          <button onclick={createAndAdd}>
            <Cover icon="plus" class="thumb" /> <b>New playlist…</b>
          </button>
          {#each core.playlists as p (p.id)}
            <button onclick={() => addTo(p.id, p.name)}>
              <Cover ids={p.coverTracks && p.coverTracks.length ? p.coverTracks : null} icon="library" class="thumb" />
              <div><div style="font-weight:600">{p.name}</div><div class="hint">{p.trackCount} songs</div></div>
            </button>
          {/each}
        </div>
        <div class="row"><button class="btn ghost" onclick={close}>Cancel</button></div>
      {:else if m.type === 'confirm'}
        <h3>{m.title}</h3>
        <p class="hint" style="font-size:14px">{m.message}</p>
        <div class="row">
          <button class="btn ghost" onclick={close}>Cancel</button>
          <button
            class="btn {m.danger ? 'danger' : 'primary'}"
            onclick={() => {
              const f = m.onConfirm
              close()
              f()
            }}>{m.confirmLabel}</button
          >
        </div>
      {:else if m.type === 'sleep'}
        <h3>Sleep timer</h3>
        <div class="list">
          {#each sleepOptions as o}
            <button
              onclick={() => {
                Go.SetSleepTimer(o.v)
                close()
                core.toast(`Music will pause after ${o.label.toLowerCase()}`)
              }}>{o.label}</button
            >
          {/each}
          {#if core.player.sleepAt || core.player.sleepEnd}
            <button
              style="color:var(--danger)"
              onclick={() => {
                Go.SetSleepTimer(0)
                close()
              }}>Turn off timer</button
            >
          {/if}
        </div>
        <div class="row"><button class="btn ghost" onclick={close}>Close</button></div>
      {/if}
    </div>
  </div>
{/if}
