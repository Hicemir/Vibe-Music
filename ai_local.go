package main

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

type LocalAIModel struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	SizeMB   int64  `json:"sizeMb"`
	MinRAM   string `json:"minRam"`
	URL      string `json:"url"`
	Filename string `json:"filename"`
	Note     string `json:"note"`
}

var localAIModels = []LocalAIModel{
	{
		ID:       "qwen2.5-1.5b",
		Name:     "Qwen 2.5 1.5B Instruct (Fast / Low RAM)",
		SizeMB:   1050,
		MinRAM:   "4 GB+ RAM",
		URL:      "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
		Filename: "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
		Note:     "Lightest option for older CPUs or tight memory budgets.",
	},
	{
		ID:       "qwen2.5-3b",
		Name:     "Qwen 2.5 3B Instruct (Recommended)",
		SizeMB:   2060,
		MinRAM:   "6 GB+ RAM",
		URL:      "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
		Filename: "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
		Note:     "Best balance of speed, size, and genre accuracy.",
	},
	{
		ID:       "qwen2.5-7b",
		Name:     "Qwen 2.5 7B Instruct (High Accuracy)",
		SizeMB:   4790,
		MinRAM:   "8 GB+ RAM / GPU",
		URL:      "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
		Filename: "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
		Note:     "Strongest knowledge of niche anime, J-pop, and game music.",
	},
}

func GetLocalAIModels() []LocalAIModel {
	out := make([]LocalAIModel, len(localAIModels))
	copy(out, localAIModels)
	return out
}

func findLocalAIModel(id string) (LocalAIModel, bool) {
	for _, m := range localAIModels {
		if m.ID == id {
			return m, true
		}
	}
	return LocalAIModel{}, false
}

type LocalAIStatus struct {
	RuntimeReady    bool     `json:"runtimeReady"`
	RuntimeVersion  string   `json:"runtimeVersion"`
	RuntimeVariant  string   `json:"runtimeVariant"` // "" | "cpu" | "vulkan" | "metal"
	VulkanAvail     bool     `json:"vulkanAvail"`
	InstalledModels []string `json:"installedModels"`
	DiskUsageBytes  int64    `json:"diskUsageBytes"`
	State           string   `json:"state"` // "idle" | "downloading" | "extracting" | "starting" | "running" | "error"
	Phase           string   `json:"phase"`
	Percent         float64  `json:"percent"`
	DownloadedBytes int64    `json:"downloadedBytes"`
	TotalBytes      int64    `json:"totalBytes"`
	Message         string   `json:"message"`
	Error           string   `json:"error"`
}

type LocalAIManager struct {
	emit          func(string, ...any)
	releaseAPIURL string

	mu           sync.Mutex
	downloading  bool
	dlCancel     context.CancelFunc
	cmd          *exec.Cmd
	doneCh       chan error
	baseURL      string
	runningModel string
	runningGPU   bool
	status       LocalAIStatus
}

func NewLocalAIManager(emit func(string, ...any)) *LocalAIManager {
	return &LocalAIManager{
		emit:          emit,
		releaseAPIURL: "https://api.github.com/repos/ggml-org/llama.cpp/releases/latest",
		status: LocalAIStatus{
			InstalledModels: []string{},
			State:           "idle",
		},
	}
}

func llamaServerExeName() string {
	if runtime.GOOS == "windows" {
		return "llama-server.exe"
	}
	return "llama-server"
}

func findLlamaServerBinary(dir string) string {
	target := llamaServerExeName()
	var found string
	_ = filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if strings.EqualFold(d.Name(), target) {
			found = p
			return filepath.SkipAll
		}
		return nil
	})
	return found
}

