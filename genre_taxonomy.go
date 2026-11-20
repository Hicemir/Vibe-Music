package main

import (
	"regexp"
	"sort"
	"strings"
)

// GenreNode is one genre in the taxonomy. Sub-genres name their parent ("Thrash Metal" -> "Metal" -> "Rock").
type GenreNode struct {
	Name    string   `json:"name"`
	Parent  string   `json:"parent"`
	Aliases []string `json:"-"`
}

// GenreLink says two genres are close cousins even though neither is an ancestor of the other.
type GenreLink struct {
	A string  `json:"a"`
	B string  `json:"b"`
	W float64 `json:"w"`
}

type GenreTaxonomy struct {
	Nodes   []GenreNode `json:"nodes"`
	Links   []GenreLink `json:"links"`
	Clashes []GenreLink `json:"clashes"`
}

func gn(name, parent string, aliases ...string) GenreNode {
	return GenreNode{Name: name, Parent: parent, Aliases: aliases}
}

var genreNodes = []GenreNode{
	// ---- Rock ----
	gn("Rock", "", "rock and roll", "rock n roll", "rock roll"),
	gn("Hard Rock", "Rock", "glam rock", "stoner rock", "heavy rock", "arena rock"),
	gn("Classic Rock", "Rock"),
	gn("Alternative Rock", "Rock", "alternative", "alt rock", "alternative and indie"),
	gn("Grunge", "Alternative Rock", "post grunge"),
	gn("Indie Rock", "Alternative Rock", "indie", "indie and alternative"),
	gn("Shoegaze", "Alternative Rock"),
	gn("Math Rock", "Alternative Rock", "midwest emo"),
	gn("Post-Rock", "Rock", "post rock"),
	gn("Britpop", "Rock"),
	gn("Progressive Rock", "Rock", "prog rock", "prog", "art rock"),
	gn("Psychedelic Rock", "Rock", "psychedelic", "psych rock"),
	gn("Garage Rock", "Rock"),
	gn("Soft Rock", "Rock"),
	gn("Folk Rock", "Rock"),
	gn("Blues Rock", "Rock"),
	gn("Punk", "Rock", "punk rock"),
	gn("Pop Punk", "Punk"),
	gn("Hardcore Punk", "Punk", "hardcore", "hardcore punk"),
	gn("Post-Punk", "Punk", "post punk"),
	gn("Emo", "Punk"),
	gn("J-Rock", "Rock", "j rock", "jrock", "japanese rock"),
	// ---- Metal ----
	gn("Metal", "Rock", "heavy metal and hard rock"),
	gn("Heavy Metal", "Metal", "classic metal", "nwobhm"),
	gn("Thrash Metal", "Metal", "thrash", "speed metal"),
	gn("Death Metal", "Metal", "deathcore", "melodic death metal"),
	gn("Black Metal", "Metal"),
	gn("Power Metal", "Metal"),
	gn("Doom Metal", "Metal", "doom", "sludge metal"),
	gn("Progressive Metal", "Metal", "prog metal"),
	gn("Metalcore", "Metal"),
	gn("Nu Metal", "Metal", "nu-metal", "alternative metal"),
	gn("Symphonic Metal", "Metal", "gothic metal"),
	gn("Groove Metal", "Metal"),
	gn("Industrial Metal", "Metal"),
	gn("Folk Metal", "Metal", "viking metal"),
	// ---- Pop ----
	gn("Pop", "", "pop music", "top 40", "adult contemporary", "contemporary pop", "teen pop"),
	gn("Synthpop", "Pop", "synth pop", "new wave", "electropop", "electro pop"),
	gn("Dance Pop", "Pop"),
	gn("Indie Pop", "Pop", "bedroom pop"),
	gn("Dream Pop", "Pop"),
	gn("Soft Pop", "Pop", "mellow pop", "chamber pop", "chill pop"),
	gn("Ballad", "Pop", "pop ballad", "power ballad", "slow ballad"),
	gn("Art Pop", "Pop"),
	gn("Hyperpop", "Pop", "glitchcore"),
	gn("Pop Rock", "Pop"),
	gn("K-Pop", "Pop", "korean pop", "kpop"),
	gn("J-Pop", "Pop", "japanese pop", "jpop"),
	gn("City Pop", "J-Pop", "japanese city pop"),
	gn("Vocaloid", "Pop", "hatsune miku", "utau", "synthv"),
	gn("Turkish Pop", "Pop", "turk pop", "türk pop", "anadolu pop", "anatolian pop"),
	gn("Disco", "Pop"),
	gn("Easy Listening", "Pop", "lounge"),
	// ---- Hip-Hop ----
	gn("Hip-Hop", "", "hiphop", "rap", "rapper", "hip hop rap", "rap hip hop"),
	gn("Trap", "Hip-Hop", "trap music"),
	gn("Drill", "Hip-Hop", "uk drill"),
	gn("Boom Bap", "Hip-Hop", "east coast hip hop", "old school hip hop"),
	gn("Gangsta Rap", "Hip-Hop", "west coast hip hop"),
	gn("Conscious Hip-Hop", "Hip-Hop", "alternative hip hop", "underground hip hop"),
	gn("Cloud Rap", "Hip-Hop", "emo rap"),
	gn("Grime", "Hip-Hop"),
	gn("Turkish Hip-Hop", "Hip-Hop", "turkish rap", "türk rap", "turk rap", "türkçe rap"),
	// ---- R&B / Soul ----
	gn("R&B", "", "rnb", "rhythm and blues", "contemporary r and b", "contemporary rnb"),
	gn("Soul", "R&B", "motown", "southern soul"),
	gn("Neo-Soul", "Soul", "neo soul"),
	gn("Funk", "R&B", "p funk"),
	// ---- Electronic ----
	gn("Electronic", "", "electronica", "electronic music", "electro"),
	gn("House", "Electronic", "deep house", "tech house", "progressive house", "electro house", "future house"),
	gn("Techno", "Electronic", "minimal techno"),
	gn("Trance", "Electronic", "psytrance", "progressive trance"),
	gn("Dubstep", "Electronic", "brostep", "riddim"),
	gn("Drum & Bass", "Electronic", "drum and bass", "dnb", "jungle", "liquid funk"),
	gn("Ambient", "Electronic", "dark ambient"),
	gn("Synthwave", "Electronic", "retrowave", "outrun", "darksynth"),
	gn("Vaporwave", "Electronic"),
	gn("EDM", "Electronic", "dance", "edm", "big room", "hardstyle"),
	gn("Future Bass", "Electronic", "kawaii future bass"),
	gn("Eurobeat", "Electronic", "super eurobeat", "initial d"),
	gn("Phonk", "Electronic", "drift phonk"),
	gn("Breakcore", "Electronic", "glitchbreak"),
	gn("Downtempo", "Electronic", "trip hop", "chillout", "chill out", "chill", "triphop"),
	gn("Lo-fi", "Electronic", "lofi", "lo fi", "chillhop", "lofi hip hop"),
	gn("IDM", "Electronic", "glitch", "experimental electronic"),
	gn("Eurodance", "Electronic", "europop", "italo disco"),
	gn("Industrial", "Electronic", "ebm", "industrial rock"),
	gn("Chiptune", "Electronic", "8 bit", "8bit"),
	// ---- Jazz / Blues ----
	gn("Jazz", "", "jazz music"),
	gn("Bebop", "Jazz", "hard bop"),
	gn("Cool Jazz", "Jazz"),
	gn("Smooth Jazz", "Jazz", "jazz pop"),
	gn("Swing", "Jazz", "big band"),
	gn("Jazz Fusion", "Jazz", "fusion"),
	gn("Vocal Jazz", "Jazz"),
	gn("Blues", "", "blues music"),
	gn("Delta Blues", "Blues", "country blues"),
	gn("Electric Blues", "Blues", "chicago blues"),
	// ---- Classical / Soundtrack ----
	gn("Classical", "", "classical music", "western classical"),
	gn("Orchestral", "Classical", "orchestra", "orchestral music", "symphonic", "symphony", "philharmonic"),
	gn("Piano", "Classical", "solo piano", "piano music", "piano solo"),
	gn("Opera", "Classical", "operatic"),
	gn("Baroque", "Classical", "renaissance", "early music"),
	gn("Chamber Music", "Classical", "chamber", "string quartet"),
	gn("Neoclassical", "Classical", "contemporary classical", "minimalism", "modern classical", "post classical"),
	gn("Choral", "Classical", "choir"),
	gn("Soundtrack", "", "ost", "original soundtrack", "soundtracks", "original score", "motion picture soundtrack", "films games", "film games", "films and games", "film and games", "movies games"),
	gn("Film Score", "Soundtrack", "score", "film", "movie", "motion picture", "cinematic", "trailer music"),
	gn("Video Game Music", "Soundtrack", "video game", "game music", "game soundtrack", "game ost", "vgm"),
	gn("Anime", "Soundtrack", "anime soundtrack", "anime ost", "anime music", "anison"),
	gn("Musical Theatre", "Soundtrack", "musical", "broadway", "show tunes", "musicals"),
	gn("TV Soundtrack", "Soundtrack", "tv", "television", "tv score"),
	// ---- Country / Folk ----
	gn("Country", "", "country music", "country pop", "outlaw country"),
	gn("Bluegrass", "Country"),
	gn("Americana", "Country", "alt country", "alternative country"),
	gn("Folk", "", "folk music"),
	gn("Singer-Songwriter", "Folk", "singer songwriter", "singersongwriter"),
	gn("Acoustic", "Folk"),
	gn("Indie Folk", "Folk", "folk pop"),
	gn("Turkish Folk", "Folk", "türkü", "turku", "türk halk", "turk halk", "halk müziği", "anatolian folk", "turk halk muzigi"),
	// ---- Reggae / Latin / World ----
	gn("Reggae", "", "roots reggae", "reggae music"),
	gn("Dancehall", "Reggae", "ragga"),
	gn("Ska", "Reggae", "ska punk", "two tone"),
	gn("Dub", "Reggae", "dub reggae"),
	gn("Latin", "", "latin music", "latino", "latin urban"),
	gn("Reggaeton", "Latin", "reggaetón", "urbano latino"),
	gn("Salsa", "Latin"),
	gn("Bachata", "Latin"),
	gn("Bossa Nova", "Latin", "bossa", "mpb", "brazilian"),
	gn("Cumbia", "Latin", "merengue"),
	gn("Flamenco", "Latin", "spanish guitar"),
	gn("Tango", "Latin"),
	gn("Latin Pop", "Latin"),
	gn("World", "", "world music", "worldbeat"),
	gn("Turkish", "World", "türk", "turk", "turkish music", "türk müziği", "turkce"),
	gn("Arabesk", "Turkish", "arabesque", "fantezi", "arabesk rap"),
	gn("Afrobeats", "World", "afrobeat", "afropop", "african", "afro"),
	gn("Celtic", "World", "irish folk", "celtic folk"),
	gn("Bollywood", "World", "indian", "filmi", "hindi"),
	gn("Middle Eastern", "World", "arabic", "arab", "persian", "oriental"),
	// ---- other ----
	gn("Children's", "", "children", "kids", "nursery rhymes", "nursery", "cocomelon", "childrens music"),
	gn("Lullaby", "Children's", "lullabies"),
	gn("Instrumental", "", "instrumentals", "instrumental music"),
	gn("Spoken Word", "", "podcast", "audiobook"),
}

