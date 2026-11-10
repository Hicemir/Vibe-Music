package main

import (
	"fmt"
	"math/rand"
	"sort"
	"sync"
	"time"
)

type qItem struct {
	QID int64
	TID int64
}

type QueueEntry struct {
	QID int64 `json:"qid"`
	TID int64 `json:"tid"`
}

type PlayerState struct {
	TrackID    int64   `json:"trackId"`
	PlaylistID int64   `json:"playlistId"`
	Playing    bool    `json:"playing"`
	Loaded     bool    `json:"loaded"`
	Position   float64 `json:"position"`
	Duration   float64 `json:"duration"`
	Volume     int     `json:"volume"`
	Muted      bool    `json:"muted"`
	Shuffle    bool    `json:"shuffle"`
	Repeat     string  `json:"repeat"`
	QueueIndex int     `json:"queueIndex"`
	QueueLen   int     `json:"queueLen"`
	HasNext    bool    `json:"hasNext"`
	HasPrev    bool    `json:"hasPrev"`
	SleepAt    int64   `json:"sleepAt"`
	SleepEnd   bool    `json:"sleepEnd"`
}

type Player struct {
	mu     sync.Mutex
	eng    *audioEngine
	lookup func(int64) (Track, bool)
	store  *Store
	emit   func(event string, data ...any)
	notify func(PlayerState, Track, bool) // state, track, trackKnown

	items   []qItem
	orig    []int64 // qids in original order
	idx     int
	nextQID int64

	shuffle bool
	repeat  string
	volume  int
	prevVol int
	muted   bool
	fade    float64

	playing    bool
	loaded     bool
	pos, dur   float64
	counted    bool
	playlist   int64
	previewTID int64
	errStreak  int

	sleepAt  time.Time
	sleepEnd bool

	quit chan struct{}
}

func NewPlayer(store *Store, lookup func(int64) (Track, bool), emit func(string, ...any)) *Player {
	return &Player{store: store, lookup: lookup, emit: emit, idx: -1, repeat: "off", volume: 80, prevVol: 80, quit: make(chan struct{})}
}

func (p *Player) Init(set Settings) error {
	p.mu.Lock()
	p.shuffle = set.Shuffle
	p.repeat = set.Repeat
	p.volume = set.Volume
	if p.volume > 0 {
		p.prevVol = p.volume
	}
	p.fade = set.FadeSeconds
	p.mu.Unlock()

	if err := p.initEngine(set); err != nil {
		return err
	}

	go p.tickLoop()
	return nil
}

func (p *Player) Close() {
	select {
	case <-p.quit:
	default:
		close(p.quit)
	}
	p.closeEngine()
}

// ---------- audio callbacks (used by WebAudio engine on Windows) ----------

func (p *Player) OnAudioLoaded(dur float64) {
	p.mu.Lock()
	p.loaded = true
	p.errStreak = 0
	if dur > 0 {
		p.dur = dur
	}
	st, tr, ok := p.snapshotLocked()
	p.mu.Unlock()
	p.publish(st, tr, ok)
}

func (p *Player) OnAudioTime(pos, dur float64) {
	p.mu.Lock()
	p.pos = pos
	if dur > 0 {
		p.dur = dur
	}
	p.mu.Unlock()
}

func (p *Player) OnAudioPause(paused bool) {
	p.mu.Lock()
	if !p.loaded {
		p.mu.Unlock()
		return
	}
	p.playing = !paused
	st, tr, ok := p.snapshotLocked()
	p.mu.Unlock()
	p.publish(st, tr, ok)
}

func (p *Player) OnAudioEOF() {
	p.mu.Lock()
	p.onEOFLocked()
	st, tr, ok := p.snapshotLocked()
	qs := p.queueLocked()
	p.mu.Unlock()
	p.publish(st, tr, ok)
	p.emit("player:queue", qs)
}