func vulkanAvailable() bool {
	switch runtime.GOOS {
	case "windows":
		return true
	case "darwin":
		return false
	default:
		for _, p := range []string{
			"/usr/lib/libvulkan.so.1",
			"/usr/lib/x86_64-linux-gnu/libvulkan.so.1",
			"/usr/lib64/libvulkan.so.1",
			"/lib/x86_64-linux-gnu/libvulkan.so.1",
			"/usr/lib/aarch64-linux-gnu/libvulkan.so.1",
		} {
			if _, err := os.Stat(p); err == nil {
				return true
			}
		}
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, "ldconfig", "-p")
		setCmdHidden(cmd)
		if out, err := cmd.Output(); err == nil {
			return strings.Contains(string(out), "libvulkan.so.1")
		}
		return false
	}
}

// Status inspects $DATA_DIR/ai without creating any directories or starting any downloads.
func (m *LocalAIManager) Status() LocalAIStatus {
	base := aiDir()
	st := LocalAIStatus{
		VulkanAvail:     vulkanAvailable(),
		InstalledModels: []string{},
		State:           "idle",
	}

	if info, err := os.Stat(base); err == nil && info.IsDir() {
		rtDir := filepath.Join(base, "runtime")
		if findLlamaServerBinary(rtDir) != "" {
			st.RuntimeReady = true
			if b, err := os.ReadFile(filepath.Join(rtDir, "version.txt")); err == nil {
				st.RuntimeVersion = strings.TrimSpace(string(b))
			}
			if b, err := os.ReadFile(filepath.Join(rtDir, "variant.txt")); err == nil {
				st.RuntimeVariant = strings.TrimSpace(string(b))
			}
		}
		modelsDir := filepath.Join(base, "models")
		for _, mod := range localAIModels {
			if fi, err := os.Stat(filepath.Join(modelsDir, mod.Filename)); err == nil && fi.Size() > 1<<20 {
				st.InstalledModels = append(st.InstalledModels, mod.ID)
			}
		}
		var totalSize int64
		_ = filepath.WalkDir(base, func(_ string, d fs.DirEntry, err error) error {
			if err != nil || d.IsDir() {
				return nil
			}
			if fi, err := d.Info(); err == nil {
				totalSize += fi.Size()
			}
			return nil
		})
		st.DiskUsageBytes = totalSize
	}

	m.mu.Lock()
	if m.status.State != "" {
		st.State = m.status.State
	}
	st.Phase = m.status.Phase
	st.Percent = m.status.Percent
	st.DownloadedBytes = m.status.DownloadedBytes
	st.TotalBytes = m.status.TotalBytes
	st.Message = m.status.Message
	st.Error = m.status.Error
	m.mu.Unlock()

	return st
}

func (m *LocalAIManager) emitStatus() {
	if m.emit != nil {
		m.emit("ai:local:status", m.Status())
	}
}

func (m *LocalAIManager) setState(state, phase string, done, total int64, msg, errMsg string) {
	pct := 0.0
	if total > 0 {
		pct = float64(done) * 100 / float64(total)
		if pct > 100 {
			pct = 100
		}
	}
	m.mu.Lock()
	m.status.State = state
	m.status.Phase = phase
	m.status.DownloadedBytes = done
	m.status.TotalBytes = total
	m.status.Percent = pct
	m.status.Message = msg
	m.status.Error = errMsg
	m.mu.Unlock()
	m.emitStatus()
}

// IsReadyForAuto reports whether the configured local model and runtime are already on disk.
func (m *LocalAIManager) IsReadyForAuto(cfg AISettings) bool {
	if _, err := m.ModelPath(cfg); err != nil {
		return false
	}
	return findLlamaServerBinary(filepath.Join(aiDir(), "runtime")) != ""
}