var genreLinks = []GenreLink{
	{"Pop", "R&B", 0.35}, {"Pop", "Electronic", 0.3}, {"Pop", "Rock", 0.25}, {"Pop Rock", "Rock", 0.6},
	{"Hip-Hop", "R&B", 0.4}, {"Rock", "Blues", 0.35}, {"Jazz", "Blues", 0.4}, {"Jazz", "R&B", 0.3},
	{"Folk", "Country", 0.4}, {"Folk", "Rock", 0.25}, {"Indie Rock", "Indie Pop", 0.4},
	{"Classical", "Soundtrack", 0.4}, {"Orchestral", "Film Score", 0.5}, {"Neoclassical", "Film Score", 0.4},
	{"Piano", "Film Score", 0.3}, {"Orchestral", "Symphonic Metal", 0.3}, {"Symphonic Metal", "Power Metal", 0.4},
	{"Reggae", "Latin", 0.25}, {"Reggae", "Hip-Hop", 0.2}, {"Punk", "Metal", 0.3}, {"Hard Rock", "Metal", 0.6},
	{"Electronic", "Hip-Hop", 0.2}, {"Lo-fi", "Hip-Hop", 0.35}, {"Ambient", "Film Score", 0.2},
	{"Turkish Pop", "Turkish Folk", 0.3}, {"Arabesk", "Turkish Pop", 0.3}, {"Arabesk", "Middle Eastern", 0.3},
	{"Anime", "J-Pop", 0.4}, {"J-Rock", "J-Pop", 0.4}, {"J-Rock", "Anime", 0.5}, {"J-Rock", "Alternative Rock", 0.35}, {"Video Game Music", "Chiptune", 0.3}, {"Video Game Music", "Film Score", 0.5},
	{"Soft Pop", "Indie Pop", 0.45}, {"Soft Pop", "Dream Pop", 0.45}, {"Soft Pop", "Soft Rock", 0.4}, {"Soft Pop", "Acoustic", 0.35},
	{"Ballad", "Soft Pop", 0.45}, {"Ballad", "Piano", 0.35}, {"Ballad", "R&B", 0.25},
	{"City Pop", "Funk", 0.4}, {"City Pop", "Synthpop", 0.4},
	{"Vocaloid", "J-Pop", 0.5}, {"Vocaloid", "Anime", 0.4}, {"Vocaloid", "Electronic", 0.4}, {"Vocaloid", "J-Rock", 0.3},
	{"Math Rock", "J-Rock", 0.45}, {"Math Rock", "Post-Rock", 0.4},
	{"Eurobeat", "Anime", 0.35}, {"Eurobeat", "EDM", 0.4},
	{"Future Bass", "EDM", 0.5}, {"Phonk", "Hip-Hop", 0.4}, {"Breakcore", "Drum & Bass", 0.5},
	{"Disco", "Funk", 0.4}, {"Soul", "Jazz", 0.2}, {"Indie Folk", "Indie Rock", 0.3}, {"Folk Metal", "Folk", 0.35}, {"Instrumental", "Ambient", 0.1},
}

