package main

import (
	"fmt"
	"strconv"
	"sync"

	"github.com/godbus/dbus/v5"
	"github.com/godbus/dbus/v5/introspect"
	"github.com/godbus/dbus/v5/prop"
)

// Mpris exposes the player on the D-Bus session bus (org.mpris.MediaPlayer2.*).
// Desktop environments route the global media keys (play/pause/next/previous) to MPRIS players,
// so this is what makes hardware media keys work everywhere (KDE, GNOME, Hyprland+playerctl, ...).
type Mpris struct {
	app   *App
	conn  *dbus.Conn
	props *prop.Properties

	mu         sync.Mutex
	lastStatus string
	lastTrack  int64
	lastLoop   string
	lastShuf   bool
	lastVol    float64
	lastPos    int64
	started    bool
}

const (
	mprisPath   = "/org/mpris/MediaPlayer2"
	mprisRootIf = "org.mpris.MediaPlayer2"
	mprisPlayIf = "org.mpris.MediaPlayer2.Player"
)

func NewMpris(a *App) *Mpris { return &Mpris{app: a} }

type mprisRoot struct{ m *Mpris }

func (r *mprisRoot) Raise() *dbus.Error { r.m.app.ShowWindow(); return nil }
func (r *mprisRoot) Quit() *dbus.Error  { go r.m.app.QuitApp(); return nil }

type mprisPlayer struct{ m *Mpris }

func (p *mprisPlayer) Next() *dbus.Error      { go p.m.app.Next(); return nil }
func (p *mprisPlayer) Previous() *dbus.Error  { go p.m.app.Previous(); return nil }
func (p *mprisPlayer) Pause() *dbus.Error     { go p.m.app.Pause(); return nil }
func (p *mprisPlayer) PlayPause() *dbus.Error { go p.m.app.TogglePlayPause(); return nil }
func (p *mprisPlayer) Stop() *dbus.Error      { go p.m.app.player.Stop(); return nil }
func (p *mprisPlayer) Play() *dbus.Error {
	go func() {
		st := p.m.app.player.State()
		if st.QueueLen == 0 {
			p.m.app.TogglePlayPause()
		} else {
			p.m.app.Resume()
		}
	}()
	return nil
}
func (p *mprisPlayer) Seek(offset int64) *dbus.Error {
	go p.m.app.SeekRelative(float64(offset) / 1e6)
	return nil
}
func (p *mprisPlayer) SetPosition(_ dbus.ObjectPath, pos int64) *dbus.Error {
	go p.m.app.Seek(float64(pos) / 1e6)
	return nil
}
func (p *mprisPlayer) OpenUri(string) *dbus.Error { return nil }

