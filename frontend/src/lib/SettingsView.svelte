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

  const HOME_SECTION_META: { id: string; title: string; desc: string }[] = [
    { id: 'recentPlaylists', title: 'Recent playlists', desc: 'Compact top grid of your 4 most recently played playlists.' },
    { id: 'topTracks', title: 'Your top tracks', desc: 'Horizontal shelf of your most played tracks.' },
    { id: 'quickPicks', title: 'Quick picks (Speed Dial)', desc: 'Horizontal strip of smart picks that start a local radio mix when clicked.' },
    { id: 'discover', title: 'Online Discover (You might like)', desc: 'Opt-in YouTube Music recommendations for songs not in your library yet.' },
    { id: 'recentlyAdded', title: 'Recently added', desc: 'Horizontal shelf of the newest songs added to your library.' },
    { id: 'allMusic', title: 'All your music', desc: 'Full sortable table of your entire local music pool at the bottom of Home.' },
  ]

  function isSectionVisible(id: string): boolean {
    const hidden = s?.homeHidden ?? ['discover']
    return !hidden.includes(id)
  }

  function toggleHomeSection(id: string) {
    if (!s) return
    const cur = new Set(s.homeHidden ?? ['discover'])
    if (cur.has(id)) cur.delete(id)
    else cur.add(id)
    save({ homeHidden: [...cur] })
  }

  function resetHomeLayout() {
    save({
      homeOrder: ['recentPlaylists', 'topTracks', 'quickPicks', 'discover', 'recentlyAdded', 'allMusic'],
      homeHidden: ['discover'],
      quickPicksRows: '1',
    })
    core.toast('Home layout reset to default')
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
    <div style="display:flex;align-items:center;justify-content:space-between">
      <h3>Home page sections</h3>
      <button class="btn ghost" onclick={resetHomeLayout}>Reset default</button>
    </div>
    <div class="hint">Choose which sections appear on your Home screen. Use the pencil (✎) icon on the Home screen to reorder them.</div>
    {#each HOME_SECTION_META as sec}
      <div class="set-row">
        <div class="lbl">{sec.title}<div class="d">{sec.desc}</div></div>
        <button class="switch" class:on={isSectionVisible(sec.id)} onclick={() => toggleHomeSection(sec.id)} aria-label="toggle"></button>
      </div>
    {/each}
    <div class="set-row">
      <div class="lbl">
        Quick picks row height
        <div class="d"><b>1x</b> is a single row. <b>Auto</b> expands to 2x or 3x rows automatically when you hide Top Tracks or Recently Added.</div>
      </div>
      <select class="pill-input" value={s.quickPicksRows || '1'} onchange={(e) => save({ quickPicksRows: e.currentTarget.value })}>
        <option value="1">1x (Single row)</option>
        <option value="auto">Auto (Expand when shelves hidden)</option>
        <option value="2">2x (2 rows)</option>
        <option value="3">3x (3 rows)</option>
      </select>
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
      <div class="lbl">
        YouTube Music browser session (optional)
        <div class="d">Lets yt-dlp read your browser's YouTube Music cookies to import private playlists / Liked Music and personalize Online Discover.</div>
      </div>
      <select class="pill-input" value={s.cookieBrowser || ''} onchange={(e) => save({ cookieBrowser: e.currentTarget.value })}>
        <option value="">None (Public only)</option>
        <option value="firefox">Firefox</option>
        <option value="brave">Brave</option>
        <option value="chrome">Chrome</option>
        <option value="chromium">Chromium</option>
        <option value="vivaldi">Vivaldi</option>
        <option value="edge">Edge</option>
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
