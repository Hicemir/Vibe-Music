<script lang="ts">
  import Icon from './Icon.svelte'
  import { core, Go } from './store.svelte'
  import { EQ_BANDS, EQ_PRESETS } from './util'
  import type { Settings } from './types'

  const s = $derived(core.settings)
  let eqTimer: ReturnType<typeof setTimeout> | undefined

  async function save(patch: Partial<Settings>) {
    if (!core.settings) return
    const next = { ...core.settings, ...patch }
    core.settings = next
    const res = (await Go.SaveSettings(next as never)) as unknown as Settings
    core.settings = res
  }

  function setBand(i: number, v: number) {
    if (!s) return
    const eq = s.eq.slice()
    eq[i] = v
    core.settings = { ...s, eq }
    clearTimeout(eqTimer)
    eqTimer = setTimeout(() => save({ eq, eqEnabled: true }), 120)
  }

  async function addDir() {
    const d = await Go.PickFolder()
    if (d && s && !s.musicDirs.includes(d)) save({ musicDirs: [...s.musicDirs, d] })
  }
  async function pickDownloadDir() {
    const d = await Go.PickFolder()
    if (d) save({ downloadDir: d })
  }
  function removeDir(d: string) {
    if (s && s.musicDirs.length > 1) save({ musicDirs: s.musicDirs.filter((x) => x !== d) })
    else core.toast('You need at least one music folder')
  }
</script>

<div class="view-head">
  <div class="view-title">Settings <span style="font-size:14px;font-weight:700;color:var(--text-dim);margin-left:8px">v{core.version}</span></div>
</div>

{#if s}
  <div class="set-group">
    <h3>Library</h3>
    <div class="hint">All folders below are scanned recursively into one big pool of songs. New files are picked up automatically.</div>
    {#each s.musicDirs as d}
      <div class="dir-row">
        <Icon name="folder" size={18} /><span>{d}</span>
        <button class="icon-btn" onclick={() => removeDir(d)} title="Remove"><Icon name="close" size={18} /></button>
      </div>
    {/each}
    <div style="display:flex;gap:10px;align-items:center">
      <button class="btn" onclick={addDir}>+ Add folder</button>
      <button class="btn ghost" onclick={() => Go.Rescan()}>Rescan now</button>
      {#if core.scan.scanning}<span class="hint">Scanning… {core.scan.done}/{core.scan.total}</span>{:else}<span class="hint">{core.tracks.length} songs</span>{/if}
    </div>
  </div>

  <div class="set-group">
    <h3>Downloads (yt-dlp)</h3>
    <div class="set-row">
      <div class="lbl">Download folder<div class="d">{s.downloadDir}</div></div>
      <button class="btn" onclick={pickDownloadDir}>Change</button>
    </div>
    <div class="set-row">
      <div class="lbl">
        Audio format
        <div class="d">Opus and M4A are what YouTube serves natively, so they are saved without re-encoding (no quality loss). MP3 is converted from those.</div>
      </div>
      <select class="pill-input" value={s.downloadFormat} onchange={(e) => save({ downloadFormat: e.currentTarget.value })}>
        <option value="m4a">M4A (AAC)</option>
        <option value="opus">Opus</option>
        <option value="best">Best available</option>
        <option value="mp3">MP3</option>
      </select>
    </div>
    <div class="set-row">
      <div class="lbl">Update yt-dlp on every start<div class="d">Keeps downloads working when YouTube changes.</div></div>
      <button class="switch" class:on={s.autoUpdateYtdlp} onclick={() => save({ autoUpdateYtdlp: !s.autoUpdateYtdlp })} aria-label="toggle"></button>
    </div>
    <div class="set-row">
      <div class="lbl">
        Status
        <div class="d">
          {#if core.ytdlp.state === 'updating'}Updating…{:else if core.ytdlp.state === 'error'}<span style="color:var(--danger)">{core.ytdlp.message}</span>{:else if core.ytdlp.version}Version {core.ytdlp.version}{:else}Not installed{/if}
          {#if core.ytdlp.path}· {core.ytdlp.path}{/if}
        </div>
      </div>
      <button class="btn" disabled={core.ytdlp.state === 'updating'} onclick={() => Go.UpdateYtdlp()}>Update now</button>
    </div>
  </div>

  <div class="set-group">
    <h3>Playback</h3>
    <div class="set-row">
      <div class="lbl">Fade in / out between tracks<div class="d">{s.fadeSeconds > 0 ? `${s.fadeSeconds}s` : 'Off'}</div></div>
      <input type="range" min="0" max="12" step="1" value={s.fadeSeconds} onchange={(e) => save({ fadeSeconds: +e.currentTarget.value })} />
    </div>
    <div class="set-row">
      <div class="lbl">Normalize volume (ReplayGain)<div class="d">Uses ReplayGain tags when present.</div></div>
      <button class="switch" class:on={s.normalize} onclick={() => save({ normalize: !s.normalize })} aria-label="toggle"></button>
    </div>
    <div class="set-row">
      <div class="lbl">Keep playing in the tray when the window is closed<div class="d">Alt+F4 / the close button hides the window. Use the tray menu → Quit to exit.</div></div>
      <button class="switch" class:on={s.closeToTray} onclick={() => save({ closeToTray: !s.closeToTray })} aria-label="toggle"></button>
    </div>
  </div>

  <div class="set-group">
    <h3>Equalizer</h3>
    <div class="set-row">
      <div class="lbl">Enable equalizer</div>
      <select class="pill-input" onchange={(e) => { const p = EQ_PRESETS[e.currentTarget.value]; if (p) save({ eq: p, eqEnabled: true }) }}>
        <option value="">Presets…</option>
        {#each Object.keys(EQ_PRESETS) as p}<option value={p}>{p}</option>{/each}
      </select>
      <button class="switch" class:on={s.eqEnabled} onclick={() => save({ eqEnabled: !s.eqEnabled })} aria-label="toggle"></button>
    </div>
    <div class="eq" style:opacity={s.eqEnabled ? 1 : 0.45}>
      {#each EQ_BANDS as b, i}
        <div class="band">
          <small>{s.eq[i] > 0 ? '+' : ''}{s.eq[i]}</small>
          <input type="range" min="-12" max="12" step="1" value={s.eq[i]} oninput={(e) => setBand(i, +e.currentTarget.value)} />
          <small>{b}</small>
        </div>
      {/each}
    </div>
  </div>

  <div class="set-group">
    <h3>Keyboard shortcuts</h3>
    <div class="kbd">
      <b>Space</b><span>Play / pause</span>
      <b>Mouse Back / Forward</b><span>Navigate back / forward</span>
      <b>Alt + ← / →</b><span>Navigate back / forward</span>
      <b>← / →</b><span>Seek 5 seconds</span>
      <b>Shift + ← / →</b><span>Previous / next track</span>
      <b>↑ / ↓</b><span>Volume</span>
      <b>M</b><span>Mute / unmute</span>
      <b>S</b><span>Shuffle</span>
      <b>R</b><span>Repeat mode</span>
      <b>L</b><span>Like current track</span>
      <b>Q</b><span>Queue panel</span>
      <b>Ctrl + F</b><span>Search</span>
      <b>Media keys</b><span>Work globally via MPRIS (even with the window hidden)</span>
    </div>
  </div>
{/if}