func (p *Player) OnAudioError(msg string) {
	p.mu.Lock()
	p.onErrorLocked(fmt.Errorf("%s", msg))
	st, tr, ok := p.snapshotLocked()
	qs := p.queueLocked()
	p.mu.Unlock()
	p.publish(st, tr, ok)
	p.emit("player:queue", qs)
}

func (p *Player) onEOFLocked() {
	if p.sleepEnd {
		p.sleepEnd = false
		p.sleepAt = time.Time{}
		p.stopLocked()
		return
	}
	p.nextLocked(true)
}

func (p *Player) onErrorLocked(err error) {
	p.errStreak++
	name := ""
	if p.idx >= 0 && p.idx < len(p.items) {
		if t, ok := p.lookup(p.items[p.idx].TID); ok {
			name = t.Title
		}
	}
	p.emit("app:toast", fmt.Sprintf("Cannot play “%s”: %v", name, err))
	if p.errStreak > 5 || p.idx+1 >= len(p.items) {
		p.stopLocked()
		return
	}
	p.playIndexLocked(p.idx + 1)
}

// ---------- tick loop (fade, play counts, sleep timer, state push) ----------

func (p *Player) tickLoop() {
	t := time.NewTicker(250 * time.Millisecond)
	defer t.Stop()
	for {
		select {
		case <-p.quit:
			return
		case <-t.C:
		}
		p.mu.Lock()
		changedVol := false
		if p.loaded && p.playing {
			if p.fade > 0 {
				changedVol = true
			}
			if !p.counted && (p.previewTID != 0 || (p.idx >= 0 && p.idx < len(p.items))) {
				threshold := 30.0
				if p.dur > 0 && p.dur/2 < threshold {
					threshold = p.dur / 2
				}
				if p.pos >= threshold {
					p.counted = true
					tid := p.previewTID
					pl := int64(0)
					if tid == 0 {
						tid = p.items[p.idx].TID
						pl = p.playlist
					}
					go p.store.RecordPlay(tid, pl)
				}
			}
		}
		if !p.sleepAt.IsZero() && !p.sleepEnd && time.Now().After(p.sleepAt) {
			p.sleepAt = time.Time{}
			p.pauseLocked(true)
		}
		if changedVol {
			p.applyVolumeLocked()
		}
		if p.loaded && p.playing || !p.sleepAt.IsZero() {
			st, tr, ok := p.snapshotLocked()
			p.mu.Unlock()
			p.publish(st, tr, ok)
			continue
		}
		p.mu.Unlock()
	}
}

func (p *Player) fadeFactorLocked() float64 {
	if p.fade <= 0 || p.dur <= p.fade*2 {
		return 1
	}
	f := 1.0
	if p.pos < p.fade {
		f = p.pos / p.fade
	}
	if rem := p.dur - p.pos; rem < p.fade {
		f = minf(f, rem/p.fade)
	}
	if f < 0 {
		f = 0
	}
	return f
}

func minf(a, b float64) float64 {
	if a < b {
		return a
	}
	return b
}

func (p *Player) applyVolumeLocked() {
	if p.eng == nil {
		return
	}
	v := float64(p.volume)
	if p.muted {
		v = 0
	}
	v *= p.fadeFactorLocked()
	p.engSetVolume(v)
}

// ---------- snapshot / publish ----------

func (p *Player) snapshotLocked() (PlayerState, Track, bool) {
	st := PlayerState{
		PlaylistID: p.playlist,
		Playing: p.playing, Loaded: p.loaded, Position: p.pos, Duration: p.dur, Volume: p.volume, Muted: p.muted,
		Shuffle: p.shuffle, Repeat: p.repeat, QueueIndex: p.idx, QueueLen: len(p.items),
		SleepEnd: p.sleepEnd,
	}
	if !p.sleepAt.IsZero() {
		st.SleepAt = p.sleepAt.Unix()
	}
	var tr Track
	ok := false
	if p.previewTID != 0 {
		st.TrackID = p.previewTID
		tr, ok = p.lookup(st.TrackID)
		if ok && st.Duration <= 0 {
			st.Duration = tr.Duration
		}
		st.HasNext = len(p.items) > 0
		st.HasPrev = true
	} else if p.idx >= 0 && p.idx < len(p.items) {
		st.TrackID = p.items[p.idx].TID
		tr, ok = p.lookup(st.TrackID)
		if ok && st.Duration <= 0 {
			st.Duration = tr.Duration
		}
		st.HasNext = p.idx+1 < len(p.items) || p.repeat == "all"
		st.HasPrev = true
	}
	return st, tr, ok
}

