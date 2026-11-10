<script lang="ts">
  import Icon from './Icon.svelte'
  import { core, Go } from './store.svelte'
  import { ClipboardGetText } from '../../wailsjs/runtime/runtime'
  import type { DownloadJob } from './types'

  let mode = $state<'track' | 'playlist'>('track')
  let url = $state('')
  let busy = $state(false)

  const fmtLabel: Record<string, string> = {
    m4a: 'M4A (AAC) – best compatibility',
    opus: 'Opus – best quality per size',
    best: 'Best available (no re-encode)',
    mp3: 'MP3 – converted',
  }

  async function submit() {
    if (!url.trim()) return
    busy = true
    const err = await Go.StartDownload(url.trim(), mode)
    busy = false
    if (err) core.toast(err)
    else {
      url = ''
      core.toast(mode === 'track' ? 'Track queued' : 'Playlist queued')
    }
  }

  async function paste() {
    try {
      const t = await ClipboardGetText()
      if (t) url = t.trim()
    } catch {
      try {
        url = (await navigator.clipboard.readText()).trim()
      } catch {
        core.toast('Could not read the clipboard – paste with Ctrl+V')
      }
    }
  }

  function pct(j: DownloadJob) {
    if (j.status === 'done') return 100
    if (j.mode === 'playlist' && j.total > 0) {
      return Math.min(100, ((Math.max(j.index, 1) - 1 + j.percent / 100) / j.total) * 100)
    }
    return j.percent
  }

  const jobs = $derived(core.downloads.slice().reverse())
  const finished = $derived(core.downloads.some((j) => j.status !== 'queued' && j.status !== 'running'))
</script>

<div class="view-head"><div class="view-title">Download music</div></div>

<div class="dl-form">
  <div class="seg" role="tablist">
    <button class:on={mode === 'track'} onclick={() => (mode = 'track')}>Track</button>
    <button class:on={mode === 'playlist'} onclick={() => (mode = 'playlist')}>Playlist</button>
  </div>
  {#if core.settings?.cookieBrowser}
    <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
      <button
        class="btn ghost"
        disabled={busy}
        onclick={async () => {
          busy = true
          const err = await Go.StartDownload('https://music.youtube.com/playlist?list=LM', 'playlist')
          busy = false
          if (err) core.toast(err)
          else core.toast('Syncing YouTube Liked Music (will also mark tracks as ♥ Liked)')
        }}
      >
        <Icon name="heart-fill" size={16} /> Import My YT Liked Music ({core.settings.cookieBrowser})
      </button>
      <span class="hint">Connected to {core.settings.cookieBrowser} session — private playlists & Liked Music enabled.</span>
    </div>
  {/if}
  <div class="dl-row">
    <input
      class="pill-input"
      placeholder={mode === 'track' ? 'Paste a track URL (YouTube, YouTube Music, …)' : 'Paste a playlist URL'}
      bind:value={url}
      onkeydown={(e) => e.key === 'Enter' && submit()}
    />
    <button class="btn ghost" onclick={paste} title="Paste from clipboard"><Icon name="clipboard" size={18} /></button>
    <button class="btn primary" disabled={busy || !url.trim()} onclick={submit}>Download</button>
  </div>
  <div class="hint">
    {#if mode === 'track'}
      Downloads one song. If the link is part of a playlist, only that song is fetched.
    {:else}
      Downloads every song in the playlist and creates a matching playlist in the app.
    {/if}
    · Format: <b>{fmtLabel[core.settings?.downloadFormat ?? 'm4a']}</b>
    <button class="link-btn" onclick={() => core.go({ name: 'settings' })}>change</button>
  </div>
  <div class="hint">
    yt-dlp:
    {#if core.ytdlp.state === 'updating'}
      <span style="color:var(--accent)">updating…</span>
    {:else if core.ytdlp.state === 'error'}
      <span style="color:var(--danger)">{core.ytdlp.message}</span>
    {:else if core.ytdlp.version}
      version {core.ytdlp.version} {core.ytdlp.state === 'ok' ? '(up to date)' : ''}
    {:else}
      not installed yet
    {/if}
  </div>
</div>

<div class="section">
  <div class="section-head">
    <h2>Downloads</h2>
    {#if finished}<button class="link-btn" onclick={() => Go.ClearDownloads()}>Clear finished</button>{/if}
  </div>
  {#if jobs.length === 0}
    <div class="hint">Nothing downloaded in this session yet.</div>
  {/if}
  <div style="display:flex;flex-direction:column;gap:10px;max-width:820px">
    {#each jobs as j (j.id)}
      <div class="job">
        <div class="top">
          <span class="badge {j.status}">{j.status}</span>
          <div class="name">{j.title}</div>
          {#if j.mode === 'playlist' && j.total > 0}
            <span class="hint">{Math.min(j.done, j.total)}/{j.total}</span>
          {/if}
          {#if j.status === 'running'}<span class="hint">{j.speed}</span>{/if}
          {#if j.status === 'running' || j.status === 'queued'}
            <button class="icon-btn" onclick={() => Go.CancelDownload(j.id)} title="Cancel"><Icon name="close" size={18} /></button>
          {/if}
        </div>
        {#if j.status === 'running' || j.status === 'queued'}
          <div class="bar"><i style="width:{pct(j)}%"></i></div>
        {/if}
        {#if j.status === 'done' && j.playlist}
          <div class="hint">
            Added to playlist <button class="link-btn" onclick={() => core.go({ name: 'playlists' })}>{j.playlist}</button>
            {#if j.error}· {j.error}{/if}
          </div>
        {:else if j.error}
          <div class="hint" style="color:var(--danger)">{j.error}</div>
        {/if}
      </div>
    {/each}
  </div>
</div>