func (m *LocalAIManager) ModelPath(cfg AISettings) (string, error) {
	if cfg.LocalModelID == "custom" {
		p := strings.TrimSpace(cfg.LocalCustomGGUF)
		if p == "" {
			return "", errors.New("choose a custom .gguf model file first")
		}
		fi, err := os.Stat(p)
		if err != nil || fi.IsDir() || fi.Size() == 0 {
			return "", fmt.Errorf("custom model file not found: %s", p)
		}
		return p, nil
	}
	id := cfg.LocalModelID
	if id == "" {
		id = "qwen2.5-3b"
	}
	mod, ok := findLocalAIModel(id)
	if !ok {
		return "", fmt.Errorf("unknown local model %q", id)
	}
	p := filepath.Join(aiDir(), "models", mod.Filename)
	fi, err := os.Stat(p)
	if err != nil || fi.Size() <= 1<<20 {
		return "", fmt.Errorf("%s is not downloaded yet — click Download in Settings → Genre detection", mod.Name)
	}
	return p, nil
}

type ghAsset struct {
	Name        string `json:"name"`
	DownloadURL string `json:"browser_download_url"`
	Size        int64  `json:"size"`
}

type ghRelease struct {
	TagName string    `json:"tag_name"`
	Assets  []ghAsset `json:"assets"`
}

func isArchiveAsset(name string) bool {
	l := strings.ToLower(name)
	if strings.HasSuffix(l, ".sha256") || strings.HasSuffix(l, ".sig") || strings.HasSuffix(l, ".asc") ||
		strings.Contains(l, "cudart") || strings.Contains(l, "xcframework") {
		return false
	}
	return strings.HasSuffix(l, ".tar.gz") || strings.HasSuffix(l, ".tgz") || strings.HasSuffix(l, ".zip")
}

func hasAnyToken(s string, tokens ...string) bool {
	for _, t := range tokens {
		if strings.Contains(s, t) {
			return true
		}
	}
	return false
}

func selectLlamaReleaseAsset(goos, goarch string, wantVulkan bool, assets []ghAsset) (ghAsset, string, error) {
	var candidates []ghAsset
	for _, a := range assets {
		if isArchiveAsset(a.Name) {
			candidates = append(candidates, a)
		}
	}

	switch goos {
	case "linux":
		var archMatch []ghAsset
		for _, a := range candidates {
			l := strings.ToLower(a.Name)
			if !strings.Contains(l, "ubuntu") {
				continue
			}
			switch goarch {
			case "amd64":
				if hasAnyToken(l, "x64", "x86_64", "amd64") {
					archMatch = append(archMatch, a)
				}
			case "arm64":
				if hasAnyToken(l, "arm64", "aarch64") {
					archMatch = append(archMatch, a)
				}
			}
		}
		if wantVulkan {
			for _, a := range archMatch {
				if strings.Contains(strings.ToLower(a.Name), "vulkan") {
					return a, "vulkan", nil
				}
			}
		}
		for _, a := range archMatch {
			l := strings.ToLower(a.Name)
			if !hasAnyToken(l, "vulkan", "cuda", "rocm", "sycl", "openvino", "kompute", "rpc", "hip", "musa", "opencl") {
				return a, "cpu", nil
			}
		}

	case "windows":
		var archMatch []ghAsset
		for _, a := range candidates {
			l := strings.ToLower(a.Name)
			if !strings.HasSuffix(l, ".zip") || !strings.Contains(l, "win") {
				continue
			}
			switch goarch {
			case "amd64":
				if hasAnyToken(l, "x64", "amd64") && !strings.Contains(l, "arm64") {
					archMatch = append(archMatch, a)
				}
			case "arm64":
				if strings.Contains(l, "arm64") {
					archMatch = append(archMatch, a)
				}
			}
		}
		if wantVulkan {
			for _, a := range archMatch {
				if strings.Contains(strings.ToLower(a.Name), "vulkan") {
					return a, "vulkan", nil
				}
			}
		}
		// Prefer explicit "cpu" first, then "avx2", then plain CPU zip.
		for _, pref := range []string{"cpu", "avx2"} {
			for _, a := range archMatch {
				l := strings.ToLower(a.Name)
				if strings.Contains(l, pref) && !hasAnyToken(l, "vulkan", "cuda", "rocm", "sycl", "openvino", "kompute", "hip", "musa", "opencl") {
					return a, "cpu", nil
				}
			}
		}
		for _, a := range archMatch {
			l := strings.ToLower(a.Name)
			if !hasAnyToken(l, "vulkan", "cuda", "rocm", "sycl", "openvino", "kompute", "hip", "musa", "opencl") {
				return a, "cpu", nil
			}
		}

	case "darwin":
		for _, a := range candidates {
			l := strings.ToLower(a.Name)
			if !strings.Contains(l, "macos") {
				continue
			}
			if goarch == "arm64" && strings.Contains(l, "arm64") {
				return a, "metal", nil
			}
			if goarch == "amd64" && hasAnyToken(l, "x64", "x86_64") {
				return a, "cpu", nil
			}
		}
	}

	return ghAsset{}, "", fmt.Errorf("no compatible llama.cpp release asset found for %s/%s", goos, goarch)
}