func (p *Player) publish(st PlayerState, tr Track, ok bool) {
	p.emit("player:state", st)
	if p.notify != nil {
		p.notify(st, tr, ok)
	}
}

func (p *Player) State() PlayerState {
	p.mu.Lock()
	defer p.mu.Unlock()
	st, _, _ := p.snapshotLocked()
	return st
}

func (p *Player) queueLocked() map[string]any {
	es := make([]QueueEntry, len(p.items))
	for i, it := range p.items {
		es[i] = QueueEntry{QID: it.QID, TID: it.TID}
	}
	return map[string]any{"items": es, "index": p.idx}
}

func (p *Player) Queue() map[string]any {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.queueLocked()
}

func (p *Player) flush() {
	st, tr, ok := p.snapshotLocked()
	qs := p.queueLocked()
	p.mu.Unlock()
	p.publish(st, tr, ok)
	p.emit("player:queue", qs)
}

// ---------- core playback ----------

func (p *Player) playIndexLocked(i int) {
	if i < 0 || i >= len(p.items) {
		return
	}
	t, ok := p.lookup(p.items[i].TID)
	if !ok {
		// track vanished from library; drop it and try the next
		p.removeAtLocked(i)
		if i < len(p.items) {
			p.playIndexLocked(i)
		}
		return
	}
	p.previewTID = 0
	p.idx = i
	p.pos = 0
	p.dur = t.Duration
	p.counted = false
	p.loaded = false
	p.playing = true
	p.applyVolumeLocked()
	if err := p.engLoadFile(t.ID, t.Path); err != nil {
		p.onErrorLocked(err)
	}
}

func (p *Player) stopLocked() {
	p.engStop()
	p.previewTID = 0
	p.playing = false
	p.loaded = false
	p.pos = 0
}

func (p *Player) pauseLocked(pause bool) {
	if !p.loaded {
		return
	}
	p.engSetPause(pause)
	p.playing = !pause
}

func (p *Player) nextLocked(auto bool) {
	if p.previewTID != 0 {
		if auto && p.repeat == "one" {
			tid := p.previewTID
			p.previewTID = 0
			p.playPreviewLocked(tid)
			return
		}
		p.previewTID = 0
		if p.idx >= 0 && p.idx < len(p.items) {
			p.playIndexLocked(p.idx)
			return
		}
		p.stopLocked()
		return
	}
	if len(p.items) == 0 {
		return
	}
	if auto && p.repeat == "one" {
		p.playIndexLocked(p.idx)
		return
	}
	if p.idx+1 < len(p.items) {
		p.playIndexLocked(p.idx + 1)
		return
	}
	if p.repeat == "all" {
		if p.shuffle {
			shuffleItems(p.items)
		}
		p.playIndexLocked(0)
		return
	}
	if auto {
		p.stopLocked()
		p.idx = len(p.items) - 1
		return
	}
	// manual next at the very end: restart the queue paused? just stop.
	p.stopLocked()
}

func shuffleItems(a []qItem) {
	rand.Shuffle(len(a), func(i, j int) { a[i], a[j] = a[j], a[i] })
}

func (p *Player) newItem(tid int64) qItem {
	p.nextQID++
	return qItem{QID: p.nextQID, TID: tid}
}

func (p *Player) removeAtLocked(i int) {
	qid := p.items[i].QID
	p.items = append(p.items[:i], p.items[i+1:]...)
	for j, q := range p.orig {
		if q == qid {
			p.orig = append(p.orig[:j], p.orig[j+1:]...)
			break
		}
	}
	if i < p.idx {
		p.idx--
	}
}

