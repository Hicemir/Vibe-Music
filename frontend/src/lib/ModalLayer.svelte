<script lang="ts">
  import Cover from './Cover.svelte'
  import Icon from './Icon.svelte'
  import { core, Go } from './store.svelte'
  import { main } from '../../wailsjs/go/models'
  import type { YtMusicCollection } from './types'

  const m = $derived(core.modal)
  let dlg = $state<HTMLDialogElement | null>(null)
  let name = $state('')
  let lastKey = ''

  let plQuery = $state('')
  let creatingInline = $state(false)
  let newPlName = $state('')
  let pendingPl = $state<Set<number>>(new Set())
  let syncTrustChecked = $state(true)
  let syncPairHandled = false

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
    const key = m
      ? m.type +
        (m.type === 'rename'
          ? `:${m.id}`
          : m.type === 'addToPlaylist'
            ? `:${m.trackIds.join(',')}`
            : m.type === 'syncPair'
              ? `:${m.prompt.pairId}`
              : '')
      : ''
    if (key !== lastKey) {
      lastKey = key
      name = m?.type === 'rename' ? m.name : ''
      if (m?.type === 'syncPair') {
        syncTrustChecked = m.prompt.trustedDefault ?? true
        syncPairHandled = false
      }
      if (m?.type === 'addToPlaylist') {
        plQuery = ''
        creatingInline = false
        newPlName = ''
        pendingPl = new Set()
        if (Object.keys(core.playlistTrackMap).length === 0 && core.playlists.length > 0) {
          core.loadPlaylists()
        }
      }
      if (m?.type === 'ytmImport') {
        loadYtmLibrary()
      }
      if (m?.type === 'genre') {
        genreCustom = ''
        genreSel = [...m.current]
      }
    }
  })

  $effect(() => {
    const el = dlg
    if (!el) return
    if (m && !el.open) {
      el.showModal()
    }
    if ('closedBy' in HTMLDialogElement.prototype) return
    const outside = (e: PointerEvent) => {
      const r = el.getBoundingClientRect()
      return e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom
    }
    let pressedOutside = false
    const onDown = (e: PointerEvent) => {
      pressedOutside = outside(e)
    }
    const onUp = (e: PointerEvent) => {
      if (pressedOutside && outside(e)) {
        el.close()
      }
      pressedOutside = false
    }
    el.addEventListener('pointerdown', onDown)
    el.addEventListener('pointerup', onUp)
    return () => {
      el.removeEventListener('pointerdown', onDown)
      el.removeEventListener('pointerup', onUp)
    }
  })

  function onDialogClose() {
    if (core.modal?.type === 'syncPair' && !syncPairHandled) {
      syncPairHandled = true
      Go.RespondSyncPair(core.modal.prompt.pairId, false, false)
    }
    if (dlg) dlg.returnValue = ''
    if (core.modal) core.modal = null
  }

  function close() {
    if (dlg?.open) dlg.close()
    else core.modal = null
  }

  const singleTrack = $derived(
    m?.type === 'addToPlaylist' && m.trackIds.length === 1 ? (core.byId.get(m.trackIds[0]) ?? null) : null,
  )

  const filteredPlaylists = $derived.by(() => {
    const q = plQuery.trim().toLowerCase()
    if (!q) return core.playlists
    return core.playlists.filter((p) => p.name.toLowerCase().includes(q))
  })

  function plMembership(plId: number): { count: number; state: 'none' | 'some' | 'all' } {
    if (m?.type !== 'addToPlaylist' || m.trackIds.length === 0) return { count: 0, state: 'none' }
    const set = new Set(core.playlistTrackMap[plId] ?? [])
    let count = 0
    for (const id of m.trackIds) {
      if (set.has(id)) count++
    }
    if (count === 0) return { count: 0, state: 'none' }
    if (count === m.trackIds.length) return { count, state: 'all' }
    return { count, state: 'some' }
  }

  const inPlaylistCount = $derived.by(() => {
    if (m?.type !== 'addToPlaylist' || m.trackIds.length === 0) return 0
    let n = 0
    for (const p of core.playlists) {
      if (plMembership(p.id).state === 'all') n++
    }
    return n
  })

  function setIndeterminate(node: HTMLInputElement, value: boolean) {
    node.indeterminate = value
    return {
      update(next: boolean) {
        node.indeterminate = next
      },
    }
  }

  function focusOnMount(node: HTMLElement) {
    node.focus()
  }

  async function togglePlaylist(plId: number) {
    if (m?.type !== 'addToPlaylist' || pendingPl.has(plId)) return
    const { state } = plMembership(plId)
    const shouldAdd = state !== 'all'
    const nextPending = new Set(pendingPl)
    nextPending.add(plId)
    pendingPl = nextPending
    try {
      await core.setPlaylistTracksOptimistic(plId, m.trackIds, shouldAdd)
    } catch {
      core.toast('Could not update playlist')
    } finally {
      const donePending = new Set(pendingPl)
      donePending.delete(plId)
      pendingPl = donePending
    }
  }

  async function createInlinePlaylist() {
    if (m?.type !== 'addToPlaylist') return
    const n = newPlName.trim()
    if (!n) return
    const id = await Go.CreatePlaylist(n, m.trackIds)
    await core.loadPlaylists()
    creatingInline = false
    newPlName = ''
    if (id) core.toast(`Created “${n}”`)
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

{#if m}
  <dialog
    bind:this={dlg}
    class="modal"
    class:modal-wide={m.type === 'ytmImport' || m.type === 'genre'}
    class:modal-pl={m.type === 'addToPlaylist'}
    aria-labelledby="modal-title"
    {...({ closedby: 'any' } as any)}
    onclose={onDialogClose}
  >
    {#if m.type === 'newPlaylist'}
      <h3 id="modal-title">New playlist</h3>
      <!-- svelte-ignore a11y_autofocus -->
      <input class="pill-input" placeholder="Playlist name" bind:value={name} autofocus onkeydown={(e) => e.key === 'Enter' && createPlaylist()} />
      <div class="row">
        <button type="button" class="btn ghost" onclick={close}>Cancel</button>
        <button type="button" class="btn primary" disabled={!name.trim()} onclick={createPlaylist}>Create</button>
      </div>
    {:else if m.type === 'rename'}
      <h3 id="modal-title">Rename playlist</h3>
      <!-- svelte-ignore a11y_autofocus -->
      <input class="pill-input" bind:value={name} autofocus onkeydown={(e) => e.key === 'Enter' && rename()} />
      <div class="row">
        <button type="button" class="btn ghost" onclick={close}>Cancel</button>
        <button type="button" class="btn primary" disabled={!name.trim()} onclick={rename}>Save</button>
      </div>
    {:else if m.type === 'addToPlaylist'}
      <div class="modal-head">
        <h3 id="modal-title">Add to playlist</h3>
        <button type="button" class="icon-btn modal-close" onclick={close} aria-label="Close">
          <Icon name="close" size={18} />
        </button>
      </div>

      {#if singleTrack}
        <div class="pl-picker-track">
          <div class="pl-picker-track-art">
            <Cover id={singleTrack.hasCover ? singleTrack.id : ''} size={128} />
          </div>
          <div class="pl-picker-track-meta">
            <div class="pl-picker-track-title">{singleTrack.title}</div>
            <div class="pl-picker-track-artist">{singleTrack.artist}</div>
          </div>
          <span class="pl-picker-badge" class:active={inPlaylistCount > 0}>
            {inPlaylistCount > 0 ? `In ${inPlaylistCount}` : 'Not saved'}
          </span>
        </div>
      {:else}
        <div class="hint">{m.trackIds.length} songs selected</div>
      {/if}

      {#if core.playlists.length >= 6}
        <div class="pl-picker-search">
          <Icon name="search" size={16} />
          <input
            type="search"
            placeholder="Find a playlist…"
            aria-label="Find a playlist"
            bind:value={plQuery}
            onkeydown={(e) => {
              if (e.key === 'Escape' && plQuery) {
                e.preventDefault()
                e.stopPropagation()
                plQuery = ''
              }
            }}
          />
          {#if plQuery}
            <button type="button" class="pl-search-clear" onclick={() => (plQuery = '')} aria-label="Clear search">
              <Icon name="close" size={14} />
            </button>
          {/if}
        </div>
      {/if}

      <div class="pl-picker-list">
        {#if core.playlists.length === 0}
          <div class="pl-picker-empty">
            No playlists yet. Create one below to add {m.trackIds.length === 1 ? 'this song' : 'these songs'}.
          </div>
        {:else if filteredPlaylists.length === 0}
          <div class="pl-picker-empty">No playlists match “{plQuery.trim()}”.</div>
        {:else}
          {#each filteredPlaylists as p (p.id)}
            {@const mem = plMembership(p.id)}
            <label class="pl-picker-row" class:checked={mem.state === 'all'} class:partial={mem.state === 'some'}>
              <div class="pl-picker-thumb">
                <Cover ids={p.coverTracks && p.coverTracks.length ? p.coverTracks : null} icon="library" size={128} />
              </div>
              <div class="pl-picker-meta">
                <div class="pl-picker-name">{p.name}</div>
                <div class="pl-picker-sub">
                  {p.trackCount} song{p.trackCount === 1 ? '' : 's'}{#if mem.state === 'some'} · {mem.count}/{m.trackIds.length} added{/if}
                </div>
              </div>
              <input
                type="checkbox"
                class="pl-circle-check"
                checked={mem.state === 'all'}
                disabled={pendingPl.has(p.id)}
                use:setIndeterminate={mem.state === 'some'}
                onchange={() => togglePlaylist(p.id)}
                aria-label={p.name}
              />
            </label>
          {/each}
        {/if}
      </div>

      {#if creatingInline}
        <div class="pl-picker-inline">
          <input
            class="pill-input"
            placeholder="Playlist name…"
            bind:value={newPlName}
            use:focusOnMount
            onkeydown={(e) => {
              if (e.key === 'Enter') {
                createInlinePlaylist()
              } else if (e.key === 'Escape') {
                e.preventDefault()
                e.stopPropagation()
                creatingInline = false
              }
            }}
          />
          <button type="button" class="btn primary" disabled={!newPlName.trim()} onclick={createInlinePlaylist}>Create</button>
          <button type="button" class="btn ghost" onclick={() => (creatingInline = false)}>Cancel</button>
        </div>
      {/if}

      <div class="pl-picker-foot">
        <button
          type="button"
          class="btn ghost pl-new-btn"
          onclick={() => {
            creatingInline = true
            newPlName = ''
          }}
        >
          <Icon name="plus" size={16} />
          <span>New playlist</span>
        </button>
        <button type="button" class="btn primary" onclick={close}>Done</button>
      </div>
    {:else if m.type === 'confirm'}
      <h3 id="modal-title">{m.title}</h3>
      <p class="hint" style="font-size:14px">{m.message}</p>
      <div class="row">
        <button type="button" class="btn ghost" onclick={close}>Cancel</button>
        <button
          type="button"
          class="btn {m.danger ? 'danger' : 'primary'}"
          onclick={() => {
            const f = m.onConfirm
            close()
            f()
          }}>{m.confirmLabel}</button
        >
      </div>
    {:else if m.type === 'sleep'}
      <h3 id="modal-title">Sleep timer</h3>
      <div class="list">
        {#each sleepOptions as o}
          <button
            type="button"
            onclick={() => {
              Go.SetSleepTimer(o.v)
              close()
              core.toast(`Music will pause after ${o.label.toLowerCase()}`)
            }}>{o.label}</button
          >
        {/each}
        {#if core.player.sleepAt || core.player.sleepEnd}
          <button
            type="button"
            style="color:var(--danger)"
            onclick={() => {
              Go.SetSleepTimer(0)
              close()
            }}>Turn off timer</button
          >
        {/if}
      </div>
      <div class="row"><button type="button" class="btn ghost" onclick={close}>Close</button></div>
    {:else if m.type === 'genre'}
      <h3 id="modal-title">Genres</h3>
      <div class="hint" style="font-size:13px">
        Pick one or more genres for {m.label}. A sub-genre also counts as its parent (Thrash Metal is also Metal and Rock). Hand-set genres are never changed by auto-detection.
      </div>
      {#if genreSel.length}
        <div style="display:flex;flex-wrap:wrap;gap:6px;margin:6px 0">
          {#each genreSel as g}<button type="button" class="chip on" onclick={() => toggleGenre(g)}>{g} ✕</button>{/each}
        </div>
      {/if}
      <div class="list" style="max-height:340px;overflow:auto">
        {#each genreTops as top}
          <div style="margin:6px 0">
            <button type="button" class="chip" class:on={genreSel.includes(top.name)} onclick={() => toggleGenre(top.name)}><b>{top.name}</b></button>
            <div style="display:flex;flex-wrap:wrap;gap:6px;margin:6px 0 0 14px">
              {#each genreKids(top.name) as kid}
                <button type="button" class="chip" class:on={genreSel.includes(kid.name)} onclick={() => toggleGenre(kid.name)}>{kid.name}</button>
                {#each genreKids(kid.name) as sub}
                  <button type="button" class="chip" class:on={genreSel.includes(sub.name)} onclick={() => toggleGenre(sub.name)}>{sub.name}</button>
                {/each}
              {/each}
            </div>
          </div>
        {/each}
      </div>
      <input class="pill-input" placeholder="Or add a custom genre and press Enter" bind:value={genreCustom} onkeydown={(e) => e.key === 'Enter' && addCustomGenre()} />
      <div class="row">
        <button type="button" class="btn ghost" onclick={() => saveGenres(true)}>Clear / auto-detect</button>
        <button type="button" class="btn ghost" onclick={close}>Cancel</button>
        <button type="button" class="btn primary" disabled={genreSel.length === 0} onclick={() => saveGenres()}>Save</button>
      </div>
    {:else if m.type === 'movePrompt'}
      <h3 id="modal-title">Move your music to the new primary folder?</h3>
      <p class="hint" style="font-size:14px">
        <b>{m.newPrimary}</b> will be your primary folder. Do you want to move the songs from your other folders into it now?
        If you choose “No”, only new downloads go there and your existing files stay where they are (you can move them later from Settings).
      </p>
      <div class="row">
        <button type="button" class="btn ghost" onclick={close}>Cancel</button>
        <button type="button" class="btn ghost" onclick={() => movePrimary(false)}>No, only new downloads</button>
        <button type="button" class="btn primary" onclick={() => movePrimary(true)}>Yes, move my music</button>
      </div>
    {:else if m.type === 'ytmImport'}
      <div>
        <h3 id="modal-title">Import from YouTube Music</h3>
        <div class="hint" style="margin-top:4px">{ytmSubtitle}</div>
      </div>
      <div class="ytm-filter-row">
        <div class="ytm-filter-pills">
          <button type="button" class="chip" class:on={ytmFilter === 'all'} onclick={() => (ytmFilter = 'all')}>All</button>
          <button type="button" class="chip" class:on={ytmFilter === 'playlists'} onclick={() => (ytmFilter = 'playlists')}>Playlists</button>
          <button type="button" class="chip" class:on={ytmFilter === 'albums'} onclick={() => (ytmFilter = 'albums')}>Albums</button>
        </div>
        <button type="button" class="link-btn" disabled={ytmLoading || ytmFiltered.length === 0} onclick={toggleSelectAllVisible}>
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
            <button type="button" class="btn ghost" onclick={loadYtmLibrary}>Retry</button>
            <button
              type="button"
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
            <button type="button" class="ytm-col-row" class:selected={checked} onclick={() => toggleYtmItem(col.id)}>
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
        <button type="button" class="btn ghost" onclick={close}>Cancel</button>
        <button
          type="button"
          class="btn primary"
          disabled={ytmLoading || ytmSelected.size === 0}
          onclick={confirmYtmImport}
        >
          {ytmSelected.size > 0 ? `Import Selected (${ytmSelected.size})` : 'Import Selected'}
        </button>
      </div>
    {:else if m.type === 'syncPair'}
      <div class="modal-head">
        <h3 id="modal-title">LAN Sync Request</h3>
        <button type="button" class="icon-btn modal-close" onclick={close} aria-label="Close">
          <Icon name="close" size={18} />
        </button>
      </div>
      <div class="sync-pair-box">
        <div class="sync-pair-peer">
          <div class="ytm-card-icon"><Icon name={m.prompt.platform === 'android' ? 'phone' : 'devices'} size={22} /></div>
          <div>
            <div style="font-weight:800;font-size:15px">{m.prompt.deviceName}</div>
            <div class="hint" style="font-size:12.5px">
              {m.prompt.platform.toUpperCase()}
              {#if m.prompt.trackCount > 0}· {m.prompt.trackCount} songs{/if}
            </div>
          </div>
        </div>
        <div class="sync-pin-wrap">
          <div class="hint" style="font-size:12px">Verify this 6-digit PIN matches the other device</div>
          <div class="sync-pin-code">{m.prompt.pin.slice(0, 3)} {m.prompt.pin.slice(3)}</div>
        </div>
        <label class="ai-check-inline" style="font-size:13px;color:var(--text)">
          <input type="checkbox" bind:checked={syncTrustChecked} />
          <span>Trust this device automatically for 1-tap syncs next time</span>
        </label>
      </div>
      <div class="row">
        <button
          type="button"
          class="btn ghost"
          onclick={() => {
            syncPairHandled = true
            const pid = m.prompt.pairId
            close()
            Go.RespondSyncPair(pid, false, false)
          }}>Decline</button
        >
        <button
          type="button"
          class="btn primary"
          onclick={() => {
            syncPairHandled = true
            const pid = m.prompt.pairId
            const trust = syncTrustChecked
            close()
            Go.RespondSyncPair(pid, true, trust)
          }}>Accept &amp; Sync</button
        >
      </div>
    {/if}
  </dialog>
{/if}
