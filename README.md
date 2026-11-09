# Vibe Music

A local-first desktop music player for Linux and Windows built with Go, Wails, Svelte 5, libmpv, SQLite.

## Download

- **Linux**: [Download `VibeMusic`](https://github.com/Hicemir/Vibe-Music/releases/latest/download/VibeMusic) *(run `chmod +x VibeMusic` after downloading)*
- **Windows**: [Download `VibeMusic.exe`](https://github.com/Hicemir/Vibe-Music/releases/latest/download/VibeMusic.exe)

## Features

- **Home**: Up to 4 recently played playlists on top, followed by top tracks, recently added, and your full music pool
- **Playlists**: Create, rename, delete, drag-to-reorder tracks, import/export M3U8, and a dedicated **Liked Songs** collection.
- **Bottom Player Bar & Queue**: Seek bar, volume/mute, shuffle (Fisher-Yates with unshuffle restore), repeat (`off` / `all` / `one`), slide-out **Queue** panel with drag-to-reorder and `Play Next`, **Lyrics** panel (`.lrc` sidecar or embedded tags), **Sleep Timer**, and **Mini Player** (always-on-top compact mode).
- **Audio Engine (`libmpv`)**: Direct local playback via `libmpv` (no local HTTP streaming server), gapless playback, ReplayGain normalization, crossfade/fade-in-out, and a 10-band Equalizer with presets.
- **Downloader (`yt-dlp`)**: Switch between **Track** and **Playlist** mode, paste a URL, and download in `m4a`, `opus` or `mp3` with embedded metadata and cover art. Playlist downloads automatically create/update a matching playlist in the app.
- **System Tray & Global Media Keys**: Closing the window (`Alt+F4` or `✕`) hides to the system tray while keeping playback alive, with tray controls (`Play/Pause`, `Next`, `Previous`, `Show`, `Quit`). Exposes an **MPRIS2** D-Bus service (`org.mpris.MediaPlayer2.vibemusic`) so hardware media keys and `playerctl` work globally.

## Running & Building

Run the compiled binary directly:

```bash
./build/bin/vibeMusic
```

Development mode with live reload:

```bash
wails dev -tags webkit2_41
```

Production build:

```bash
wails build -tags webkit2_41
```
