<script lang="ts">
  import Icon from './Icon.svelte'
  import { core, Go } from './store.svelte'
  import type { AISettings, AITestResult, GenreTargets, Settings } from './types'

  const s = $derived(core.settings)
  const ai = $derived(s?.ai)
  const gs = $derived(core.genreStatus)
  const lai = $derived(core.localAI)

  const localProviders = $derived(core.aiProviders.filter((p) => p.local))
  const cloudProviders = $derived(core.aiProviders.filter((p) => !p.local))
  const preset = $derived(core.aiProviders.find((p) => p.id === (ai?.provider || 'openai')))
  const selLocalModel = $derived(core.localAIModels.find((m) => m.id === (ai?.localModelId || 'qwen2.5-3b')))

  let showKey = $state(false)
  let overrideUrl = $state(false)
  let testing = $state(false)
  let testResult = $state<AITestResult | null>(null)
  let saveTimer: ReturnType<typeof setTimeout> | undefined

  const isLocalBuiltin = $derived((ai?.provider || 'openai') === 'local')
  const needsEndpointField = $derived(
    ai?.provider === 'ollama' || ai?.provider === 'lmstudio' || ai?.provider === 'custom' || overrideUrl || Boolean(ai?.baseUrl),
  )
  const localDownloadBusy = $derived(lai.state === 'downloading' || lai.state === 'extracting')
  const selectedLocalReady = $derived.by(() => {
    if (!ai) return false
    if (!lai.runtimeReady) return false
    if (ai.localModelId === 'custom') return Boolean(ai.localCustomGguf?.trim())
    return (lai.installedModels ?? []).includes(ai.localModelId || 'qwen2.5-3b')
  })

  function fmtBytes(bytes: number): string {
    if (!bytes || bytes <= 0) return '0 MB'
    const mb = bytes / (1024 * 1024)
    if (mb < 1024) return `${mb.toFixed(0)} MB`
    return `${(mb / 1024).toFixed(2)} GB`
  }

  function fmtSizeMB(mb: number): string {
    if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`
    return `${mb} MB`
  }

  async function saveAI(patch: Partial<AISettings>, immediate = true) {
    if (!core.settings) return
    clearTimeout(saveTimer)
    const nextAI: AISettings = { ...core.settings.ai, ...patch }
    const next: Settings = { ...core.settings, ai: nextAI }
    core.settings = next
    if (immediate) {
      const res = (await Go.SaveSettings(next as never)) as unknown as Settings
      core.settings = res
    } else {
      saveTimer = setTimeout(async () => {
        if (!core.settings) return
        const res = (await Go.SaveSettings(core.settings as never)) as unknown as Settings
        core.settings = res
      }, 250)
    }
  }

  async function flushAISettings() {
    clearTimeout(saveTimer)
    if (!core.settings) return
    const res = (await Go.SaveSettings(core.settings as never)) as unknown as Settings
    core.settings = res
  }

  function changeProvider(nextId: string) {
    if (!ai) return
    const nextPreset = core.aiProviders.find((p) => p.id === nextId)
    testResult = null
    overrideUrl = false
    saveAI({
      provider: nextId,
      baseUrl: '',
      model: nextPreset?.defaultModel ?? '',
    })
  }

  async function pickCustomGGUF() {
    const file = await Go.PickGGUFFile()
    if (file) {
      testResult = null
      await saveAI({ localCustomGguf: file })
    }
  }

  async function testConnection() {
    if (!ai || testing) return
    await flushAISettings()
    testing = true
    testResult = null
    try {
      testResult = (await Go.TestAIConnection(core.settings!.ai as never)) as unknown as AITestResult
    } catch (e) {
      testResult = { ok: false, message: String(e), latencyMs: 0 }
    } finally {
      testing = false
    }
  }

  async function startLocalDownload() {
    if (!ai) return
    await flushAISettings()
    const err = await Go.DownloadLocalAI(ai.localModelId || 'qwen2.5-3b', !!ai.localGpu)
    if (err) core.toast(err)
  }

  function confirmDeleteLocalModel() {
    if (!ai || ai.localModelId === 'custom') return
    const label = selLocalModel?.name ?? ai.localModelId
    core.modal = {
      type: 'confirm',
      title: 'Delete local AI model?',
      message: `Remove ${label} from disk? The llama.cpp runtime stays installed.`,
      confirmLabel: 'Delete model',
      danger: true,
      onConfirm: async () => {
        const err = await Go.DeleteLocalModel(ai.localModelId)
        if (err) core.toast(err)
        else core.toast('Local model deleted')
      },
    }
  }

  function confirmDeleteAllLocal() {
    core.modal = {
      type: 'confirm',
      title: 'Remove all local AI files?',
      message: `Deletes the llama.cpp runtime and all downloaded GGUF models (${fmtBytes(lai.diskUsageBytes)} freed). Nothing will be re-downloaded unless you ask.`,
      confirmLabel: 'Remove all',
      danger: true,
      onConfirm: async () => {
        const err = await Go.DeleteAllLocalAI()
        if (err) core.toast(err)
        else core.toast('Removed all local AI files')
      },
    }
  }

  function activeModelLabel(): string {
    if (!ai) return ''
    if (ai.provider === 'local') {
      if (ai.localModelId === 'custom') return ai.localCustomGguf || 'Custom GGUF'
      return selLocalModel?.name ?? ai.localModelId
    }
    return ai.model || preset?.defaultModel || 'default'
  }

  function activeProviderLabel(): string {
    return preset?.name ?? ai?.provider ?? 'AI'
  }

  async function promptAIDetect(mode: 'missing' | 'all') {
    if (!ai || gs.running) return
    await flushAISettings()
    const targets = (await Go.GetGenreTargets()) as unknown as GenreTargets
    const count = mode === 'missing' ? targets.missing : targets.allAuto
    if (count === 0) {
      core.toast(mode === 'missing' ? 'All songs already have genres' : 'No eligible songs (all are hand-set or file-tagged)')
      return
    }
    let batchSize = 15
    if (ai.provider === 'local' || ai.provider === 'ollama' || ai.provider === 'lmstudio') {
      batchSize = ai.webSearch ? 8 : 10
    } else if (ai.provider === 'gemini') {
      batchSize = ai.geminiGrounding ? 8 : 15
    } else if (ai.webSearch) {
      batchSize = 12
    }
    const batches = Math.ceil(count / batchSize)
    const privacy = isLocalBuiltin || ai.provider === 'ollama' || ai.provider === 'lmstudio'
      ? 'Runs locally on this PC (no audio or data leaves your machine).'
      : 'Only artist, title, and album text are sent (never audio).'

    core.modal = {
      type: 'confirm',
      title: mode === 'missing' ? `AI detect ${count} missing song(s)?` : `AI re-detect ${count} song(s)?`,
      message:
        `Provider: ${activeProviderLabel()} (${activeModelLabel()}). ` +
        `This will process ${count} song(s) in ~${batches} request(s). ${privacy} ` +
        (mode === 'all' ? 'Hand-set genres and embedded file tags are never changed.' : 'Progress is saved after every batch.'),
      confirmLabel: mode === 'missing' ? 'Start AI detect' : 'Start AI re-detect',
      onConfirm: async () => {
        await saveAI({ enabled: true })
        const err = await Go.DetectGenresAI(mode)
        if (err) core.toast(err)
      },
    }
  }

  async function resumeAI() {
    if (gs.running) return
    await flushAISettings()
    await saveAI({ enabled: true })
    const err = await Go.DetectGenresAI('resume')
    if (err) core.toast(err)
  }

  const pct = $derived(gs.total > 0 ? Math.min(100, Math.round((gs.done * 100) / gs.total)) : 0)
</script>

{#if s && ai}
  <div class="set-group">
    <h3>Genre detection</h3>

    <div class="ai-subbox">
      <div class="ai-stats-row">
        <span class="ai-stat-pill"><b>{gs.identified}</b> / {core.tracks.length} have genres</span>
        <span class="ai-stat-pill accent"><b>{gs.aiIdentified}</b> AI-tagged</span>
        {#if gs.unidentified > 0}
          <span class="ai-stat-pill"><b>{gs.unidentified}</b> unidentified</span>
        {/if}
        {#if gs.unchecked > 0}
          <span class="ai-stat-pill"><b>{gs.unchecked}</b> unchecked</span>
        {/if}
      </div>

      {#if gs.running}
        <div class="ai-progress-row">
          <div style="flex:1;display:flex;flex-direction:column;gap:6px">
            <div class="hint">
              {gs.phase || 'Working'}{gs.total > 0 ? `… ${gs.done}/${gs.total} (${pct}%)` : '…'}
            </div>
            <div class="bar"><i style="width:{gs.total > 0 ? pct : 30}%"></i></div>
          </div>
          <button class="btn ghost" onclick={() => Go.CancelGenreDetection()} style="display:inline-flex;align-items:center;gap:6px">
            <Icon name="stop" size={16} /> Stop
          </button>
        </div>
      {:else if gs.error}
        <div class="ai-status-err">{gs.error}</div>
      {:else if gs.message}
        <div class="hint">{gs.message}</div>
      {/if}
    </div>

    <div class="set-row">
      <div class="lbl">
        Standard detection (no AI)
        <div class="d">Uses file tags, MusicBrainz + Deezer, title/album hints, and sibling tracks by the same artist. Hand-set genres are never changed.</div>
      </div>
      <div class="ai-actions">
        <button class="btn ghost" disabled={gs.running} onclick={() => Go.DetectGenres()}>Detect missing</button>
        <button
          class="btn ghost"
          disabled={gs.running}
          onclick={() =>
            (core.modal = {
              type: 'confirm',
              title: 'Re-detect all genres?',
              message: 'All automatically detected genres are discarded and detected again without AI. Genres you set by hand are kept.',
              confirmLabel: 'Re-detect',
              onConfirm: () => Go.RedetectGenres(),
            })}>Re-detect all</button
        >
      </div>
    </div>

    <hr class="set-divider" />

    <div class="lbl">
      <div style="font-weight:700;font-size:15px">AI Genre Detection (Bring Your Own AI)</div>
      <div class="hint" style="margin-top:2px">
        Labels each song individually with up to 5 specific sub-genres &amp; styles. API keys stay only on this PC; only artist, title, and album text are sent — never audio.
      </div>
    </div>

    <div class="ai-field">
      <label class="ai-field-head" for="ai-provider-sel">Provider</label>
      <select
        id="ai-provider-sel"
        class="pill-input"
        value={ai.provider || 'openai'}
        onchange={(e) => changeProvider(e.currentTarget.value)}
      >
        <optgroup label="Local (On this PC)">
          {#each localProviders as p (p.id)}
            <option value={p.id}>{p.name}</option>
          {/each}
        </optgroup>
        <optgroup label="Cloud APIs (Bring Your Own Key)">
          {#each cloudProviders as p (p.id)}
            <option value={p.id}>{p.name}</option>
          {/each}
        </optgroup>
      </select>
      {#if preset?.note}
        <div class="hint">{preset.note}</div>
      {/if}
    </div>

    {#if isLocalBuiltin}
      <div class="ai-subbox">
        <div class="hint">
          Runs offline on your PC via <b>llama.cpp</b>. Nothing is downloaded unless you click <b>Download</b> below.
        </div>

        <div class="ai-field">
          <label class="ai-field-head" for="local-model-sel">Local model</label>
          <select
            id="local-model-sel"
            class="pill-input"
            value={ai.localModelId || 'qwen2.5-3b'}
            onchange={(e) => {
              testResult = null
              saveAI({ localModelId: e.currentTarget.value })
            }}
          >
            {#each core.localAIModels as mod (mod.id)}
              {@const installed = (lai.installedModels ?? []).includes(mod.id)}
              <option value={mod.id}>
                {mod.name} — {fmtSizeMB(mod.sizeMb)} ({mod.minRam}){installed ? ' [Installed]' : ''}
              </option>
            {/each}
            <option value="custom">Custom local .gguf file…</option>
          </select>
          {#if selLocalModel}
            <div class="hint">{selLocalModel.note}</div>
          {/if}
        </div>

        {#if ai.localModelId === 'custom'}
          <div class="ai-field">
            <label class="ai-field-head" for="local-gguf-path">GGUF model file</label>
            <div class="ai-key-wrap">
              <input
                id="local-gguf-path"
                class="pill-input"
                placeholder="/path/to/model.gguf"
                value={ai.localCustomGguf || ''}
                oninput={(e) => saveAI({ localCustomGguf: e.currentTarget.value }, false)}
                onblur={() => flushAISettings()}
              />
              <button class="btn ghost" onclick={pickCustomGGUF}>Browse…</button>
            </div>
          </div>
        {/if}

        <div class="set-row">
          <div class="lbl">
            GPU acceleration (Vulkan)
            <div class="d">
              {#if lai.vulkanAvail}
                Offloads layers to your GPU via Vulkan when supported (falls back to CPU automatically if needed).
              {:else}
                Vulkan driver not detected on this system — CPU mode will be used.
              {/if}
            </div>
          </div>
          <button
            class="switch"
            class:on={ai.localGpu}
            onclick={() => saveAI({ localGpu: !ai.localGpu })}
            aria-label="Toggle GPU acceleration"
          ></button>
        </div>

        <div class="ai-stats-row">
          <span class="ai-stat-pill" class:accent={lai.runtimeReady}>
            Runtime: <b>{lai.runtimeReady ? `${lai.runtimeVersion || 'Ready'} (${lai.runtimeVariant || 'cpu'})` : 'Not downloaded (~50 MB)'}</b>
          </span>
          {#if lai.diskUsageBytes > 0}
            <span class="ai-stat-pill">Disk used: <b>{fmtBytes(lai.diskUsageBytes)}</b></span>
          {/if}
        </div>

        {#if localDownloadBusy}
          <div class="ai-progress-row">
            <div style="flex:1;display:flex;flex-direction:column;gap:6px">
              <div class="hint">
                {lai.phase || 'Downloading'}{lai.totalBytes > 0 ? ` — ${fmtBytes(lai.downloadedBytes)} / ${fmtBytes(lai.totalBytes)} (${lai.percent.toFixed(0)}%)` : '…'}
              </div>
              <div class="bar"><i style="width:{lai.totalBytes > 0 ? lai.percent : 25}%"></i></div>
            </div>
            <button class="btn ghost" onclick={() => Go.CancelLocalAIDownload()}>Cancel</button>
          </div>
        {:else if lai.state === 'starting' || lai.state === 'running'}
          <div class="hint">{lai.message || 'Local AI engine active…'}</div>
        {:else if lai.state === 'error' && lai.error}
          <div class="ai-status-err">{lai.error}</div>
        {:else if lai.message}
          <div class="hint">{lai.message}</div>
        {/if}

        <div class="ai-actions">
          {#if !selectedLocalReady && (ai.localModelId !== 'custom' || !lai.runtimeReady)}
            <button class="btn primary" disabled={localDownloadBusy || gs.running} onclick={startLocalDownload}>
              {ai.localModelId === 'custom'
                ? 'Download runtime (~50 MB)'
                : `Download ${selLocalModel ? `(~${fmtSizeMB(selLocalModel.sizeMb)})` : 'model'}`}
            </button>
          {/if}
          {#if selectedLocalReady}
            <button class="btn ghost" disabled={testing || gs.running || localDownloadBusy} onclick={testConnection}>
              {testing ? 'Testing local AI…' : 'Test local AI'}
            </button>
          {/if}
          {#if ai.localModelId !== 'custom' && (lai.installedModels ?? []).includes(ai.localModelId || 'qwen2.5-3b')}
            <button class="btn ghost" disabled={localDownloadBusy || gs.running} onclick={confirmDeleteLocalModel}>Delete model</button>
          {/if}
          {#if lai.diskUsageBytes > 0 && !localDownloadBusy}
            <button class="btn ghost" style="color:var(--danger)" disabled={gs.running} onclick={confirmDeleteAllLocal}>Remove all local AI files</button>
          {/if}
        </div>

        {#if testResult}
          <div class={testResult.ok ? 'ai-status-ok' : 'ai-status-err'}>{testResult.message}</div>
        {/if}
      </div>
    {:else}
      <div class="ai-subbox">
        {#if needsEndpointField}
          <div class="ai-field">
            <label class="ai-field-head" for="ai-base-url">Base URL</label>
            <input
              id="ai-base-url"
              class="pill-input"
              placeholder={preset?.defaultBaseUrl || 'https://api.example.com/v1'}
              value={ai.baseUrl || ''}
              oninput={(e) => {
                testResult = null
                saveAI({ baseUrl: e.currentTarget.value }, false)
              }}
              onblur={() => flushAISettings()}
            />
          </div>
        {:else if preset?.defaultBaseUrl}
          <div class="ai-field-head" style="font-weight:500">
            <span class="hint">Endpoint: <code>{preset.defaultBaseUrl}</code></span>
            <label class="ai-check-inline">
              <input type="checkbox" bind:checked={overrideUrl} /> Override API endpoint
            </label>
          </div>
        {/if}

        {#if ai.provider !== 'ollama' && ai.provider !== 'lmstudio'}
          <div class="ai-field">
            <div class="ai-field-head">
              <label for="ai-api-key">API Key</label>
              {#if ai.provider === 'custom'}
                <span class="hint">Optional for local servers</span>
              {/if}
            </div>
            <div class="ai-key-wrap">
              <input
                id="ai-api-key"
                type={showKey ? 'text' : 'password'}
                class="pill-input"
                autocomplete="off"
                placeholder={preset?.needsKey ? 'Paste your API key' : 'Optional API key'}
                value={ai.apiKey || ''}
                oninput={(e) => {
                  testResult = null
                  saveAI({ apiKey: e.currentTarget.value }, false)
                }}
                onblur={() => flushAISettings()}
              />
              <button
                class="icon-btn"
                onclick={() => (showKey = !showKey)}
                title={showKey ? 'Hide API key' : 'Show API key'}
                aria-label={showKey ? 'Hide API key' : 'Show API key'}
              >
                <Icon name={showKey ? 'eye-off' : 'eye'} size={18} />
              </button>
            </div>
          </div>
        {/if}

        <div class="ai-field">
          <label class="ai-field-head" for="ai-model-name">Model</label>
          <input
            id="ai-model-name"
            class="pill-input"
            placeholder={preset?.defaultModel || 'model-name'}
            value={ai.model || ''}
            oninput={(e) => {
              testResult = null
              saveAI({ model: e.currentTarget.value }, false)
            }}
            onblur={() => flushAISettings()}
          />
        </div>

        <div class="ai-actions">
          <button class="btn ghost" disabled={testing || gs.running} onclick={testConnection}>
            {testing ? 'Testing connection…' : 'Test connection'}
          </button>
        </div>

        {#if testResult}
          <div class={testResult.ok ? 'ai-status-ok' : 'ai-status-err'}>{testResult.message}</div>
        {/if}
      </div>
    {/if}

    <div class="set-row">
      <div class="lbl">
        Search web &amp; metadata before AI tagging
        <div class="d">
          Looks up YouTube tags/descriptions, iTunes genres, and Wikipedia before asking the AI. Free and works on all models, including local AI and free-tier API keys.
        </div>
      </div>
      <button
        class="switch"
        class:on={ai.webSearch}
        onclick={() => saveAI({ webSearch: !ai.webSearch })}
        aria-label="Toggle web search enrichment"
      ></button>
    </div>

    {#if ai.provider === 'gemini'}
      <div class="set-row">
        <div class="lbl">
          Gemini Google Search grounding (paid tier)
          <div class="d">
            Enables Google's native Search tool inside Gemini 3+. Requires a billing-linked Gemini project (free keys reject it with 429).
          </div>
        </div>
        <button
          class="switch"
          class:on={ai.geminiGrounding}
          onclick={() => saveAI({ geminiGrounding: !ai.geminiGrounding })}
          aria-label="Toggle Gemini Google Search grounding"
        ></button>
      </div>
    {/if}

    <div class="set-row">
      <div class="lbl">
        Auto-detect newly added songs with AI
        <div class="d">
          Runs AI classification automatically after scans and downloads. Built-in Local AI only auto-runs when the model is already downloaded.
        </div>
      </div>
      <button
        class="switch"
        class:on={ai.autoOnNew}
        onclick={() => saveAI({ autoOnNew: !ai.autoOnNew, enabled: !ai.autoOnNew ? true : ai.enabled })}
        aria-label="Toggle auto AI detection"
      ></button>
    </div>

    <div class="set-row">
      <div class="lbl">
        Allow custom genres outside built-in tree
        <div class="d">Keep niche AI-marked genre tags when a song does not match any genre in the built-in tree.</div>
      </div>
      <button
        class="switch"
        class:on={ai.allowCustomGenres}
        onclick={() => saveAI({ allowCustomGenres: !ai.allowCustomGenres })}
        aria-label="Toggle custom genres"
      ></button>
    </div>

    <div class="ai-actions">
      {#if gs.pendingAI > 0}
        <button class="btn primary" disabled={gs.running} onclick={resumeAI}>
          Resume AI detection ({gs.pendingAI} left)
        </button>
        <button class="btn ghost" disabled={gs.running} onclick={() => Go.ClearPendingAI()}>
          Clear pending
        </button>
      {/if}
      <button class="btn primary" disabled={gs.running || localDownloadBusy} onclick={() => promptAIDetect('missing')}>
        AI detect (missing only)
      </button>
      <button class="btn ghost" disabled={gs.running || localDownloadBusy} onclick={() => promptAIDetect('all')}>
        AI re-detect all
      </button>
    </div>
  </div>
{/if}