func (m *Mpris) Start() {
	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		return
	}
	m.conn = conn
	reply, err := conn.RequestName("org.mpris.MediaPlayer2.vibemusic", dbus.NameFlagDoNotQueue)
	if err != nil || reply != dbus.RequestNameReplyPrimaryOwner {
		return
	}
	root := &mprisRoot{m}
	player := &mprisPlayer{m}
	_ = conn.Export(root, mprisPath, mprisRootIf)
	_ = conn.Export(player, mprisPath, mprisPlayIf)

	meta := map[string]dbus.Variant{
		"mpris:trackid": dbus.MakeVariant(dbus.ObjectPath("/org/mpris/MediaPlayer2/TrackList/NoTrack")),
	}
	props, err := prop.Export(conn, mprisPath, prop.Map{
		mprisRootIf: {
			"CanQuit":             {Value: true, Writable: false, Emit: prop.EmitFalse},
			"CanRaise":            {Value: true, Writable: false, Emit: prop.EmitFalse},
			"HasTrackList":        {Value: false, Writable: false, Emit: prop.EmitFalse},
			"CanSetFullscreen":    {Value: false, Writable: false, Emit: prop.EmitFalse},
			"Fullscreen":          {Value: false, Writable: false, Emit: prop.EmitFalse},
			"Identity":            {Value: "Vibe Music", Writable: false, Emit: prop.EmitFalse},
			"DesktopEntry":        {Value: "vibe-music", Writable: false, Emit: prop.EmitFalse},
			"SupportedUriSchemes": {Value: []string{"file"}, Writable: false, Emit: prop.EmitFalse},
			"SupportedMimeTypes":  {Value: []string{"audio/mpeg", "audio/mp4", "audio/ogg", "audio/flac"}, Writable: false, Emit: prop.EmitFalse},
		},
		mprisPlayIf: {
			"PlaybackStatus": {Value: "Stopped", Writable: false, Emit: prop.EmitTrue},
			"LoopStatus": {Value: "None", Writable: true, Emit: prop.EmitTrue, Callback: func(c *prop.Change) *dbus.Error {
				mode := "off"
				switch c.Value.(string) {
				case "Track":
					mode = "one"
				case "Playlist":
					mode = "all"
				}
				go func() { m.app.SetRepeat(mode) }()
				return nil
			}},
			"Rate":        {Value: 1.0, Writable: false, Emit: prop.EmitTrue},
			"MinimumRate": {Value: 1.0, Writable: false, Emit: prop.EmitFalse},
			"MaximumRate": {Value: 1.0, Writable: false, Emit: prop.EmitFalse},
			"Shuffle": {Value: false, Writable: true, Emit: prop.EmitTrue, Callback: func(c *prop.Change) *dbus.Error {
				v := c.Value.(bool)
				go func() { m.app.SetShuffle(v) }()
				return nil
			}},
			"Metadata": {Value: meta, Writable: false, Emit: prop.EmitTrue},
			"Volume": {Value: 0.8, Writable: true, Emit: prop.EmitTrue, Callback: func(c *prop.Change) *dbus.Error {
				v := c.Value.(float64)
				go func() { m.app.SetVolume(int(v*100 + 0.5)) }()
				return nil
			}},
			"Position":      {Value: int64(0), Writable: false, Emit: prop.EmitFalse},
			"CanGoNext":     {Value: true, Writable: false, Emit: prop.EmitTrue},
			"CanGoPrevious": {Value: true, Writable: false, Emit: prop.EmitTrue},
			"CanPlay":       {Value: true, Writable: false, Emit: prop.EmitTrue},
			"CanPause":      {Value: true, Writable: false, Emit: prop.EmitTrue},
			"CanSeek":       {Value: true, Writable: false, Emit: prop.EmitTrue},
			"CanControl":    {Value: true, Writable: false, Emit: prop.EmitFalse},
		},
	})
	if err != nil {
		return
	}
	m.props = props
	n := &introspect.Node{Name: mprisPath, Interfaces: []introspect.Interface{
		introspect.IntrospectData,
		prop.IntrospectData,
		{Name: mprisRootIf, Methods: introspect.Methods(root), Properties: props.Introspection(mprisRootIf)},
		{Name: mprisPlayIf, Methods: introspect.Methods(player), Properties: props.Introspection(mprisPlayIf),
			Signals: []introspect.Signal{{Name: "Seeked", Args: []introspect.Arg{{Name: "Position", Type: "x"}}}}},
	}}
	_ = conn.Export(introspect.NewIntrospectable(n), mprisPath, "org.freedesktop.DBus.Introspectable")
	m.started = true
}

func (m *Mpris) Close() {
	if m.conn != nil {
		_ = m.conn.Close()
	}
}

// Update is called on every player state publication; only changed properties hit the bus.
func (m *Mpris) Update(st PlayerState, t Track, ok bool) {
	if !m.started {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	set := func(name string, v any) { _ = m.props.Set(mprisPlayIf, name, dbus.MakeVariant(v)) }

	status := "Stopped"
	switch {
	case st.Playing && st.Loaded:
		status = "Playing"
	case st.Loaded:
		status = "Paused"
	case ok:
		status = "Paused" // keep the player "alive" so media keys still target us
	}
	if status != m.lastStatus {
		m.lastStatus = status
		set("PlaybackStatus", status)
	}
	if ok && st.TrackID != m.lastTrack {
		m.lastTrack = st.TrackID
		md := map[string]dbus.Variant{
			"mpris:trackid": dbus.MakeVariant(dbus.ObjectPath("/org/vibemusic/track/" + strconv.FormatInt(t.ID, 10))),
			"mpris:length":  dbus.MakeVariant(int64(st.Duration * 1e6)),
			"xesam:title":   dbus.MakeVariant(t.Title),
			"xesam:artist":  dbus.MakeVariant([]string{t.Artist}),
			"xesam:album":   dbus.MakeVariant(t.Album),
			"xesam:url":     dbus.MakeVariant("file://" + t.Path),
		}
		if t.HasCover {
			md["mpris:artUrl"] = dbus.MakeVariant("file://" + coverPath(t.ID))
		}
		set("Metadata", md)
	}
	loop := "None"
	switch st.Repeat {
	case "one":
		loop = "Track"
	case "all":
		loop = "Playlist"
	}
	if loop != m.lastLoop {
		m.lastLoop = loop
		set("LoopStatus", loop)
	}
	if st.Shuffle != m.lastShuf {
		m.lastShuf = st.Shuffle
		set("Shuffle", st.Shuffle)
	}
	vol := float64(st.Volume) / 100
	if vol != m.lastVol {
		m.lastVol = vol
		set("Volume", vol)
	}
	pos := int64(st.Position * 1e6)
	diff := pos - m.lastPos
	m.lastPos = pos
	_ = m.props.Set(mprisPlayIf, "Position", dbus.MakeVariant(pos))
	if diff < -500_000 || diff > 2_000_000 { // a seek happened
		_ = m.conn.Emit(mprisPath, mprisPlayIf+".Seeked", pos)
	}
}

var _ = fmt.Sprint
