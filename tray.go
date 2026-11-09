package main

import (
	_ "embed"
	"runtime"
	"sync"

	"fyne.io/systray"
)

//go:embed trayicon.png
var trayIcon []byte

func pngToICO(pngBytes []byte) []byte {
	n := uint32(len(pngBytes))
	hdr := []byte{
		0, 0,
		1, 0,
		1, 0,
		64,
		64,
		0,
		0,
		1, 0,
		32, 0,
		byte(n), byte(n >> 8), byte(n >> 16), byte(n >> 24),
		22, 0, 0, 0,
	}
	return append(hdr, pngBytes...)
}

type Tray struct {
	app   *App
	mu    sync.Mutex
	ready bool

	mTitle, mPlay, mNext, mPrev, mShow, mQuit *systray.MenuItem
	lastTrack                                  int64
	lastPlaying                                bool
	lastLoaded                                 bool
}

func NewTray(a *App) *Tray { return &Tray{app: a} }

func (t *Tray) Ready() bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.ready
}

func (t *Tray) Start() {
	go systray.Run(t.onReady, func() {})
}

func (t *Tray) Close() { systray.Quit() }

func (t *Tray) onReady() {
	if runtime.GOOS == "windows" {
		systray.SetIcon(pngToICO(trayIcon))
	} else {
		systray.SetIcon(trayIcon)
	}
	systray.SetTitle("Vibe Music")
	systray.SetTooltip("Vibe Music")
	systray.SetOnTapped(func() { t.app.ShowWindow() })

	t.mTitle = systray.AddMenuItem("Nothing playing", "")
	t.mTitle.Disable()
	systray.AddSeparator()
	t.mPlay = systray.AddMenuItem("Play", "")
	t.mNext = systray.AddMenuItem("Next", "")
	t.mPrev = systray.AddMenuItem("Previous", "")
	systray.AddSeparator()
	t.mShow = systray.AddMenuItem("Show Vibe Music", "")
	t.mQuit = systray.AddMenuItem("Quit", "")

	t.mu.Lock()
	t.ready = true
	t.mu.Unlock()

	go func() {
		for {
			select {
			case <-t.mPlay.ClickedCh:
				t.app.TogglePlayPause()
			case <-t.mNext.ClickedCh:
				t.app.Next()
			case <-t.mPrev.ClickedCh:
				t.app.Previous()
			case <-t.mShow.ClickedCh:
				t.app.ShowWindow()
			case <-t.mQuit.ClickedCh:
				t.app.QuitApp()
				return
			}
		}
	}()
}

func (t *Tray) Update(st PlayerState, tr Track, ok bool) {
	if !t.Ready() {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	playing := st.Playing && st.Loaded
	if st.TrackID == t.lastTrack && playing == t.lastPlaying && st.Loaded == t.lastLoaded {
		return
	}
	t.lastTrack, t.lastPlaying, t.lastLoaded = st.TrackID, playing, st.Loaded
	if playing {
		t.mPlay.SetTitle("Pause")
	} else {
		t.mPlay.SetTitle("Play")
	}
	if ok {
		title := tr.Title + " — " + tr.Artist
		t.mTitle.SetTitle(title)
		systray.SetTooltip(title)
	} else {
		t.mTitle.SetTitle("Nothing playing")
		systray.SetTooltip("Vibe Music")
	}
}