// ---------- public control API ----------

// PlayTracks replaces the queue. start < 0 means "no particular track" (start of list or random when shuffled).
func (p *Player) PlayTracks(ids []int64, start int, shuffle bool, playlistID int64) {
	if len(ids) == 0 {
		return
	}
	p.mu.Lock()
	items := make([]qItem, 0, len(ids))
	for _, id := range ids {
		if _, ok := p.lookup(id); ok {
			items = append(items, p.newItem(id))
		}
	}
	if len(items) == 0 {
		p.mu.Unlock()
		return
	}
	if start >= len(ids) {
		start = -1
	}
	p.orig = make([]int64, len(items))
	for i, it := range items {
		p.orig[i] = it.QID
	}
	p.shuffle = shuffle
	first := 0
	if shuffle {
		var chosen qItem
		if start >= 0 && start < len(items) {
			chosen = items[start]
			items = append(items[:start], items[start+1:]...)
		}
		shuffleItems(items)
		if chosen.QID != 0 {
			items = append([]qItem{chosen}, items...)
		}
	} else if start >= 0 {
		first = start
	}
	p.items = items
	p.playlist = playlistID
	if playlistID > 0 {
		go p.store.TouchPlaylist(playlistID)
		p.emit("playlists:changed")
	}
	p.playIndexLocked(first)
	p.flush()
}

func (p *Player) PlayQueueIndex(i int) {
	p.mu.Lock()
	p.playIndexLocked(i)
	p.flush()
}

func (p *Player) Toggle() {
	p.mu.Lock()
	if !p.loaded {
		if p.previewTID != 0 {
			p.playPreviewLocked(p.previewTID)
		} else if p.idx >= 0 && p.idx < len(p.items) {
			p.playIndexLocked(p.idx)
		}
	} else {
		p.pauseLocked(p.playing)
	}
	p.flush()
}

func (p *Player) Play() {
	p.mu.Lock()
	if !p.loaded {
		if p.previewTID != 0 {
			p.playPreviewLocked(p.previewTID)
		} else if p.idx >= 0 && p.idx < len(p.items) {
			p.playIndexLocked(p.idx)
		}
	} else {
		p.pauseLocked(false)
	}
	p.flush()
}

func (p *Player) Pause() {
	p.mu.Lock()
	p.pauseLocked(true)
	p.flush()
}

func (p *Player) Stop() {
	p.mu.Lock()
	p.stopLocked()
	p.flush()
}

func (p *Player) Next() {
	p.mu.Lock()
	p.nextLocked(false)
	p.flush()
}

func (p *Player) Prev() {
	p.mu.Lock()
	if p.previewTID != 0 {
		if p.pos > 3 || p.idx < 0 || p.idx >= len(p.items) {
			if p.loaded {
				p.engSeek(0)
				p.pos = 0
			}
		} else {
			p.playIndexLocked(p.idx)
		}
		p.flush()
		return
	}
	if p.pos > 3 || p.idx <= 0 {
		if p.loaded {
			p.engSeek(0)
			p.pos = 0
		} else if p.idx >= 0 {
			p.playIndexLocked(p.idx)
		}
	} else {
		p.playIndexLocked(p.idx - 1)
	}
	p.flush()
}

func (p *Player) Seek(sec float64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if !p.loaded {
		return
	}
	if sec < 0 {
		sec = 0
	}
	p.engSeek(sec)
	p.pos = sec
	p.applyVolumeLocked()
	st, tr, ok := p.snapshotLocked()
	go p.publish(st, tr, ok)
}

func (p *Player) SeekRelative(delta float64) {
	p.mu.Lock()
	pos := p.pos + delta
	p.mu.Unlock()
	p.Seek(pos)
}

func (p *Player) SetVolume(v int) {
	p.mu.Lock()
	if v < 0 {
		v = 0
	}
	if v > 100 {
		v = 100
	}
	p.volume = v
	if v > 0 {
		p.prevVol = v
		p.muted = false
	}
	p.applyVolumeLocked()
	st, tr, ok := p.snapshotLocked()
	p.mu.Unlock()
	p.publish(st, tr, ok)
}