// genreClashes defines stylistically opposing genre pairs (propagated to children unless a positive link connects them).
var genreClashes = []GenreLink{
	// Metal vs soft / orchestral / acoustic / chill / children's
	{"Metal", "Classical", 0.85}, {"Metal", "Soft Pop", 0.85}, {"Metal", "Ballad", 0.80},
	{"Metal", "Easy Listening", 0.90}, {"Metal", "Acoustic", 0.80}, {"Metal", "Folk", 0.70},
	{"Metal", "Lo-fi", 0.85}, {"Metal", "Ambient", 0.80}, {"Metal", "Downtempo", 0.80},
	{"Metal", "Jazz", 0.75}, {"Metal", "Bossa Nova", 0.90}, {"Metal", "Children's", 0.95},
	{"Metal", "R&B", 0.70}, {"Metal", "K-Pop", 0.70}, {"Metal", "City Pop", 0.80}, {"Metal", "Soft Rock", 0.75},
	// Hard Rock vs soft / orchestral / ambient / children's
	{"Hard Rock", "Classical", 0.75}, {"Hard Rock", "Piano", 0.80}, {"Hard Rock", "Soft Pop", 0.75},
	{"Hard Rock", "Ballad", 0.65}, {"Hard Rock", "Lo-fi", 0.80}, {"Hard Rock", "Ambient", 0.80},
	{"Hard Rock", "Easy Listening", 0.85}, {"Hard Rock", "Children's", 0.95}, {"Hard Rock", "Bossa Nova", 0.85},
	// Punk & Grunge vs orchestral / easy listening / chill / children's
	{"Punk", "Classical", 0.85}, {"Punk", "Piano", 0.85}, {"Punk", "Ballad", 0.75},
	{"Punk", "Soft Pop", 0.70}, {"Punk", "Ambient", 0.85}, {"Punk", "Lo-fi", 0.80},
	{"Punk", "Easy Listening", 0.90}, {"Punk", "Jazz", 0.75}, {"Punk", "Children's", 0.95},
	{"Grunge", "Classical", 0.75}, {"Grunge", "Piano", 0.75}, {"Grunge", "Soft Pop", 0.75},
	{"Grunge", "Easy Listening", 0.85}, {"Grunge", "Children's", 0.95},
	// General Rock vs classical / piano / easy listening / children's
	{"Rock", "Classical", 0.60}, {"Rock", "Piano", 0.60}, {"Rock", "Ballad", 0.40},
	{"Rock", "Easy Listening", 0.70}, {"Rock", "Children's", 0.90},
	// Industrial vs gentle / acoustic / jazz / children's
	{"Industrial", "Classical", 0.85}, {"Industrial", "Piano", 0.85}, {"Industrial", "Soft Pop", 0.85},
	{"Industrial", "Ballad", 0.85}, {"Industrial", "Folk", 0.85}, {"Industrial", "Jazz", 0.80}, {"Industrial", "Children's", 0.95},
	// Aggressive / high-energy electronic vs classical / acoustic / ballad / children's
	{"Dubstep", "Classical", 0.80}, {"Dubstep", "Piano", 0.80}, {"Dubstep", "Ballad", 0.80},
	{"Dubstep", "Soft Pop", 0.75}, {"Dubstep", "Acoustic", 0.80}, {"Dubstep", "Folk", 0.75},
	{"Dubstep", "Jazz", 0.70}, {"Dubstep", "Children's", 0.95},
	{"Breakcore", "Classical", 0.80}, {"Breakcore", "Piano", 0.80}, {"Breakcore", "Ballad", 0.80},
	{"Breakcore", "Soft Pop", 0.75}, {"Breakcore", "Acoustic", 0.80}, {"Breakcore", "Folk", 0.75},
	{"Breakcore", "Jazz", 0.70}, {"Breakcore", "Children's", 0.95},
	{"Phonk", "Classical", 0.80}, {"Phonk", "Piano", 0.80}, {"Phonk", "Ballad", 0.80},
	{"Phonk", "Soft Pop", 0.75}, {"Phonk", "Acoustic", 0.80}, {"Phonk", "Folk", 0.75},
	{"Phonk", "Jazz", 0.70}, {"Phonk", "Children's", 0.95},
	{"Techno", "Classical", 0.80}, {"Techno", "Piano", 0.80}, {"Techno", "Ballad", 0.80},
	{"Techno", "Soft Pop", 0.75}, {"Techno", "Acoustic", 0.80}, {"Techno", "Folk", 0.75},
	{"Techno", "Jazz", 0.70}, {"Techno", "Children's", 0.95},
	{"EDM", "Classical", 0.80}, {"EDM", "Piano", 0.80}, {"EDM", "Ballad", 0.80},
	{"EDM", "Soft Pop", 0.75}, {"EDM", "Acoustic", 0.80}, {"EDM", "Folk", 0.75},
	{"EDM", "Jazz", 0.70}, {"EDM", "Children's", 0.95},
	{"Drum & Bass", "Classical", 0.80}, {"Drum & Bass", "Piano", 0.80}, {"Drum & Bass", "Ballad", 0.80},
	{"Drum & Bass", "Soft Pop", 0.75}, {"Drum & Bass", "Acoustic", 0.80}, {"Drum & Bass", "Folk", 0.75},
	{"Drum & Bass", "Jazz", 0.70}, {"Drum & Bass", "Children's", 0.95},
	{"Hyperpop", "Classical", 0.80}, {"Hyperpop", "Piano", 0.80}, {"Hyperpop", "Ballad", 0.80},
	{"Hyperpop", "Soft Pop", 0.75}, {"Hyperpop", "Acoustic", 0.80}, {"Hyperpop", "Folk", 0.75},
	{"Hyperpop", "Jazz", 0.70}, {"Hyperpop", "Children's", 0.95},
	{"Eurobeat", "Classical", 0.80}, {"Eurobeat", "Piano", 0.80}, {"Eurobeat", "Ballad", 0.80},
	{"Eurobeat", "Soft Pop", 0.75}, {"Eurobeat", "Acoustic", 0.80}, {"Eurobeat", "Folk", 0.75},
	{"Eurobeat", "Jazz", 0.70}, {"Eurobeat", "Children's", 0.95},
	// Hard Hip-Hop & Hip-Hop vs classical / folk / country / children's
	{"Trap", "Classical", 0.85}, {"Trap", "Piano", 0.80}, {"Trap", "Folk", 0.85}, {"Trap", "Country", 0.85},
	{"Trap", "Soft Pop", 0.70}, {"Trap", "Ballad", 0.75}, {"Trap", "Children's", 0.95}, {"Trap", "Metal", 0.65},
	{"Drill", "Classical", 0.85}, {"Drill", "Piano", 0.80}, {"Drill", "Folk", 0.85}, {"Drill", "Country", 0.85},
	{"Drill", "Soft Pop", 0.70}, {"Drill", "Ballad", 0.75}, {"Drill", "Children's", 0.95}, {"Drill", "Metal", 0.65},
	{"Gangsta Rap", "Classical", 0.85}, {"Gangsta Rap", "Piano", 0.80}, {"Gangsta Rap", "Folk", 0.85}, {"Gangsta Rap", "Country", 0.85},
	{"Gangsta Rap", "Soft Pop", 0.70}, {"Gangsta Rap", "Ballad", 0.75}, {"Gangsta Rap", "Children's", 0.95}, {"Gangsta Rap", "Metal", 0.65},
	{"Grime", "Classical", 0.85}, {"Grime", "Piano", 0.80}, {"Grime", "Folk", 0.85}, {"Grime", "Country", 0.85},
	{"Grime", "Soft Pop", 0.70}, {"Grime", "Ballad", 0.75}, {"Grime", "Children's", 0.95}, {"Grime", "Metal", 0.65},
	{"Hip-Hop", "Classical", 0.70}, {"Hip-Hop", "Folk", 0.70}, {"Hip-Hop", "Country", 0.75}, {"Hip-Hop", "Children's", 0.90},
	// Children's & Spoken Word separations
	{"Children's", "Electronic", 0.80}, {"Children's", "Blues", 0.75}, {"Children's", "Spoken Word", 0.70},
	{"Spoken Word", "Metal", 0.90}, {"Spoken Word", "EDM", 0.90}, {"Spoken Word", "Rock", 0.75},
	{"Spoken Word", "Pop", 0.75}, {"Spoken Word", "Classical", 0.70},
}

