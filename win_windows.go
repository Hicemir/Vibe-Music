//go:build windows

package main

import (
	_ "embed"
	"os/exec"
	"syscall"
	"time"

	wr "github.com/wailsapp/wails/v2/pkg/runtime"
)

//go:embed build/appicon.png
var appIcon []byte

var winApp *App

func (a *App) initNativeWindow() {
	winApp = a
	s := a.Settings()
	if s.WindowMaximised {
		wr.WindowMaximise(a.ctx)
		a.emit("window:maximised", true)
	} else {
		if s.WindowWidth >= 900 && s.WindowHeight >= 600 {
			wr.WindowSetSize(a.ctx, s.WindowWidth, s.WindowHeight)
		}
		a.emit("window:maximised", false)
	}
	go func() {
		t := time.NewTicker(1500 * time.Millisecond)
		defer t.Stop()
		for range t.C {
			if a.ctx == nil || a.quitting {
				return
			}
			if a.mini {
				continue
			}
			max := wr.WindowIsMaximised(a.ctx)
			w, h := wr.WindowGetSize(a.ctx)
			a.updateSettings(func(st *Settings) {
				if !max && w >= 900 && h >= 600 {
					st.WindowWidth = w
					st.WindowHeight = h
				}
				st.WindowMaximised = max
			})
			a.emit("window:maximised", max)
		}
	}()
}

func nativeShowWindow() {
	if winApp == nil || winApp.ctx == nil {
		return
	}
	wr.WindowShow(winApp.ctx)
	wr.WindowUnminimise(winApp.ctx)
}

func nativeMinimiseWindow() {
	if winApp == nil || winApp.ctx == nil {
		return
	}
	wr.WindowMinimise(winApp.ctx)
}

func nativeToggleMaximiseWindow() {
	if winApp == nil || winApp.ctx == nil {
		return
	}
	wr.WindowToggleMaximise(winApp.ctx)
	max := wr.WindowIsMaximised(winApp.ctx)
	winApp.updateSettings(func(s *Settings) { s.WindowMaximised = max })
	winApp.emit("window:maximised", max)
}

func nativeArmWindowDrag() {}

func nativeSetMini(on bool) {
	if winApp == nil || winApp.ctx == nil {
		return
	}
	if on {
		wr.WindowUnmaximise(winApp.ctx)
		wr.WindowSetMinSize(winApp.ctx, 340, 128)
		wr.WindowSetSize(winApp.ctx, 360, 134)
		wr.WindowSetAlwaysOnTop(winApp.ctx, true)
	} else {
		wr.WindowSetAlwaysOnTop(winApp.ctx, false)
		wr.WindowSetMinSize(winApp.ctx, 900, 600)
		s := winApp.Settings()
		if s.WindowMaximised {
			wr.WindowMaximise(winApp.ctx)
		} else {
			w, h := s.WindowWidth, s.WindowHeight
			if w < 900 || h < 600 {
				w, h = 1500, 920
			}
			wr.WindowSetSize(winApp.ctx, w, h)
		}
	}
}

func ensureDesktopIntegration() {}

func nativeOpenBrowser(url string, background bool) {
	if winApp == nil || winApp.ctx == nil {
		return
	}
	wr.BrowserOpenURL(winApp.ctx, url)
	if background {
		go func() {
			time.Sleep(250 * time.Millisecond)
			wr.WindowShow(winApp.ctx)
		}()
	}
}

func setCmdHidden(cmd *exec.Cmd) {
	if cmd != nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	}
}