func safeArchiveJoin(dst, name string) (string, bool) {
	cleaned := filepath.Clean(filepath.FromSlash(name))
	if cleaned == "." || cleaned == "" || filepath.IsAbs(cleaned) || strings.HasPrefix(cleaned, "..") {
		return "", false
	}
	cleanDst := filepath.Clean(dst)
	target := filepath.Join(cleanDst, cleaned)
	if target != cleanDst && !strings.HasPrefix(target, cleanDst+string(filepath.Separator)) {
		return "", false
	}
	return target, true
}

func safeSymlinkTarget(dst, targetPath, linkName string) bool {
	if linkName == "" || filepath.IsAbs(linkName) {
		return false
	}
	cleanDst := filepath.Clean(dst)
	resolved := filepath.Clean(filepath.Join(filepath.Dir(targetPath), filepath.FromSlash(linkName)))
	return resolved == cleanDst || strings.HasPrefix(resolved, cleanDst+string(filepath.Separator))
}

func extractTarGz(src, dst string) error {
	f, err := os.Open(src)
	if err != nil {
		return err
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer gz.Close()

	cleanDst := filepath.Clean(dst)
	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		target, ok := safeArchiveJoin(cleanDst, hdr.Name)
		if !ok {
			continue
		}
		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			mode := fs.FileMode(hdr.Mode) & 0o777
			if mode == 0 {
				mode = 0o644
			}
			out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
			if err != nil {
				return err
			}
			if _, err := io.Copy(out, tr); err != nil {
				out.Close()
				return err
			}
			if err := out.Close(); err != nil {
				return err
			}
		case tar.TypeSymlink:
			if !safeSymlinkTarget(cleanDst, target, hdr.Linkname) {
				continue
			}
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			_ = os.Remove(target)
			if err := os.Symlink(hdr.Linkname, target); err != nil {
				return err
			}
		case tar.TypeLink:
			linkTarget, ok := safeArchiveJoin(cleanDst, hdr.Linkname)
			if !ok {
				continue
			}
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			_ = os.Remove(target)
			if err := os.Link(linkTarget, target); err != nil {
				return err
			}
		}
	}
	return nil
}