func getGenreTaxonomy() GenreTaxonomy {
	return GenreTaxonomy{Nodes: genreNodes, Links: genreLinks, Clashes: genreClashes}
}

var (
	genreParent  = map[string]string{}
	genreKnown   = map[string]bool{}
	genreByLower = map[string]string{}
	aliasList    []aliasEntry
)

type aliasEntry struct {
	phrase string
	node   string
	words  int
}

func init() {
	seen := map[string]bool{}
	for _, n := range genreNodes {
		genreKnown[n.Name] = true
		genreByLower[strings.ToLower(n.Name)] = n.Name
		genreParent[n.Name] = n.Parent
		for _, a := range append([]string{n.Name}, n.Aliases...) {
			p := normTag(a)
			if p == "" || seen[p+"|"+n.Name] {
				continue
			}
			seen[p+"|"+n.Name] = true
			aliasList = append(aliasList, aliasEntry{p, n.Name, len(strings.Fields(p))})
		}
	}
	// most specific (longest) phrases are tried first so "thrash metal" beats "metal" and "pop punk" beats "pop"
	sort.SliceStable(aliasList, func(i, j int) bool {
		if aliasList[i].words != aliasList[j].words {
			return aliasList[i].words > aliasList[j].words
		}
		return len(aliasList[i].phrase) > len(aliasList[j].phrase)
	})
}

