package main

import (
	"embed"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/linux"
)

//go:embed all:frontend/dist
var assets embed.FS

func main() {
	ensureDesktopIntegration()
	app := NewApp()

	initW, initH := 1500, 920
	startState := options.Maximised
	if st, err := OpenStore(); err == nil {
		s := st.LoadSettings()
		if s.WindowWidth >= 900 && s.WindowHeight >= 600 {
			initW, initH = s.WindowWidth, s.WindowHeight
		}
		if !s.WindowMaximised {
			startState = options.Normal
		}
		_ = st.db.Close()
	}

	err := wails.Run(&options.App{
		Title:            "Vibe Music",
		Width:            initW,
		Height:           initH,
		Frameless:        true,
		WindowStartState: startState,
		AssetServer: &assetserver.Options{
			Assets:  assets,
			Handler: coverHandler{},
		},
		BackgroundColour: &options.RGBA{R: 10, G: 10, B: 14, A: 1},
		OnStartup:        app.startup,
		OnBeforeClose:    app.beforeClose,
		OnShutdown:       app.shutdown,
		SingleInstanceLock: &options.SingleInstanceLock{
			UniqueId:               "dev.vibemusic.app",
			OnSecondInstanceLaunch: func(options.SecondInstanceData) { app.ShowWindow() },
		},
		Linux: &linux.Options{
			Icon:             appIcon,
			ProgramName:      "vibe-music",
			WebviewGpuPolicy: linux.WebviewGpuPolicyOnDemand,
		},
		Bind: []interface{}{app},
	})
	if err != nil {
		println("Error:", err.Error())
	}
}
