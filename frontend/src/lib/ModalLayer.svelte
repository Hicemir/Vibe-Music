<script lang="ts">
  import Cover from './Cover.svelte'
  import Icon from './Icon.svelte'
  import { core, Go } from './store.svelte'
  import { main } from '../../wailsjs/go/models'
  import type { YtMusicCollection } from './types'

  const m = $derived(core.modal)
  let name = $state('')
  let lastKey = ''

  let genreSel = $state<string[]>([])
  let genreCustom = $state('')
  const genreTops = $derived(core.genreTax.filter((n) => !n.parent))
  const genreKids = (p: string) => core.genreTax.filter((n) => n.parent === p)
  function toggleGenre(g: string) {
    genreSel = genreSel.includes(g) ? genreSel.filter((x) => x !== g) : [...genreSel, g]
  }
  function addCustomGenre() {
    const g = genreCustom.trim()
    if (g && !genreSel.includes(g)) genreSel = [...genreSel, g]
    genreCustom = ''
  }
  async function saveGenres(clear = false) {
    if (m?.type !== 'genre') return
    const ids = m.trackIds
    const list = clear ? [] : genreSel
    close()
    await Go.SetTracksGenre(ids, list)
    core.toast(list.length ? `Genres set: ${list.join(', ')}` : 'Genres cleared — they will be detected again')
  }
  async function movePrimary(move: boolean) {
    if (m?.type !== 'movePrompt') return
    const { newPrimary, others, nextDirs } = m
    close()
    await Go.SaveSettings({ ...core.settings!, musicDirs: nextDirs, downloadDir: newPrimary } as never)
    if (move && others.length) await Go.MoveMusic(others, newPrimary)
  }

  let ytmLoading = $state(false)
  let ytmError = $state('')
  let ytmCollections = $state.raw<YtMusicCollection[]>([])
  let ytmFilter = $state<'all' | 'playlists' | 'albums'>('all')
  let ytmSelected = $state<Set<string>>(new Set())

  const ytmFiltered = $derived(
    ytmCollections.filter((c) => {
      if (ytmFilter === 'playlists') return c.type === 'PLAYLIST' || c.type === 'LIKED_MUSIC'
      if (ytmFilter === 'albums') return c.type === 'ALBUM'
      return true
    }),
  )

  const ytmAllVisibleSelected = $derived(
    ytmFiltered.length > 0 && ytmFiltered.every((c) => ytmSelected.has(c.id)),
  )

  const ytmSubtitle = $derived.by(() => {
    if (ytmLoading) return 'Loading your YouTube Music library…'
    if (ytmError) return ytmError
    if (ytmCollections.length === 0) return 'Could not load library'
    const plCount = ytmCollections.filter((c) => c.type === 'PLAYLIST').length
    const alCount = ytmCollections.filter((c) => c.type === 'ALBUM').length
    return `Found Liked Music, ${plCount} playlist(s) & ${alCount} saved album(s)`
  })

  async function loadYtmLibrary() {
    ytmLoading = true
    ytmError = ''
    ytmCollections = []
    ytmFilter = 'all'
    ytmSelected = new Set()
    try {
      const res = await Go.FetchYtMusicLibrary()
      if (res.error) {
        ytmError = res.error
        ytmCollections = []
      } else {
        const cols = (res.collections ?? []) as unknown as YtMusicCollection[]
        ytmCollections = cols
        const defaults = new Set<string>(['LM', ...(res.syncedIds ?? [])])
        const nextSel = new Set<string>()
        for (const c of cols) {
          if (defaults.has(c.id)) nextSel.add(c.id)
        }
        ytmSelected = nextSel
      }
    } catch (e) {
      ytmError = String(e)
    } finally {
      ytmLoading = false
    }
  }

  function toggleYtmItem(id: string) {
    const next = new Set(ytmSelected)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    ytmSelected = next
  }

  function toggleSelectAllVisible() {
    const next = new Set(ytmSelected)
    if (ytmAllVisibleSelected) {
      for (const c of ytmFiltered) next.delete(c.id)
    } else {
      for (const c of ytmFiltered) next.add(c.id)
    }
    ytmSelected = next
  }

  async function confirmYtmImport() {
    const selected = ytmCollections.filter((c) => ytmSelected.has(c.id))
    if (selected.length === 0) return
    close()
    core.toast(`Importing ${selected.length} YouTube Music collection(s)…`)
    const err = await Go.ImportYtMusicCollections(selected.map((c) => main.YtMusicCollection.createFrom(c)))
    if (err) core.toast(err)
  }

  $effect(() => {
    const key = m ? m.type + (m.type === 'rename' ? m.id : '') : ''
    if (key !== lastKey) {
      lastKey = key
      name = m?.type === 'rename' ? m.name : ''
      if (m?.type === 'ytmImport') {
        loadYtmLibrary()
      }
      if (m?.type === 'genre') {
        genreCustom = ''
        genreSel = [...m.current]
      }
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
    <div class="modal" class:modal-wide={m.type === 'ytmImport' || m.type === 'genre'} onclick={(e) => e.stopPropagation()}>
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
              <Cover ids={p.coverTracks && p.coverTracks.length ? p.coverTracks : null} icon="library" class="thumb" size={128} />
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
      {:else if m.type === 'genre'}
        <h3>Genres</h3>
        <div class="hint" style="font-size:13px">
          Pick one or more genres for {m.label}. A sub-genre also counts as its parent (Thrash Metal is also Metal and Rock). Hand-set genres are never changed by auto-detection.
        </div>
        {#if genreSel.length}
          <div style="display:flex;flex-wrap:wrap;gap:6px;margin:6px 0">
            {#each genreSel as g}<button class="chip on" onclick={() => toggleGenre(g)}>{g} ✕</button>{/each}
          </div>
        {/if}
        <div class="list" style="max-height:340px;overflow:auto">
          {#each genreTops as top}
            <div style="margin:6px 0">
              <button class="chip" class:on={genreSel.includes(top.name)} onclick={() => toggleGenre(top.name)}><b>{top.name}</b></button>
              <div style="display:flex;flex-wrap:wrap;gap:6px;margin:6px 0 0 14px">
                {#each genreKids(top.name) as kid}
                  <button class="chip" class:on={genreSel.includes(kid.name)} onclick={() => toggleGenre(kid.name)}>{kid.name}</button>
                  {#each genreKids(kid.name) as sub}
                    <button class="chip" class:on={genreSel.includes(sub.name)} onclick={() => toggleGenre(sub.name)}>{sub.name}</button>
                  {/each}
                {/each}
              </div>
            </div>
          {/each}
        </div>
        <input class="pill-input" placeholder="Or add a custom genre and press Enter" bind:value={genreCustom} onkeydown={(e) => e.key === 'Enter' && addCustomGenre()} />
        <div class="row">
          <button class="btn ghost" onclick={() => saveGenres(true)}>Clear / auto-detect</button>
          <button class="btn ghost" onclick={close}>Cancel</button>
          <button class="btn primary" disabled={genreSel.length === 0} onclick={() => saveGenres()}>Save</button>
        </div>
      {:else if m.type === 'movePrompt'}
        <h3>Move your music to the new primary folder?</h3>
        <p class="hint" style="font-size:14px">
          <b>{m.newPrimary}</b> will be your primary folder. Do you want to move the songs from your other folders into it now?
          If you choose “No”, only new downloads go there and your existing files stay where they are (you can move them later from Settings).
        </p>
        <div class="row">
          <button class="btn ghost" onclick={close}>Cancel</button>
          <button class="btn ghost" onclick={() => movePrimary(false)}>No, only new downloads</button>
          <button class="btn primary" onclick={() => movePrimary(true)}>Yes, move my music</button>
        </div>
      {:else if m.type === 'ytmImport'}
        <div>
          <h3>Import from YouTube Music</h3>
          <div class="hint" style="margin-top:4px">{ytmSubtitle}</div>
        </div>
        <div class="ytm-filter-row">
          <div class="ytm-filter-pills">
            <button class="chip" class:on={ytmFilter === 'all'} onclick={() => (ytmFilter = 'all')}>All</button>
            <button class="chip" class:on={ytmFilter === 'playlists'} onclick={() => (ytmFilter = 'playlists')}>Playlists</button>
            <button class="chip" class:on={ytmFilter === 'albums'} onclick={() => (ytmFilter = 'albums')}>Albums</button>
          </div>
          <button class="link-btn" disabled={ytmLoading || ytmFiltered.length === 0} onclick={toggleSelectAllVisible}>
            {ytmAllVisibleSelected ? 'Deselect All' : 'Select All'}
          </button>
        </div>
        {#if ytmLoading}
          <div class="empty" style="padding:36px 16px">Fetching your YouTube Music playlists &amp; albums…</div>
        {:else if ytmError || ytmCollections.length === 0}
          <div class="empty" style="padding:32px 16px">
            <div class="big">No collections found</div>
            <div class="hint">{ytmError || 'Make sure your Google account is signed in to YouTube Music.'}</div>
            <div style="margin-top:14px;display:flex;justify-content:center;gap:10px">
              <button class="btn ghost" onclick={loadYtmLibrary}>Retry</button>
              <button
                class="btn ghost"
                onclick={async () => {
                  close()
                  await Go.LogoutYtMusic()
                  core.startYtMusicAction('import')
                }}>Switch account</button
              >
            </div>
          </div>
        {:else}
          <div class="list ytm-col-list">
            {#each ytmFiltered as col (col.id)}
              {@const checked = ytmSelected.has(col.id)}
              <button class="ytm-col-row" class:selected={checked} onclick={() => toggleYtmItem(col.id)}>
                <div class="sec-checkbox" class:checked>
                  {#if checked}<Icon name="check" size={14} />{/if}
                </div>
                <div class="ytm-col-thumb">
                  {#if col.thumbnailUrl}
                    <img src={col.thumbnailUrl} alt="" loading="lazy" decoding="async" />
                  {:else}
                    <Icon
                      name={col.type === 'LIKED_MUSIC' ? 'heart-fill' : col.type === 'ALBUM' ? 'album' : 'library'}
                      size={20}
                    />
                  {/if}
                </div>
                <div class="ytm-col-info">
                  <div class="ytm-col-title">{col.title}</div>
                  <div class="hint ytm-col-sub">
                    {col.subtitle}
                    {#if col.trackCountHint > 0 && !col.subtitle.toLowerCase().includes('song') && !col.subtitle.toLowerCase().includes('track')}
                      · {col.trackCountHint} songs
                    {/if}
                  </div>
                </div>
              </button>
            {/each}
          </div>
        {/if}
        <div class="row">
          <button class="btn ghost" onclick={close}>Cancel</button>
          <button
            class="btn primary"
            disabled={ytmLoading || ytmSelected.size === 0}
            onclick={confirmYtmImport}
          >
            {ytmSelected.size > 0 ? `Import Selected (${ytmSelected.size})` : 'Import Selected'}
          </button>
        </div>
      {/if}
    </div>
  </div>
{/if}