func extractZip(src, dst string) error {
	zr, err := zip.OpenReader(src)
	if err != nil {
		return err
	}
	defer zr.Close()

	cleanDst := filepath.Clean(dst)
	for _, f := range zr.File {
		target, ok := safeArchiveJoin(cleanDst, f.Name)
		if !ok {
			continue
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		if f.Mode()&os.ModeSymlink != 0 {
			rc, err := f.Open()
			if err != nil {
				return err
			}
			b, err := io.ReadAll(io.LimitReader(rc, 4096))
			rc.Close()
			if err != nil {
				return err
			}
			link := strings.TrimSpace(string(b))
			if safeSymlinkTarget(cleanDst, target, link) {
				_ = os.Remove(target)
				_ = os.Symlink(link, target)
			}
			continue
		}
		mode := f.Mode() & 0o777
		if mode == 0 {
			mode = 0o644
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
		if err != nil {
			rc.Close()
			return err
		}
		if _, err := io.Copy(out, rc); err != nil {
			out.Close()
			rc.Close()
			return err
		}
		out.Close()
		rc.Close()
	}
	return nil
}

func longDownloadClient() *http.Client {
	return &http.Client{
		Timeout: 0,
		Transport: &http.Transport{
			DialContext:           (&net.Dialer{Timeout: 15 * time.Second}).DialContext,
			TLSHandshakeTimeout:   15 * time.Second,
			ResponseHeaderTimeout: 30 * time.Second,
		},
	}
}

func (m *LocalAIManager) downloadToFile(ctx context.Context, client *http.Client, url, partPath string, hintTotal int64, phase string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "VibeMusic/0.6")
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("download failed (HTTP %d)", resp.StatusCode)
	}
	total := resp.ContentLength
	if total <= 0 && hintTotal > 0 {
		total = hintTotal
	}
	if err := os.MkdirAll(filepath.Dir(partPath), 0o755); err != nil {
		return err
	}
	f, err := os.OpenFile(partPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	cleanup := func() {
		f.Close()
		_ = os.Remove(partPath)
	}

	buf := make([]byte, 128*1024)
	var written int64
	lastEmit := time.Time{}
	m.setState("downloading", phase, 0, total, phase, "")
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				cleanup()
				return werr
			}
			written += int64(n)
			if time.Since(lastEmit) >= 150*time.Millisecond {
				lastEmit = time.Now()
				m.setState("downloading", phase, written, total, phase, "")
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			cleanup()
			return rerr
		}
	}
	if err := f.Close(); err != nil {
		_ = os.Remove(partPath)
		return err
	}
	m.setState("downloading", phase, written, total, phase, "")
	return nil
}

