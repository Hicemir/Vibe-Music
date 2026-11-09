//go:build windows

package main

import (
	"fmt"
	"time"
)

type audioEngine struct{}

func (p *Player) initEngine(set Settings) error {
	p.eng = &audioEngine{}
	p.mu.Lock()
	p.applyVolumeLocked()
	p.applyAudioFiltersLocked(set)
	p.mu.Unlock()
	return nil
}

func (p *Player) closeEngine() {}

func (p *Player) engLoadFile(id int64, _ string) error {
	p.emit("webplayer:cmd", map[string]any{
		"cmd": "load",
		"url": fmt.Sprintf("/audio/%d?t=%d", id, time.Now().UnixNano()),
	})
	return nil
}

func (p *Player) engStop() {
	p.emit("webplayer:cmd", map[string]any{"cmd": "stop"})
}

func (p *Player) engSetPause(pause bool) {
	p.emit("webplayer:cmd", map[string]any{"cmd": "pause", "pause": pause})
}

func (p *Player) engSeek(sec float64) {
	p.emit("webplayer:cmd", map[string]any{"cmd": "seek", "sec": sec})
}

func (p *Player) engSetVolume(vol float64) {
	p.emit("webplayer:cmd", map[string]any{"cmd": "volume", "vol": vol / 100.0})
}

func (p *Player) engSetFilters(set Settings) {
	p.emit("webplayer:cmd", map[string]any{
		"cmd":     "eq",
		"enabled": set.EQEnabled,
		"eq":      set.EQ,
	})
}