var tagReplacer = strings.NewReplacer("-", " ", "_", " ", ".", " ", "/", " ", ",", " ", "'", "", "’", "", "&", " and ", "(", " ", ")", " ", ":", " ")

func normTag(s string) string {
	return strings.Join(strings.Fields(tagReplacer.Replace(strings.ToLower(strings.TrimSpace(s)))), " ")
}

// mapTag maps one free-form tag onto a taxonomy node ("" when it is not a genre we know).
func mapTag(raw string) string {
	s := normTag(raw)
	if s == "" {
		return ""
	}
	padded := " " + s + " "
	for _, a := range aliasList {
		if s == a.phrase || strings.Contains(padded, " "+a.phrase+" ") {
			return a.node
		}
	}
	return ""
}

// canonicalGenreName resolves an exact, case-insensitive, or alias-matched taxonomy node name.
func canonicalGenreName(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if genreKnown[s] {
		return s
	}
	if c, ok := genreByLower[strings.ToLower(s)]; ok {
		return c
	}
	return mapTag(s)
}

var tagSplitRe = regexp.MustCompile(`[;|/\x00]+`)

// tagGenres maps a raw file tag (possibly "Rock; Metal") to taxonomy nodes.
func tagGenres(raw string) []string {
	var out []string
	for _, part := range tagSplitRe.Split(raw, -1) {
		if g := mapTag(part); g != "" {
			out = append(out, g)
		}
	}
	return dedupeGenres(out, 4)
}

