package main

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type MoveStatus struct {
	Running bool   `json:"running"`
	Done    int    `json:"done"`
	Total   int    `json:"total"`
	Moved   int    `json:"moved"`
	Skipped int    `json:"skipped"`
	Failed  int    `json:"failed"`
	Message string `json:"message"`
}

var (
	moveMu     sync.Mutex
	moveActive bool
	moveState  MoveStatus
)

func (a *App) GetMoveStatus() MoveStatus {
	moveMu.Lock()
	defer moveMu.Unlock()
	return moveState
}

func (a *App) setMoveState(st MoveStatus) {
	moveMu.Lock()
	moveState = st
	moveMu.Unlock()
	a.emit("library:move", st)
}

// CountMovable reports how many library files live under the given folders (for the confirm dialog).
func (a *App) CountMovable(sources []string, dest string) []int64 {
	var n, size int64
	dest = filepath.Clean(dest)
	for _, t := range a.reloadTracks() {
		if t.Path == "" || underDir(t.Path, dest) {
			continue
		}
		for _, src := range sources {
			if underDir(t.Path, filepath.Clean(src)) {
				n++
				size += t.Size
				break
			}
		}
	}
	return []int64{n, size}
}

func underDir(p, dir string) bool {
	return dir != "" && strings.HasPrefix(p, dir+string(filepath.Separator))
}

type moveUpdate struct{ id, rootKey, rel string }

func (a *App) moveMusic(sources []string, dest string) {
	moveMu.Lock()
	if moveActive {
		moveMu.Unlock()
		return
	}
	moveActive = true
	moveMu.Unlock()
	defer func() {
		moveMu.Lock()
		moveActive = false
		moveMu.Unlock()
	}()

	dest = filepath.Clean(dest)
	if err := os.MkdirAll(dest, 0o755); err != nil {
		a.setMoveState(MoveStatus{Message: "Cannot create destination: " + err.Error()})
		return
	}
	// block rescans (and watcher-triggered scans) so they never see half-moved files
	a.scanner.scanMu.Lock()
	defer a.scanner.scanMu.Unlock()

	destKey := a.store.EnsureRoot(dest)
	var cleanSrc []string
	for _, s := range sources {
		if s = filepath.Clean(s); s != dest {
			cleanSrc = append(cleanSrc, s)
		}
	}
	type item struct {
		t   Track
		src string
	}
	var todo []item
	all, _ := a.store.AllTracksIncludeHidden()
	for _, t := range all {
		if t.Path == "" || underDir(t.Path, dest) {
			continue
		}
		for _, src := range cleanSrc {
			if underDir(t.Path, src) {
				todo = append(todo, item{t, src})
				break
			}
		}
	}

	st := MoveStatus{Running: true, Total: len(todo)}
	a.setMoveState(st)
	var pending []moveUpdate
	flush := func() {
		if len(pending) > 0 {
			_ = a.store.MoveTracks(pending)
			pending = nil
		}
	}
	last := time.Now()
	for _, it := range todo {
		relOS, err := filepath.Rel(it.src, it.t.Path)
		rel, ok := cleanRelPath(filepath.ToSlash(relOS))
		if err != nil || !ok {
			st.Failed++
			st.Done++
			continue
		}
		target := filepath.Join(dest, filepath.FromSlash(rel))
		if fi, err := os.Stat(target); err == nil {
			if fi.Size() == it.t.Size {
				// same file already at the destination: leave the original alone
				st.Skipped++
				st.Done++
				continue
			}
			target = uniqueTarget(target)
			rel, _ = cleanRelPath(filepath.ToSlash(mustRel(dest, target)))
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			st.Failed++
			st.Done++
			continue
		}
		if err := moveFile(it.t.Path, target); err != nil {
			st.Failed++
			st.Done++
			continue
		}
		base := strings.TrimSuffix(it.t.Path, filepath.Ext(it.t.Path))
		tbase := strings.TrimSuffix(target, filepath.Ext(target))
		for _, ext := range []string{".lrc", ".jpg", ".jpeg", ".png", ".webp"} {
			if fileExists(base+ext) && !fileExists(tbase+ext) {
				_ = moveFile(base+ext, tbase+ext)
			}
		}
		pending = append(pending, moveUpdate{it.t.ID, destKey, rel})
		st.Moved++
		st.Done++
		if len(pending) >= 25 {
			flush()
		}
		if time.Since(last) > 250*time.Millisecond {
			last = time.Now()
			a.setMoveState(st)
		}
	}
	flush()
	for _, src := range cleanSrc {
		removeEmptyDirs(src, dest)
	}
	st.Running = false
	st.Message = fmt.Sprintf("Moved %d song(s)", st.Moved)
	if st.Skipped > 0 {
		st.Message += fmt.Sprintf(", %d already there", st.Skipped)
	}
	if st.Failed > 0 {
		st.Message += fmt.Sprintf(", %d failed", st.Failed)
	}
	a.reloadTracks()
	a.emit("library:changed")
	a.setMoveState(st)
	a.emit("app:toast", st.Message)
}

func mustRel(base, p string) string {
	r, err := filepath.Rel(base, p)
	if err != nil {
		return filepath.Base(p)
	}
	return r
}

func uniqueTarget(p string) string {
	ext := filepath.Ext(p)
	base := strings.TrimSuffix(p, ext)
	for i := 1; ; i++ {
		c := fmt.Sprintf("%s (%d)%s", base, i, ext)
		if !fileExists(c) {
			return c
		}
	}
}

// moveFile renames, or copies + verifies + deletes when crossing filesystems.
func moveFile(from, to string) error {
	if err := os.Rename(from, to); err == nil {
		return nil
	}
	in, err := os.Open(from)
	if err != nil {
		return err
	}
	defer in.Close()
	fi, err := in.Stat()
	if err != nil {
		return err
	}
	tmp := to + ".vmpart"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	if err := out.Sync(); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	out.Close()
	if st, err := os.Stat(tmp); err != nil || st.Size() != fi.Size() {
		os.Remove(tmp)
		return fmt.Errorf("size mismatch after copy")
	}
	_ = os.Chtimes(tmp, fi.ModTime(), fi.ModTime())
	if err := os.Rename(tmp, to); err != nil {
		os.Remove(tmp)
		return err
	}
	return os.Remove(from)
}

func removeEmptyDirs(root, keep string) {
	var dirs []string
	_ = filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err == nil && d.IsDir() && p != root {
			dirs = append(dirs, p)
		}
		return nil
	})
	for i := len(dirs) - 1; i >= 0; i-- {
		if dirs[i] == keep || underDir(keep, dirs[i]) {
			continue
		}
		_ = os.Remove(dirs[i]) // only succeeds when empty
	}
}

// MoveTracks re-points tracks at a new root/rel path in one transaction.
func (s *Store) MoveTracks(ups []moveUpdate) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	for _, u := range ups {
		if _, err := tx.Exec(`UPDATE tracks SET root_key=?, rel_path=? WHERE id=?`, u.rootKey, u.rel, u.id); err != nil {
			tx.Rollback()
			return err
		}
	}
	return tx.Commit()
}
