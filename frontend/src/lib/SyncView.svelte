<script lang="ts">
  import { onMount } from 'svelte'
  import Icon from './Icon.svelte'
  import { core, Go } from './store.svelte'

  let syncDeletions = $state(true)
  let manualIP = $state('')
  let showManual = $state(false)
  let manualBusy = $state(false)
  let editingName = $state(false)
  let nameInput = $state('')
  let startErr = $state('')

  const st = $derived(core.syncStatus)
  const self = $derived(core.syncSelf)
  const peers = $derived(core.syncPeers)
  const trusted = $derived(core.trustedSyncPeers)
  const isBusy = $derived(st.state === 'syncing' || st.state === 'pairing')

  onMount(() => {
    let active = true
    ;(async () => {
      try {
        const err = await Go.StartSyncDiscovery()
        if (err && active) startErr = err
        const state = await Go.GetSyncState()
        if (active && state) {
          core.syncSelf = state.self
          core.syncStatus = state.status
          core.syncPeers = state.peers ?? []
        }
        await core.loadTrustedSyncPeers()
      } catch (e) {
        if (active) startErr = String(e)
      }
    })()
    return () => {
      active = false
      Go.StopSyncDiscovery()
    }
  })

  function fmtBytes(b: number): string {
    if (!b || b <= 0) return '0 B'
    if (b < 1024) return `${b} B`
    if (b < 1024 * 1024) return `${(b / 1024).toFixed(0)} KB`
    if (b < 1024 * 1024 * 1024) return `${(b / (1024 * 1024)).toFixed(1)} MB`
    return `${(b / (1024 * 1024 * 1024)).toFixed(2)} GB`
  }

  function fmtWhen(unixSec: number): string {
    if (!unixSec) return 'Paired, never synced'
    const diff = Math.max(0, Math.floor(Date.now() / 1000 - unixSec))
    if (diff < 60) return 'Just now'
    if (diff < 3600) return `${Math.floor(diff / 60)}m ago`
    if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`
    return new Date(unixSec * 1000).toLocaleDateString()
  }

  async function saveDeviceName() {
    const clean = nameInput.trim()
    if (!clean) {
      editingName = false
      return
    }
    const next = await Go.SetSyncDeviceName(clean)
    if (next?.self) core.syncSelf = next.self
    editingName = false
  }

  async function connectManual() {
    const addr = manualIP.trim()
    if (!addr || manualBusy) return
    manualBusy = true
    try {
      const res = await Go.ConnectSyncManualIP(addr)
      if (res.error) {
        core.toast(res.error)
      } else if (res.peer) {
        core.toast(`Found ${res.peer.deviceName}`)
        showManual = false
        manualIP = ''
      }
    } catch (e) {
      core.toast(String(e))
    } finally {
      manualBusy = false
    }
  }

  async function startSync(deviceId: string) {
    const err = await Go.StartPeerSync(deviceId, syncDeletions)
    if (err) core.toast(err)
  }
</script>

<div class="view-head">
  <div>
    <div class="view-title">LAN Sync</div>
    <div class="hint">Sync songs, audio files, playlists, favorites, play counts &amp; genres directly over Wi‑Fi — zero re-downloading.</div>
  </div>
</div>

{#if startErr}
  <div class="ai-status-err" style="max-width:760px;margin-bottom:14px">{startErr}</div>
{/if}

<div class="sync-wrap">
  <!-- This Device Card -->
  <div class="sync-card">
    <div class="sync-self-head">
      <div class="sync-radar-badge" class:active={!isBusy}>
        <Icon name="sync" size={22} />
      </div>
      <div style="flex:1;min-width:0">
        {#if editingName}
          <div style="display:flex;align-items:center;gap:8px">
            <input
              class="pill-input"
              style="max-width:260px;height:32px"
              bind:value={nameInput}
              onkeydown={(e) => {
                if (e.key === 'Enter') saveDeviceName()
                else if (e.key === 'Escape') editingName = false
              }}
            />
            <button class="btn primary" style="padding:6px 12px" onclick={saveDeviceName}>Save</button>
            <button class="btn ghost" style="padding:6px 12px" onclick={() => (editingName = false)}>Cancel</button>
          </div>
        {:else}
          <div class="sync-self-title">
            <span>{self?.deviceName ?? 'VibeMusic PC'}</span>
            <button
              class="icon-btn"
              style="width:26px;height:26px"
              title="Rename this device"
              onclick={() => {
                nameInput = self?.deviceName ?? ''
                editingName = true
              }}
            >
              <Icon name="edit" size={14} />
            </button>
            <span class="badge">{self?.platform ?? 'pc'}</span>
          </div>
        {/if}
        <div class="hint" style="margin-top:2px">
          {core.tracks.length} songs · {core.playlists.length} playlists
          {#if self?.localIps?.length}
            · {self.localIps[0]}:{self.port}
          {/if}
        </div>
      </div>
      <button class="btn ghost" onclick={() => (showManual = !showManual)}>
        {showManual ? 'Hide IP' : 'Connect by IP'}
      </button>
    </div>

    {#if showManual}
      <div class="sync-manual-row">
        <input
          class="pill-input"
          placeholder="Phone IP address (e.g. 192.168.1.42 or 192.168.1.42:53318)"
          bind:value={manualIP}
          onkeydown={(e) => e.key === 'Enter' && connectManual()}
        />
        <button class="btn primary" disabled={manualBusy || !manualIP.trim()} onclick={connectManual}>
          {manualBusy ? 'Connecting…' : 'Find Device'}
        </button>
      </div>
    {/if}

    <hr class="set-divider" />

    <div class="set-row">
      <div class="lbl">
        Sync deletions
        <div class="d">Mirror deleted songs and duplicate cleanups across paired devices (new devices still merge safely on first sync).</div>
      </div>
      <button
        class="switch"
        class:on={syncDeletions}
        disabled={isBusy}
        onclick={() => (syncDeletions = !syncDeletions)}
        aria-label="Toggle sync deletions"
      ></button>
    </div>
  </div>

  <!-- Active Sync Status Card -->
  {#if st.state === 'pairing' || st.state === 'syncing' || st.state === 'done' || st.state === 'error'}
    <div class="sync-card sync-status-card" class:error={st.state === 'error'} class:done={st.state === 'done'}>
      <div class="sync-status-top">
        <div style="flex:1;min-width:0">
          <div style="display:flex;align-items:center;gap:8px">
            <span class="badge" class:running={isBusy} class:done={st.state === 'done'} class:error={st.state === 'error'}>
              {st.state}
            </span>
            <span style="font-weight:800">{st.peerName || 'Device'}</span>
          </div>
          <div class="hint" style="margin-top:4px">{st.phase || st.message || st.error}</div>
        </div>
        {#if isBusy}
          <button class="btn ghost" onclick={() => Go.CancelSync()}>Cancel</button>
        {/if}
      </div>

      {#if st.state === 'pairing' && st.pin}
        <div class="sync-pin-wrap" style="margin-top:4px">
          <div class="hint" style="font-size:12px">Confirm this 6-digit PIN on {st.peerName}</div>
          <div class="sync-pin-code">{st.pin.slice(0, 3)} {st.pin.slice(3)}</div>
        </div>
      {/if}

      {#if st.state === 'syncing'}
        <div class="bar"><i style="width:{Math.max(2, st.percent)}%"></i></div>
        <div class="sync-status-meta">
          <span class="sync-cur-file">{st.currentFile || 'Working…'}</span>
          <span>
            {#if st.totalBytes > 0}
              {fmtBytes(st.transferredBytes)} / {fmtBytes(st.totalBytes)}
            {/if}
            {#if st.filesTotal > 0}
              · {st.filesDone}/{st.filesTotal} files
            {/if}
            {#if st.speedBps > 0}
              · {fmtBytes(st.speedBps)}/s
            {/if}
          </span>
        </div>
      {:else if st.state === 'done'}
        <div class="ai-stats-row">
          <span class="ai-stat-pill accent">+{st.addedTracks} received</span>
          {#if st.updatedTracks > 0}<span class="ai-stat-pill">{st.updatedTracks} updated</span>{/if}
          {#if st.deletedTracks > 0}<span class="ai-stat-pill">{st.deletedTracks} deleted</span>{/if}
          <span class="ai-stat-pill">{st.syncedPlaylists} playlists</span>
        </div>
      {:else if st.state === 'error' && st.error}
        <div class="ai-status-err">{st.error}</div>
      {/if}
    </div>
  {/if}

  <!-- Nearby Devices -->
  <div class="sync-card">
    <div style="display:flex;align-items:center;justify-content:space-between">
      <h3>Nearby Devices</h3>
      <span class="hint">{peers.length} found on LAN</span>
    </div>

    {#if peers.length === 0}
      <div class="sync-empty">
        <div class="sync-radar-ring">
          <Icon name="devices" size={28} />
        </div>
        <div style="font-weight:700;margin-top:8px">Scanning local network…</div>
        <div class="hint" style="max-width:420px;margin-top:4px">
          Open <b>VibeMusic → Settings → LAN Sync</b> on your Android phone (or another PC) on the same Wi‑Fi or hotspot.
        </div>
      </div>
    {:else}
      <div class="sync-peer-grid">
        {#each peers as peer (peer.deviceId)}
          <div class="sync-peer-row">
            <div class="ytm-card-icon">
              <Icon name={peer.platform === 'android' ? 'phone' : 'devices'} size={22} />
            </div>
            <div style="flex:1;min-width:0">
              <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">
                <span style="font-weight:800;font-size:15px">{peer.deviceName || 'VibeMusic Device'}</span>
                <span class="badge">{peer.platform}</span>
                {#if peer.trusted}
                  <span class="sync-trusted-pill"><Icon name="shield" size={13} /> Trusted</span>
                {/if}
              </div>
              <div class="hint" style="font-size:12.5px;margin-top:2px">
                {peer.trackCount} songs · {peer.playlistCount} playlists · {peer.ip}:{peer.port}
              </div>
            </div>
            <button
              class="btn primary"
              disabled={isBusy}
              onclick={() => startSync(peer.deviceId)}
            >
              {isBusy && st.peerId === peer.deviceId ? 'Syncing…' : 'Sync Now'}
            </button>
          </div>
        {/each}
      </div>
    {/if}
  </div>

  <!-- Trusted Devices -->
  {#if trusted.length > 0}
    <div class="sync-card">
      <div style="display:flex;align-items:center;justify-content:space-between">
        <h3>Trusted Devices</h3>
        <span class="hint">1-tap auto-authenticated via pinned cryptographic key</span>
      </div>
      <div class="sync-peer-grid">
        {#each trusted as tp (tp.deviceId)}
          <div class="sync-peer-row">
            <div class="ytm-card-icon" style="width:38px;height:38px">
              <Icon name={tp.platform === 'android' ? 'phone' : 'devices'} size={18} />
            </div>
            <div style="flex:1;min-width:0">
              <div style="font-weight:700">{tp.deviceName || tp.deviceId.slice(0, 8)}</div>
              <div class="hint" style="font-size:12px">{tp.platform} · Last synced: {fmtWhen(tp.lastSyncAt)}</div>
            </div>
            <button
              class="btn ghost"
              disabled={isBusy}
              onclick={async () => {
                await Go.RemoveTrustedSyncPeer(tp.deviceId)
                await core.loadTrustedSyncPeers()
              }}
            >
              Forget
            </button>
          </div>
        {/each}
      </div>
    </div>
  {/if}
</div>