func isAncestorGenre(anc, node string) bool {
	for p := genreParent[node]; p != ""; p = genreParent[p] {
		if p == anc {
			return true
		}
	}
	return false
}

// dedupeGenres removes duplicates and any genre that is an ancestor of another listed one
// (Metal is implied by Thrash Metal), keeping the original order and at most max entries.
func dedupeGenres(list []string, max int) []string {
	var uniq []string
	seen := map[string]bool{}
	for _, g := range list {
		g = strings.TrimSpace(g)
		if g != "" && !seen[g] {
			seen[g] = true
			uniq = append(uniq, g)
		}
	}
	var out []string
	for _, g := range uniq {
		implied := false
		for _, o := range uniq {
			if o != g && isAncestorGenre(g, o) {
				implied = true
				break
			}
		}
		if !implied {
			out = append(out, g)
		}
	}
	if max > 0 && len(out) > max {
		out = out[:max]
	}
	return out
}

func joinGenres(list []string) string { return strings.Join(list, "|") }

func splitGenres(s string) []string {
	var out []string
	for _, p := range strings.Split(s, "|") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// cleanUserGenres normalises hand-entered genres: known ones map to the taxonomy, custom text is kept.
func cleanUserGenres(list []string) []string {
	var out []string
	for _, raw := range list {
		raw = strings.TrimSpace(raw)
		if raw == "" {
			continue
		}
		if genreKnown[raw] {
			out = append(out, raw)
			continue
		}
		if g := mapTag(raw); g != "" {
			out = append(out, g)
			continue
		}
		if len(raw) > 40 {
			raw = raw[:40]
		}
		out = append(out, raw)
	}
	return dedupeGenres(out, 6)
}

var (
	soundtrackRe = regexp.MustCompile(`(?i)\b(original (motion picture |game |series |tv |film )?(soundtrack|score)|motion picture soundtrack|ost|o\.s\.t|gekiban\s*ongakushu)\b|サウンドトラック|劇伴`)
	childrenRe   = regexp.MustCompile(`(?i)\b(lullab(y|ies)|nursery rhymes?|cocomelon|kids songs?|children'?s songs?)\b`)
	animeRe      = regexp.MustCompile(`(?i)\b(anime|anison|opening\s+theme|ending\s+theme|tv[\s-]size|op\s+theme|ed\s+theme|character\s+song|insert\s+song)\b|アニメ`)
	vgmRe        = regexp.MustCompile(`(?i)\b(video\s*game|original\s+game\s+(soundtrack|score)|game\s+(soundtrack|ost|music)|vgm|boss\s+theme|battle\s+theme)\b`)
	chiptuneRe   = regexp.MustCompile(`(?i)\b(chiptune|8[\s-]?bit|16[\s-]?bit)\b`)
	lofiRe       = regexp.MustCompile(`(?i)\b(lo[\s-]?fi|chillhop)\b`)
	pianoRe      = regexp.MustCompile(`(?i)\b(piano\s+(ver\.?|version|cover|solo|arrangement|collection)|solo\s+piano)\b`)
	acousticRe   = regexp.MustCompile(`(?i)\b(acoustic\s+(ver\.?|version|cover|live|session)|unplugged)\b`)
	orchestralRe = regexp.MustCompile(`(?i)\b(orchestral\s+(ver\.?|version|arrangement|suite)|philharmonic\s+orchestra)\b`)
)

// heuristicGenres derives extra genres from the title/album/artist text (e.g. "... Original Soundtrack").
func heuristicGenres(t Track) []string {
	var out []string
	text := t.Album + " " + t.Title
	if soundtrackRe.MatchString(text) {
		out = append(out, "Soundtrack")
	}
	if animeRe.MatchString(text) {
		out = append(out, "Anime")
	}
	if vgmRe.MatchString(text) {
		out = append(out, "Video Game Music")
	}
	if chiptuneRe.MatchString(text) {
		out = append(out, "Chiptune")
	}
	if lofiRe.MatchString(text) {
		out = append(out, "Lo-fi")
	}
	if pianoRe.MatchString(text) {
		out = append(out, "Piano")
	}
	if acousticRe.MatchString(text) {
		out = append(out, "Acoustic")
	}
	if orchestralRe.MatchString(text) {
		out = append(out, "Orchestral")
	}
	if childrenRe.MatchString(text + " " + t.Artist) {
		out = append(out, "Children's")
	}
	return dedupeGenres(out, 5)
}