func (m *LocalAIManager) Download(modelID string, useGPU bool) string {
	var mod LocalAIModel
	needModel := modelID != "" && modelID != "custom"
	if needModel {
		var ok bool
		mod, ok = findLocalAIModel(modelID)
		if !ok {
			return fmt.Sprintf("unknown local model %q", modelID)
		}
	}

	m.mu.Lock()
	if m.downloading {
		m.mu.Unlock()
		return "A local AI download is already in progress"
	}
	ctx, cancel := context.WithCancel(context.Background())
	m.downloading = true
	m.dlCancel = cancel
	m.mu.Unlock()

	m.setState("downloading", "Preparing download", 0, 0, "Preparing download…", "")

	go func() {
		defer func() {
			cancel()
			m.mu.Lock()
			m.downloading = false
			m.dlCancel = nil
			m.mu.Unlock()
		}()

		m.Stop()
		base := aiDir()
		rtDir := filepath.Join(base, "runtime")
		modelsDir := filepath.Join(base, "models")
		if err := os.MkdirAll(modelsDir, 0o755); err != nil {
			m.setState("error", "", 0, 0, "", err.Error())
			return
		}

		client := longDownloadClient()
		curStatus := m.Status()
		wantVulkan := useGPU && vulkanAvailable()
		needRuntime := !curStatus.RuntimeReady || (wantVulkan && curStatus.RuntimeVariant != "vulkan")

		if needRuntime {
			m.setState("downloading", "Fetching llama.cpp release info", 0, 0, "Checking latest llama.cpp runtime…", "")
			req, err := http.NewRequestWithContext(ctx, http.MethodGet, m.releaseAPIURL, nil)
			if err != nil {
				m.setState("error", "", 0, 0, "", err.Error())
				return
			}
			req.Header.Set("User-Agent", "VibeMusic/0.6")
			req.Header.Set("Accept", "application/vnd.github+json")
			resp, err := client.Do(req)
			if err != nil {
				if ctx.Err() != nil {
					m.setState("idle", "", 0, 0, "Download cancelled", "")
				} else {
					m.setState("error", "", 0, 0, "", "Failed to check llama.cpp release: "+err.Error())
				}
				return
			}
			body, rerr := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
			resp.Body.Close()
			if rerr != nil || resp.StatusCode < 200 || resp.StatusCode >= 300 {
				msg := fmt.Sprintf("Failed to fetch llama.cpp release (HTTP %d)", resp.StatusCode)
				if rerr != nil {
					msg = rerr.Error()
				}
				m.setState("error", "", 0, 0, "", msg)
				return
			}
			var rel ghRelease
			if err := json.Unmarshal(body, &rel); err != nil {
				m.setState("error", "", 0, 0, "", "Invalid GitHub release JSON: "+err.Error())
				return
			}
			asset, variant, err := selectLlamaReleaseAsset(runtime.GOOS, runtime.GOARCH, wantVulkan, rel.Assets)
			if err != nil {
				m.setState("error", "", 0, 0, "", err.Error())
				return
			}

			arcPart := filepath.Join(base, "runtime.archive.part")
			if err := m.downloadToFile(ctx, client, asset.DownloadURL, arcPart, asset.Size, "Downloading llama.cpp runtime ("+variant+")"); err != nil {
				_ = os.Remove(arcPart)
				if ctx.Err() != nil {
					m.setState("idle", "", 0, 0, "Download cancelled", "")
				} else {
					m.setState("error", "", 0, 0, "", err.Error())
				}
				return
			}

			m.setState("extracting", "Extracting llama.cpp runtime", 0, 0, "Extracting llama.cpp runtime…", "")
			rtNew := filepath.Join(base, "runtime.new")
			rtOld := filepath.Join(base, "runtime.old")
			_ = os.RemoveAll(rtNew)
			_ = os.RemoveAll(rtOld)
			var extErr error
			if strings.HasSuffix(strings.ToLower(asset.Name), ".zip") {
				extErr = extractZip(arcPart, rtNew)
			} else {
				extErr = extractTarGz(arcPart, rtNew)
			}
			_ = os.Remove(arcPart)
			if extErr != nil {
				_ = os.RemoveAll(rtNew)
				m.setState("error", "", 0, 0, "", "Failed to extract runtime: "+extErr.Error())
				return
			}
			srv := findLlamaServerBinary(rtNew)
			if srv == "" {
				_ = os.RemoveAll(rtNew)
				m.setState("error", "", 0, 0, "", "Downloaded archive did not contain "+llamaServerExeName())
				return
			}
			if runtime.GOOS != "windows" {
				_ = os.Chmod(srv, 0o755)
			}
			_ = os.WriteFile(filepath.Join(rtNew, "version.txt"), []byte(rel.TagName), 0o644)
			_ = os.WriteFile(filepath.Join(rtNew, "variant.txt"), []byte(variant), 0o644)

			if _, err := os.Stat(rtDir); err == nil {
				_ = os.Rename(rtDir, rtOld)
			}
			if err := os.Rename(rtNew, rtDir); err != nil {
				_ = os.Rename(rtOld, rtDir)
				_ = os.RemoveAll(rtNew)
				m.setState("error", "", 0, 0, "", "Failed to install runtime: "+err.Error())
				return
			}
			_ = os.RemoveAll(rtOld)
		}

		if needModel {
			modelDst := filepath.Join(modelsDir, mod.Filename)
			if fi, err := os.Stat(modelDst); err != nil || fi.Size() <= 1<<20 {
				part := modelDst + ".part"
				if err := m.downloadToFile(ctx, client, mod.URL, part, mod.SizeMB*1024*1024, "Downloading "+mod.Name); err != nil {
					_ = os.Remove(part)
					if ctx.Err() != nil {
						m.setState("idle", "", 0, 0, "Download cancelled", "")
					} else {
						m.setState("error", "", 0, 0, "", err.Error())
					}
					return
				}
				if fi, err := os.Stat(part); err != nil || fi.Size() <= 1<<20 {
					_ = os.Remove(part)
					m.setState("error", "", 0, 0, "", "Downloaded model file is incomplete or corrupt")
					return
				}
				if err := os.Rename(part, modelDst); err != nil {
					_ = os.Remove(part)
					m.setState("error", "", 0, 0, "", "Failed to save model: "+err.Error())
					return
				}
			}
		}

		m.setState("idle", "", 0, 0, "Local AI is ready", "")
	}()
	return ""
}