func (p *Player) ToggleMute() {
	p.mu.Lock()
	if p.muted || p.volume == 0 {
		p.muted = false
		if p.volume == 0 {
			if p.prevVol > 0 {
				p.volume = p.prevVol
			} else {
				p.volume = 80
			}
		}
	} else {
		if p.volume > 0 {
			p.prevVol = p.volume
		}
		p.muted = true
	}
	p.applyVolumeLocked()
	st, tr, ok := p.snapshotLocked()
	p.mu.Unlock()
	p.publish(st, tr, ok)
}

func (p *Player) SetShuffle(on bool) {
	p.mu.Lock()
	if on != p.shuffle {
		p.shuffle = on
		if on {
			if p.idx >= 0 && p.idx < len(p.items) {
				shuffleItems(p.items[p.idx+1:])
			} else {
				shuffleItems(p.items)
			}
		} else if len(p.items) > 0 {
			cur := int64(-1)
			if p.idx >= 0 && p.idx < len(p.items) {
				cur = p.items[p.idx].QID
			}
			pos := map[int64]int{}
			for i, q := range p.orig {
				pos[q] = i
			}
			sort.SliceStable(p.items, func(a, b int) bool { return pos[p.items[a].QID] < pos[p.items[b].QID] })
			for i, it := range p.items {
				if it.QID == cur {
					p.idx = i
				}
			}
		}
	}
	p.flush()
}

func (p *Player) SetRepeat(mode string) {
	p.mu.Lock()
	if mode != "off" && mode != "all" && mode != "one" {
		mode = "off"
	}
	p.repeat = mode
	p.flush()
}

func (p *Player) playPreviewLocked(id int64) {
	t, ok := p.lookup(id)
	if !ok {
		return
	}
	p.previewTID = id
	p.pos = 0
	p.dur = t.Duration
	p.counted = false
	p.loaded = false
	p.playing = true
	p.applyVolumeLocked()
	if err := p.engLoadFile(t.ID, t.Path); err != nil {
		p.onErrorLocked(err)
	}
}

// PlayInstant plays a single track right now as a one-time preview without touching the queue:
// if it's already the playing/previewed track, it toggles play/pause; if you try another track,
// the previous preview is replaced without ever entering the queue.
func (p *Player) PlayInstant(id int64) {
	if _, ok := p.lookup(id); !ok {
		return
	}
	p.mu.Lock()
	curTID := p.previewTID
	if curTID == 0 && p.idx >= 0 && p.idx < len(p.items) {
		curTID = p.items[p.idx].TID
	}
	if curTID == id {
		if !p.loaded {
			if p.previewTID != 0 {
				p.playPreviewLocked(id)
			} else {
				p.playIndexLocked(p.idx)
			}
		} else {
			p.pauseLocked(p.playing)
		}
		p.flush()
		return
	}
	p.playPreviewLocked(id)
	p.flush()
}

func (p *Player) PlayNext(ids []int64) {
	p.mu.Lock()
	var newItems []qItem
	for _, id := range ids {
		if _, ok := p.lookup(id); ok {
			newItems = append(newItems, p.newItem(id))
		}
	}
	if len(newItems) == 0 {
		p.mu.Unlock()
		return
	}
	if len(p.items) == 0 {
		p.mu.Unlock()
		// nothing playing: just start
		p.PlayTracks(ids, 0, false, 0)
		return
	}
	at := p.idx + 1
	if at < 0 {
		at = 0
	}
	p.items = insertItems(p.items, at, newItems)
	// original order: place after the current track's original position
	opos := len(p.orig)
	if p.idx >= 0 && p.idx < len(p.items)-len(newItems) {
		cur := p.items[p.idx].QID
		for i, q := range p.orig {
			if q == cur {
				opos = i + 1
				break
			}
		}
	}
	q := make([]int64, len(newItems))
	for i, it := range newItems {
		q[i] = it.QID
	}
	p.orig = append(p.orig[:opos], append(q, p.orig[opos:]...)...)
	p.emit("app:toast", fmt.Sprintf("Playing next: %d track(s)", len(newItems)))
	p.flush()
}

