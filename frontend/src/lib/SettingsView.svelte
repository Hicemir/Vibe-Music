<script lang="ts">
  import Icon from './Icon.svelte'
  import GenreSettings from './GenreSettings.svelte'
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

  // The first folder is the primary one: new downloads are stored there.
  async function applyDirs(dirs: string[], askMove: boolean) {
    if (!s) return
    const oldPrimary = s.musicDirs[0]
    const newPrimary = dirs[0]
    if (askMove && newPrimary !== oldPrimary && s.musicDirs.length > 0) {
      const others = dirs.slice(1)
      if (others.length > 0) {
        core.modal = { type: 'movePrompt', newPrimary, others, nextDirs: dirs }
        return
      }
    }
    save({ musicDirs: dirs, downloadDir: newPrimary })
  }
  async function addDir() {
    const d = await Go.PickFolder()
    if (d && s && !s.musicDirs.includes(d)) save({ musicDirs: [...s.musicDirs, d] })
  }
  function makePrimary(d: string) {
    if (!s) return
    applyDirs([d, ...s.musicDirs.filter((x) => x !== d)], true)
  }
  function moveDir(i: number, delta: number) {
    if (!s) return
    const j = i + delta
    if (j < 0 || j >= s.musicDirs.length) return
    const dirs = s.musicDirs.slice()
    ;[dirs[i], dirs[j]] = [dirs[j], dirs[i]]
    applyDirs(dirs, i === 0 || j === 0)
  }
  function removeDir(d: string) {
    if (s && s.musicDirs.length > 1) save({ musicDirs: s.musicDirs.filter((x) => x !== d), downloadDir: s.musicDirs.filter((x) => x !== d)[0] })
    else core.toast('You need at least one music folder')
  }
  async function moveInto(dest: string) {
    if (!s) return
    const sources = s.musicDirs.filter((x) => x !== dest)
    if (sources.length === 0) return core.toast('Add another folder first')
    const [n, size] = (await Go.CountMovable(sources, dest)) as number[]
    if (n === 0) return core.toast('No songs to move')
    core.modal = {
      type: 'confirm',
      title: `Move ${n} song(s) to this folder?`,
      message: `${(size / 1048576).toFixed(0)} MB from your other folders will be moved into ${dest}. Likes, play counts and playlists are kept.`,
      confirmLabel: 'Move music',
      onConfirm: () => Go.MoveMusic(sources, dest),
    }
  }

  const HOME_SECTION_META: { id: string; title: string; desc: string }[] = [
    { id: 'recentPlaylists', title: 'Recent playlists', desc: 'Compact top grid of your 4 most recently played playlists.' },
    { id: 'topTracks', title: 'Your top tracks', desc: 'Horizontal shelf of your most played tracks.' },
    { id: 'quickPicks', title: 'Quick picks (Speed Dial)', desc: 'Horizontal strip of smart mixes you can play directly or open like playlists.' },
    { id: 'artistRadios', title: 'Artist radios', desc: 'Artist radio mixes combining your local songs with online discoveries.' },
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
      homeOrder: ['recentPlaylists', 'topTracks', 'quickPicks', 'artistRadios', 'discover', 'recentlyAdded', 'allMusic'],
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
    <div class="hint">All folders below are scanned recursively into one big pool of songs. The folder at the top is the <b>primary</b> folder: new downloads are stored there.</div>
    {#each s.musicDirs as d, i (d)}
      <div class="dir-row">
        <Icon name="folder" size={18} /><span>{d}</span>
        {#if i === 0}<span class="hint" style="color:var(--accent);font-weight:700;flex:0 0 auto">Primary</span>{/if}
        {#if i > 0}<button class="btn ghost" onclick={() => makePrimary(d)} title="Make primary">Make primary</button>{/if}
        {#if s.musicDirs.length > 1}
          <button class="btn ghost" onclick={() => moveInto(d)} title="Move music from the other folders into this one">Move music here</button>
          <button class="icon-btn" disabled={i === 0} onclick={() => moveDir(i, -1)} title="Move up">↑</button>
          <button class="icon-btn" disabled={i === s.musicDirs.length - 1} onclick={() => moveDir(i, 1)} title="Move down">↓</button>
        {/if}
        <button class="icon-btn" onclick={() => removeDir(d)} title="Remove"><Icon name="close" size={18} /></button>
      </div>
    {/each}
    <div style="display:flex;gap:10px;align-items:center">
      <button class="btn" onclick={addDir}>+ Add folder</button>
      <button class="btn ghost" onclick={() => Go.Rescan()}>Rescan now</button>
      {#if core.scan.scanning}<span class="hint">Scanning… {core.scan.done}/{core.scan.total}</span>{:else}<span class="hint">{core.tracks.length} songs</span>{/if}
    </div>
    {#if core.moveStatus.running}
      <div class="hint">Moving music… {core.moveStatus.done}/{core.moveStatus.total}</div>
    {/if}
  </div>

  <GenreSettings />

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
        When clicking “Open in browser” on Discover songs
        <div class="d">Choose whether to switch focus to your browser window immediately or open the tab in the background while staying in Vibe Music.</div>
      </div>
      <select
        class="pill-input"
        value={s.browserBackground ? 'background' : 'foreground'}
        onchange={(e) => save({ browserBackground: e.currentTarget.value === 'background' })}
      >
        <option value="foreground">Bring browser to front (Default)</option>
        <option value="background">Open in background</option>
      </select>
    </div>
  </div>

  <div class="set-group">
    <h3>Downloads (yt-dlp)</h3>
    <div class="set-row">
      <div class="lbl">Download folder<div class="d">{s.musicDirs[0]} — your primary folder (change it in Library above)</div></div>
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
