package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestMapTag(t *testing.T) {
	cases := map[string]string{
		"Heavy Metal":         "Heavy Metal",
		"thrash metal":        "Thrash Metal",
		"metal":               "Metal",
		"Rap/Hip Hop":         "Hip-Hop",
		"hip-hop":             "Hip-Hop",
		"alternative rock":    "Alternative Rock",
		"pop punk":            "Pop Punk",
		"K-Pop":               "K-Pop",
		"Children's Music":    "Children's",
		"Dancehall":           "Dancehall",
		"Dubstep":             "Dubstep",
		"Post-Rock":           "Post-Rock",
		"R&B/Soul": "R&B",
		"Drum & Bass":         "Drum & Bass",
		"orchestral":          "Orchestral",
		"anime":               "Anime",
		"anime soundtrack":    "Anime",
		"korean":              "",
		"seen live":           "",
		"american":            "",
		"most played":         "",
		"Original Soundtrack": "Soundtrack",
		"film score":          "Film Score",
		"alternative hip hop": "Conscious Hip-Hop",
		"turkish pop":         "Turkish Pop",
		"neo-soul":            "Neo-Soul",
		"Films/Games":         "Soundtrack",
		"j-rock":              "J-Rock",
		"jrock":               "J-Rock",
		"mellow pop":          "Soft Pop",
		"pop ballad":          "Ballad",
		"japanese city pop":   "City Pop",
		"hatsune miku":        "Vocaloid",
		"math rock":           "Math Rock",
		"super eurobeat":      "Eurobeat",
		"future bass":         "Future Bass",
		"drift phonk":         "Phonk",
		"breakcore":           "Breakcore",
	}
	for in, want := range cases {
		if got := mapTag(in); got != want {
			t.Errorf("mapTag(%q) = %q, want %q", in, got, want)
		}
	}
	// sub-genres imply their parents, so only the most specific one is kept
	got := dedupeGenres([]string{"Rock", "Metal", "Thrash Metal", "Soundtrack"}, 4)
	if joinGenres(got) != "Thrash Metal|Soundtrack" {
		t.Errorf("dedupeGenres = %v", got)
	}
	if g := dedupeGenres([]string{"Rock", "J-Rock", "Soft Rock"}, 5); joinGenres(g) != "J-Rock|Soft Rock" {
		t.Errorf("ancestor strip Rock = %v", g)
	}
	if g := dedupeGenres([]string{"Pop", "Soft Pop", "City Pop", "J-Pop"}, 5); joinGenres(g) != "Soft Pop|City Pop" {
		t.Errorf("ancestor strip Pop/J-Pop = %v", g)
	}
	if g := tagGenres("R&B/Soul"); joinGenres(g) != "Soul" {
		t.Errorf("tagGenres R&B/Soul = %v", g)
	}
	if g := tagGenres("Metal; Soundtrack"); joinGenres(g) != "Metal|Soundtrack" {
		t.Errorf("tagGenres = %v", g)
	}
	tags := []mbTag{{-1, "political"}, {1, "rock"}, {41, "heavy metal"}, {67, "thrash metal"}, {34, "metal"}, {7, "american"}, {-6, "seen live"}}
	if g := pickTags(tags, 4); joinGenres(g) != "Thrash Metal|Heavy Metal" {
		t.Errorf("pickTags = %v", g)
	}
	if g := heuristicGenres(Track{Album: "Interstellar (Original Motion Picture Soundtrack)"}); len(g) != 1 || g[0] != "Soundtrack" {
		t.Errorf("heuristic = %v", g)
	}
	if g := heuristicGenres(Track{Title: "Opening Theme (TV-Size)", Album: "Bocchi Anime OST"}); joinGenres(g) != "Anime" {
		t.Errorf("anime heuristic = %v", g)
	}
	if g := heuristicGenres(Track{Title: "Boss Battle Theme", Album: "Hollow Knight Original Game Soundtrack"}); joinGenres(g) != "Video Game Music" {
		t.Errorf("vgm heuristic = %v", g)
	}
	if g := heuristicGenres(Track{Title: "Retro 8-bit Adventure"}); joinGenres(g) != "Chiptune" {
		t.Errorf("chiptune heuristic = %v", g)
	}
	if g := heuristicGenres(Track{Title: "Rainy Night Lo-fi Mix"}); joinGenres(g) != "Lo-fi" {
		t.Errorf("lofi heuristic = %v", g)
	}
	if g := heuristicGenres(Track{Title: "Song (Piano Version)"}); joinGenres(g) != "Piano" {
		t.Errorf("piano heuristic = %v", g)
	}
	if g := heuristicGenres(Track{Title: "Song (Acoustic Ver.)"}); joinGenres(g) != "Acoustic" {
		t.Errorf("acoustic heuristic = %v", g)
	}
	if g := heuristicGenres(Track{Title: "Finale (Orchestral Arrangement)"}); joinGenres(g) != "Orchestral" {
		t.Errorf("orchestral heuristic = %v", g)
	}
}