func insertItems(a []qItem, at int, ins []qItem) []qItem {
	if at > len(a) {
		at = len(a)
	}
	out := make([]qItem, 0, len(a)+len(ins))
	out = append(out, a[:at]...)
	out = append(out, ins...)
	out = append(out, a[at:]...)
	return out
}

func (p *Player) AddToQueue(ids []int64) {
	p.mu.Lock()
	n := 0
	for _, id := range ids {
		if _, ok := p.lookup(id); ok {
			it := p.newItem(id)
			p.items = append(p.items, it)
			p.orig = append(p.orig, it.QID)
			n++
		}
	}
	if n == 0 {
		p.mu.Unlock()
		return
	}
	wasEmpty := p.idx < 0
	if wasEmpty {
		p.playIndexLocked(0)
	}
	p.emit("app:toast", fmt.Sprintf("Added %d track(s) to queue", n))
	p.flush()
}

func (p *Player) RemoveFromQueue(qid int64) {
	p.mu.Lock()
	for i, it := range p.items {
		if it.QID == qid {
			if i == p.idx {
				p.removeAtLocked(i)
				if i < len(p.items) {
					p.idx = i
					p.playIndexLocked(i)
				} else {
					p.idx = len(p.items) - 1
					p.stopLocked()
				}
			} else {
				p.removeAtLocked(i)
			}
			break
		}
	}
	p.flush()
}

func (p *Player) MoveInQueue(from, to int) {
	p.mu.Lock()
	n := len(p.items)
	if from < 0 || from >= n || to < 0 || to >= n || from == to {
		p.mu.Unlock()
		return
	}
	it := p.items[from]
	p.items = append(p.items[:from], p.items[from+1:]...)
	p.items = insertItems(p.items, to, []qItem{it})
	switch {
	case p.idx == from:
		p.idx = to
	case from < p.idx && to >= p.idx:
		p.idx--
	case from > p.idx && to <= p.idx:
		p.idx++
	}
	if !p.shuffle {
		for i, x := range p.items {
			p.orig[i] = x.QID
		}
	}
	p.flush()
}

// ClearQueue removes everything except the current track.
func (p *Player) ClearQueue() {
	p.mu.Lock()
	if p.idx >= 0 && p.idx < len(p.items) {
		cur := p.items[p.idx]
		p.items = []qItem{cur}
		p.orig = []int64{cur.QID}
		p.idx = 0
	} else {
		p.items = nil
		p.orig = nil
		p.idx = -1
	}
	p.flush()
}

// ---------- sleep timer / fade / EQ ----------

// SetSleep: minutes>0 pauses after that many minutes; minutes<0 pauses at end of current track; 0 cancels.
func (p *Player) SetSleep(minutes int) {
	p.mu.Lock()
	switch {
	case minutes > 0:
		p.sleepAt = time.Now().Add(time.Duration(minutes) * time.Minute)
		p.sleepEnd = false
	case minutes < 0:
		p.sleepEnd = true
		p.sleepAt = time.Now().Add(24 * time.Hour)
	default:
		p.sleepAt = time.Time{}
		p.sleepEnd = false
	}
	p.flush()
}

func (p *Player) SetFade(sec float64) {
	p.mu.Lock()
	p.fade = sec
	p.applyVolumeLocked()
	p.mu.Unlock()
}

var eqFreqs = []int{31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000}

func (p *Player) applyAudioFiltersLocked(set Settings) {
	if p.eng == nil {
		return
	}
	p.engSetFilters(set)
}

func (p *Player) ApplySettings(set Settings) {
	p.mu.Lock()
	p.fade = set.FadeSeconds
	p.applyAudioFiltersLocked(set)
	p.applyVolumeLocked()
	p.mu.Unlock()
}