func (m *LocalAIManager) CancelDownload() {
	m.mu.Lock()
	cancel := m.dlCancel
	m.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (m *LocalAIManager) DeleteModel(modelID string) string {
	mod, ok := findLocalAIModel(modelID)
	if !ok {
		return fmt.Sprintf("unknown local model %q", modelID)
	}
	m.Stop()
	p := filepath.Join(aiDir(), "models", mod.Filename)
	_ = os.Remove(p + ".part")
	if err := os.Remove(p); err != nil && !os.IsNotExist(err) {
		return err.Error()
	}
	m.setState("idle", "", 0, 0, "Removed "+mod.Name, "")
	return ""
}

func (m *LocalAIManager) DeleteAll() string {
	m.CancelDownload()
	// Give an active download a moment to abort and release handles before deleting files.
	for i := 0; i < 20; i++ {
		m.mu.Lock()
		busy := m.downloading
		m.mu.Unlock()
		if !busy {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	m.Stop()
	if err := os.RemoveAll(aiDir()); err != nil {
		return err.Error()
	}
	m.setState("idle", "", 0, 0, "Removed all local AI files", "")
	return ""
}

type tailBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
	max int
}

func (b *tailBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.buf.Write(p)
	if b.buf.Len() > b.max {
		excess := b.buf.Len() - b.max
		b.buf.Next(excess)
	}
	return len(p), nil
}

func (b *tailBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return strings.TrimSpace(b.buf.String())
}

func (m *LocalAIManager) isHealthy(url string) bool {
	c := &http.Client{Timeout: 1200 * time.Millisecond}
	resp, err := c.Get(url + "/health")
	if err != nil {
		return false
	}
	resp.Body.Close()
	return resp.StatusCode == http.StatusOK
}

func (m *LocalAIManager) Start(ctx context.Context, modelPath string, useGPU bool) (string, error) {
	srvBin := findLlamaServerBinary(filepath.Join(aiDir(), "runtime"))
	if srvBin == "" {
		return "", errors.New("built-in local AI runtime is not downloaded yet")
	}
	if fi, err := os.Stat(modelPath); err != nil || fi.IsDir() || fi.Size() == 0 {
		return "", fmt.Errorf("local model file not found: %s", modelPath)
	}

	m.mu.Lock()
	if m.cmd != nil && m.baseURL != "" && m.runningModel == modelPath && m.runningGPU == useGPU {
		url := m.baseURL
		m.mu.Unlock()
		if m.isHealthy(url) {
			return url, nil
		}
	} else {
		m.mu.Unlock()
	}

	m.Stop()

	wantVulkan := useGPU && vulkanAvailable()
	if wantVulkan {
		url, err := m.launchAndWait(ctx, srvBin, modelPath, 99)
		if err == nil || ctx.Err() != nil {
			return url, err
		}
		// Fallback to CPU (-ngl 0) if GPU offloading failed on this driver/GPU.
	}
	return m.launchAndWait(ctx, srvBin, modelPath, 0)
}

func (m *LocalAIManager) launchAndWait(ctx context.Context, srvBin, modelPath string, ngl int) (string, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()

	threads := runtime.NumCPU() - 1
	if threads < 1 {
		threads = 1
	}
	if threads > 8 {
		threads = 8
	}

	args := []string{
		"-m", modelPath,
		"--host", "127.0.0.1",
		"--port", strconv.Itoa(port),
		"-c", "4096",
		"-ngl", strconv.Itoa(ngl),
		"--threads", strconv.Itoa(threads),
	}
	cmd := exec.Command(srvBin, args...)
	binDir := filepath.Dir(srvBin)
	cmd.Dir = binDir
	setCmdHidden(cmd)

	if runtime.GOOS == "linux" {
		libPaths := []string{binDir}
		parentLib := filepath.Join(filepath.Dir(binDir), "lib")
		if fi, err := os.Stat(parentLib); err == nil && fi.IsDir() {
			libPaths = append(libPaths, parentLib)
		}
		if cur := os.Getenv("LD_LIBRARY_PATH"); cur != "" {
			libPaths = append(libPaths, cur)
		}
		cmd.Env = append(os.Environ(), "LD_LIBRARY_PATH="+strings.Join(libPaths, ":"))
	}

	stderr := &tailBuffer{max: 4096}
	cmd.Stderr = stderr
	cmd.Stdout = io.Discard

	m.setState("starting", "Starting local AI engine", 0, 0, "Loading local AI model…", "")
	if err := cmd.Start(); err != nil {
		m.setState("error", "", 0, 0, "", "Could not start llama-server: "+err.Error())
		return "", fmt.Errorf("could not start llama-server: %w", err)
	}

	doneCh := make(chan error, 1)
	go func() {
		doneCh <- cmd.Wait()
	}()

	baseURL := fmt.Sprintf("http://127.0.0.1:%d", port)
	healthClient := &http.Client{Timeout: time.Second}
	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			if cmd.Process != nil {
				_ = cmd.Process.Kill()
			}
			select {
			case <-doneCh:
			case <-time.After(2 * time.Second):
			}
			m.setState("idle", "", 0, 0, "", "")
			return "", ctx.Err()
		case werr := <-doneCh:
			msg := stderr.String()
			if msg == "" && werr != nil {
				msg = werr.Error()
			}
			m.setState("error", "", 0, 0, "", "Local AI exited: "+msg)
			return "", fmt.Errorf("llama-server exited during startup: %s", msg)
		default:
		}

		resp, err := healthClient.Get(baseURL + "/health")
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				m.mu.Lock()
				m.cmd = cmd
				m.doneCh = doneCh
				m.baseURL = baseURL
				m.runningModel = modelPath
				m.runningGPU = ngl > 0
				m.mu.Unlock()
				m.setState("running", "Local AI running", 0, 0, "Local AI engine active", "")
				return baseURL, nil
			}
		}
		if err := sleepCtx(ctx, 250*time.Millisecond); err != nil {
			if cmd.Process != nil {
				_ = cmd.Process.Kill()
			}
			select {
			case <-doneCh:
			case <-time.After(2 * time.Second):
			}
			m.setState("idle", "", 0, 0, "", "")
			return "", err
		}
	}

	if cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
	select {
	case <-doneCh:
	case <-time.After(2 * time.Second):
	}
	errMsg := "timed out waiting for llama-server to load model"
	if s := stderr.String(); s != "" {
		errMsg += ": " + s
	}
	m.setState("error", "", 0, 0, "", errMsg)
	return "", errors.New(errMsg)
}

func (m *LocalAIManager) Stop() {
	m.mu.Lock()
	cmd := m.cmd
	doneCh := m.doneCh
	wasDownloading := m.downloading
	m.cmd = nil
	m.doneCh = nil
	m.baseURL = ""
	m.runningModel = ""
	m.runningGPU = false
	m.mu.Unlock()

	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
		if doneCh != nil {
			select {
			case <-doneCh:
			case <-time.After(3 * time.Second):
			}
		}
	}
	if !wasDownloading {
		m.mu.Lock()
		if m.status.State == "running" || m.status.State == "starting" {
			m.status.State = "idle"
			m.status.Phase = ""
			m.status.Message = ""
		}
		m.mu.Unlock()
		m.emitStatus()
	}
}

func (m *LocalAIManager) Shutdown() {
	m.CancelDownload()
	m.Stop()
}