func TestMergeGenreSources(t *testing.T) {
	// Exact corroboration ranks first, then remaining MB, then remaining Deezer
	got := mergeGenreSources([]string{"Jazz", "Ambient"}, []string{"Ambient", "Pop"}, 5)
	if joinGenres(got) != "Ambient|Jazz|Pop" {
		t.Errorf("merge exact = %v", got)
	}
	// Specific sub-genre beats parent across sources and ranks first
	got = mergeGenreSources([]string{"Pop", "J-Rock"}, []string{"Rock", "Anime"}, 5)
	if joinGenres(got) != "J-Rock|Pop|Anime" {
		t.Errorf("merge subgenre MB>DZ = %v", got)
	}
	got = mergeGenreSources([]string{"Rock"}, []string{"J-Rock", "Pop"}, 5)
	if joinGenres(got) != "J-Rock|Pop" {
		t.Errorf("merge subgenre DZ>MB = %v", got)
	}
	// Max limit respected
	got = mergeGenreSources([]string{"Jazz", "Blues", "Folk"}, []string{"Pop", "Electronic"}, 3)
	if len(got) != 3 {
		t.Errorf("merge max limit len=%d (%v)", len(got), got)
	}
}

func TestGenreRoundTripAndMove(t *testing.T) {
	data := t.TempDir()
	t.Setenv("XDG_DATA_HOME", data)
	st, err := OpenStore()
	if err != nil {
		t.Fatal(err)
	}
	a := &App{store: st, tracks: map[string]Track{}}
	a.scanner = NewScanner(st, func(string, ...any) {})

	src := filepath.Join(data, "src")
	dst := filepath.Join(data, "dst")
	_ = os.MkdirAll(filepath.Join(src, "Album"), 0o755)
	f := filepath.Join(src, "Album", "x.mp3")
	_ = os.WriteFile(f, []byte("hello audio"), 0o644)
	_ = os.WriteFile(filepath.Join(src, "Album", "x.lrc"), []byte("[00:01.00]hi"), 0o644)

	key := st.EnsureRoot(src)
	if _, err := st.UpsertTrack(Track{ID: "local:abc", RootKey: key, RelPath: "Album/x.mp3", Title: "x", Artist: "A", Genre: "Metal", Size: 11}); err != nil {
		t.Fatal(err)
	}
	// a re-scan without a genre must not erase the stored one
	if _, err := st.UpsertTrack(Track{ID: "local:abc", RootKey: key, RelPath: "Album/x.mp3", Title: "x", Artist: "A", Size: 11}); err != nil {
		t.Fatal(err)
	}
	tr, _ := st.TrackByID("local:abc")
	if tr.Genre != "Metal" || tr.GenreSource != "tag" {
		t.Fatalf("genre lost: %+v", tr)
	}
	// manual genre is never overwritten by a tag
	_ = st.SetTracksGenre([]string{"local:abc"}, "Jazz", "user")
	_, _ = st.UpsertTrack(Track{ID: "local:abc", RootKey: key, RelPath: "Album/x.mp3", Title: "x", Artist: "A", Genre: "Rock", Size: 11})
	tr, _ = st.TrackByID("local:abc")
	if tr.Genre != "Jazz" {
		t.Fatalf("user genre overwritten: %+v", tr)
	}

	a.moveMusic([]string{src}, dst)
	tr, _ = st.TrackByID("local:abc")
	want := filepath.Join(dst, "Album", "x.mp3")
	if tr.Path != want {
		t.Fatalf("path = %q want %q", tr.Path, want)
	}
	if !fileExists(want) || fileExists(f) || !fileExists(filepath.Join(dst, "Album", "x.lrc")) {
		t.Fatal("files not moved")
	}
	if tr.Genre != "Jazz" {
		t.Fatal("track state lost on move")
	}
}

func TestGenreTaxonomyClashesValid(t *testing.T) {
	for _, l := range genreLinks {
		if !genreKnown[l.A] || !genreKnown[l.B] {
			t.Errorf("unknown genre in genreLinks: %+v", l)
		}
		if l.W <= 0 || l.W > 1 {
			t.Errorf("invalid link weight in genreLinks: %+v", l)
		}
	}
	hasMetalClassical := false
	hasHardRockPiano := false
	for _, c := range genreClashes {
		if !genreKnown[c.A] || !genreKnown[c.B] {
			t.Errorf("unknown genre in genreClashes: %+v", c)
		}
		if c.W <= 0 || c.W > 1 {
			t.Errorf("invalid clash weight in genreClashes: %+v", c)
		}
		if (c.A == "Metal" && c.B == "Classical") || (c.A == "Classical" && c.B == "Metal") {
			hasMetalClassical = true
		}
		if (c.A == "Hard Rock" && c.B == "Piano") || (c.A == "Piano" && c.B == "Hard Rock") {
			hasHardRockPiano = true
		}
	}
	if !hasMetalClassical || !hasHardRockPiano {
		t.Fatalf("missing key clashes: metalClassical=%v hardRockPiano=%v", hasMetalClassical, hasHardRockPiano)
	}
}
