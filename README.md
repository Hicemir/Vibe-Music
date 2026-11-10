# Vibe Music

A fast, local-first desktop music player for Linux and Windows built with Go, Wails, Svelte 5, `libmpv`, and SQLite.

## Download

- **Linux**: [Download `VibeMusic`](https://github.com/Hicemir/Vibe-Music/releases/latest/download/VibeMusic) *(run `chmod +x VibeMusic` after downloading)*
- **Windows**: [Download `VibeMusic.exe`](https://github.com/Hicemir/Vibe-Music/releases/latest/download/VibeMusic.exe)

## Features

- **Customizable Home**: Reorder or toggle sections (`Recent playlists`, `Top tracks`, `Quick picks`, `Discover new music`, `Recently added`, `All music`).
- **Quick Picks & Local Radio Mix**: Start a radio mix from any song with filter pills (`All`, `Familiar`, `Rediscover`, `Same artist`).
- **Smart Playlists**: Drag-to-reorder tracks, sort by any column, instant song preview without interrupting your queue, local recommendation engine, and M3U8 import/export.
- **Online Discovery & Built-in Downloader (`yt-dlp`)**: Discover songs from YouTube Music based on your library, preview in browser, and download tracks or full playlists (`m4a`, `opus`, `mp3`) with cover art, metadata, and optional browser cookie support.
- **Audio & Controls**: Gapless playback, 10-band equalizer with presets, ReplayGain normalization, crossfade, synced `.lrc` lyrics, sleep timer, mini player, system tray, MPRIS2 / media keys, and an auto-hiding top window bar.

## Building from Source

Development mode:

```bash
wails dev -tags webkit2_41
```

Production build:

```bash
wails build -tags webkit2_41
```
