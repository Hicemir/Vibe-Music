//go:build linux

package main

import (
	"fmt"
	"strings"

	mpv "github.com/gen2brain/go-mpv"
)

type audioEngine struct {
	m *mpv.Mpv
}

func (p *Player) initEngine(set Settings) error {
	mpv.SetLocale(mpv.LCNumeric, "C")
	m := mpv.New()
	opts := map[string]string{
		"video": "no", "audio-display": "no", "idle": "yes", "input-default-bindings": "no",
		"input-vo-keyboard": "no", "osc": "no", "terminal": "no", "load-scripts": "no", "ytdl": "no",
		"keep-open": "no", "gapless-audio": "yes", "audio-client-name": "vibeMusic", "cache": "yes",
		"load-osd-console": "no", "save-position-on-quit": "no", "resume-playback": "no",
	}
	for k, v := range opts {
		_ = m.SetOptionString(k, v)
	}
	if err := m.Initialize(); err != nil {
		return fmt.Errorf("libmpv init: %w", err)
	}
	p.eng = &audioEngine{m: m}
	_ = m.ObserveProperty(1, "time-pos", mpv.FormatDouble)
	_ = m.ObserveProperty(2, "duration", mpv.FormatDouble)
	_ = m.ObserveProperty(3, "pause", mpv.FormatFlag)

	p.mu.Lock()
	p.applyVolumeLocked()
	p.applyAudioFiltersLocked(set)
	p.mu.Unlock()

	go p.eventLoop()
	return nil
}

func (p *Player) closeEngine() {
	if p.eng != nil && p.eng.m != nil {
		p.eng.m.TerminateDestroy()
	}
}

func (p *Player) engLoadFile(_ int64, path string) error {
	if p.eng == nil || p.eng.m == nil {
		return fmt.Errorf("audio engine not ready")
	}
	_ = p.eng.m.SetPropertyString("pause", "no")
	return p.eng.m.Command([]string{"loadfile", path, "replace"})
}

func (p *Player) engStop() {
	if p.eng != nil && p.eng.m != nil {
		_ = p.eng.m.Command([]string{"stop"})
	}
}

func (p *Player) engSetPause(pause bool) {
	if p.eng == nil || p.eng.m == nil {
		return
	}
	v := "no"
	if pause {
		v = "yes"
	}
	_ = p.eng.m.SetPropertyString("pause", v)
}

func (p *Player) engSeek(sec float64) {
	if p.eng == nil || p.eng.m == nil {
		return
	}
	_ = p.eng.m.Command([]string{"seek", fmt.Sprintf("%.3f", sec), "absolute"})
}

func (p *Player) engSetVolume(vol float64) {
	if p.eng == nil || p.eng.m == nil {
		return
	}
	_ = p.eng.m.SetPropertyString("volume", fmt.Sprintf("%.1f", vol))
}

func (p *Player) engSetFilters(set Settings) {
	if p.eng == nil || p.eng.m == nil {
		return
	}
	af := ""
	if set.EQEnabled {
		var parts []string
		for i, f := range eqFreqs {
			if i < len(set.EQ) && set.EQ[i] != 0 {
				parts = append(parts, fmt.Sprintf("equalizer=f=%d:t=o:w=1:g=%.1f", f, set.EQ[i]))
			}
		}
		if len(parts) > 0 {
			af = "lavfi=[" + strings.Join(parts, ",") + "]"
		}
	}
	_ = p.eng.m.SetPropertyString("af", af)
	rg := "no"
	if set.Normalize {
		rg = "track"
	}
	_ = p.eng.m.SetPropertyString("replaygain", rg)
}

func (p *Player) eventLoop() {
	m := p.eng.m
	for {
		ev := m.WaitEvent(0.5)
		select {
		case <-p.quit:
			return
		default:
		}
		if ev == nil {
			continue
		}
		switch ev.EventID {
		case mpv.EventShutdown:
			return
		case mpv.EventPropertyChange:
			pr := ev.Property()
			p.mu.Lock()
			switch pr.Name {
			case "time-pos":
				if v, ok := pr.Data.(float64); ok {
					p.pos = v
				}
			case "duration":
				if v, ok := pr.Data.(float64); ok && v > 0 {
					p.dur = v
				}
			case "pause":
				if v, ok := pr.Data.(int); ok && p.loaded {
					p.playing = v == 0
					st, tr, ok := p.snapshotLocked()
					p.mu.Unlock()
					p.publish(st, tr, ok)
					continue
				}
			}
			p.mu.Unlock()
		case mpv.EventFileLoaded:
			p.mu.Lock()
			p.loaded = true
			p.errStreak = 0
			p.mu.Unlock()
		case mpv.EventEnd:
			ef := ev.EndFile()
			switch ef.Reason {
			case mpv.EndFileEOF:
				p.mu.Lock()
				p.onEOFLocked()
			case mpv.EndFileError:
				p.mu.Lock()
				p.onErrorLocked(ef.Error)
			default:
				continue
			}
			st, tr, ok := p.snapshotLocked()
			qs := p.queueLocked()
			p.mu.Unlock()
			p.publish(st, tr, ok)
			p.emit("player:queue", qs)
		}
	}
}
