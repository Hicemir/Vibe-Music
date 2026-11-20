package com.vibemusic.nativeapp.genre

import com.vibemusic.nativeapp.data.CanonicalKey
import com.vibemusic.nativeapp.data.Track
import kotlin.math.max

data class GenreNode(
    val name: String,
    val parent: String,
    val aliases: List<String> = emptyList()
)

data class GenreLink(
    val a: String,
    val b: String,
    val w: Double
)

enum class GenreRelation {
    SAME,
    RELATED,
    UNKNOWN,
    CLASH
}

object GenreTaxonomy {

    private fun gn(name: String, parent: String, vararg aliases: String) =
        GenreNode(name = name, parent = parent, aliases = aliases.toList())

    val nodes: List<GenreNode> = listOf(
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
        // ---- Other ----
        gn("Children's", "", "children", "kids", "nursery rhymes", "nursery", "cocomelon", "childrens music"),
        gn("Lullaby", "Children's", "lullabies"),
        gn("Instrumental", "", "instrumentals", "instrumental music"),
        gn("Spoken Word", "", "podcast", "audiobook")
    )

    val links: List<GenreLink> = listOf(
        GenreLink("Pop", "R&B", 0.35), GenreLink("Pop", "Electronic", 0.3), GenreLink("Pop", "Rock", 0.25), GenreLink("Pop Rock", "Rock", 0.6),
        GenreLink("Hip-Hop", "R&B", 0.4), GenreLink("Rock", "Blues", 0.35), GenreLink("Jazz", "Blues", 0.4), GenreLink("Jazz", "R&B", 0.3),
        GenreLink("Folk", "Country", 0.4), GenreLink("Folk", "Rock", 0.25), GenreLink("Indie Rock", "Indie Pop", 0.4),
        GenreLink("Classical", "Soundtrack", 0.4), GenreLink("Orchestral", "Film Score", 0.5), GenreLink("Neoclassical", "Film Score", 0.4),
        GenreLink("Piano", "Film Score", 0.3), GenreLink("Orchestral", "Symphonic Metal", 0.3), GenreLink("Symphonic Metal", "Power Metal", 0.4),
        GenreLink("Reggae", "Latin", 0.25), GenreLink("Reggae", "Hip-Hop", 0.2), GenreLink("Punk", "Metal", 0.3), GenreLink("Hard Rock", "Metal", 0.6),
        GenreLink("Electronic", "Hip-Hop", 0.2), GenreLink("Lo-fi", "Hip-Hop", 0.35), GenreLink("Ambient", "Film Score", 0.2),
        GenreLink("Turkish Pop", "Turkish Folk", 0.3), GenreLink("Arabesk", "Turkish Pop", 0.3), GenreLink("Arabesk", "Middle Eastern", 0.3),
        GenreLink("Anime", "J-Pop", 0.4), GenreLink("J-Rock", "J-Pop", 0.4), GenreLink("J-Rock", "Anime", 0.5), GenreLink("J-Rock", "Alternative Rock", 0.35),
        GenreLink("Video Game Music", "Chiptune", 0.3), GenreLink("Video Game Music", "Film Score", 0.5),
        GenreLink("Soft Pop", "Indie Pop", 0.45), GenreLink("Soft Pop", "Dream Pop", 0.45), GenreLink("Soft Pop", "Soft Rock", 0.4), GenreLink("Soft Pop", "Acoustic", 0.35),
        GenreLink("Ballad", "Soft Pop", 0.45), GenreLink("Ballad", "Piano", 0.35), GenreLink("Ballad", "R&B", 0.25),
        GenreLink("City Pop", "Funk", 0.4), GenreLink("City Pop", "Synthpop", 0.4),
        GenreLink("Vocaloid", "J-Pop", 0.5), GenreLink("Vocaloid", "Anime", 0.4), GenreLink("Vocaloid", "Electronic", 0.4), GenreLink("Vocaloid", "J-Rock", 0.3),
        GenreLink("Math Rock", "J-Rock", 0.45), GenreLink("Math Rock", "Post-Rock", 0.4),
        GenreLink("Eurobeat", "Anime", 0.35), GenreLink("Eurobeat", "EDM", 0.4),
        GenreLink("Future Bass", "EDM", 0.5), GenreLink("Phonk", "Hip-Hop", 0.4), GenreLink("Breakcore", "Drum & Bass", 0.5),
        GenreLink("Disco", "Funk", 0.4), GenreLink("Soul", "Jazz", 0.2), GenreLink("Indie Folk", "Indie Rock", 0.3), GenreLink("Folk Metal", "Folk", 0.35), GenreLink("Instrumental", "Ambient", 0.1)
    )

    val clashes: List<GenreLink> = listOf(
        // Metal vs soft / orchestral / acoustic / chill / children's
        GenreLink("Metal", "Classical", 0.85), GenreLink("Metal", "Soft Pop", 0.85), GenreLink("Metal", "Ballad", 0.80),
        GenreLink("Metal", "Easy Listening", 0.90), GenreLink("Metal", "Acoustic", 0.80), GenreLink("Metal", "Folk", 0.70),
        GenreLink("Metal", "Lo-fi", 0.85), GenreLink("Metal", "Ambient", 0.80), GenreLink("Metal", "Downtempo", 0.80),
        GenreLink("Metal", "Jazz", 0.75), GenreLink("Metal", "Bossa Nova", 0.90), GenreLink("Metal", "Children's", 0.95),
        GenreLink("Metal", "R&B", 0.70), GenreLink("Metal", "K-Pop", 0.70), GenreLink("Metal", "City Pop", 0.80), GenreLink("Metal", "Soft Rock", 0.75),
        // Hard Rock vs soft / orchestral / ambient / children's
        GenreLink("Hard Rock", "Classical", 0.75), GenreLink("Hard Rock", "Piano", 0.80), GenreLink("Hard Rock", "Soft Pop", 0.75),
        GenreLink("Hard Rock", "Ballad", 0.65), GenreLink("Hard Rock", "Lo-fi", 0.80), GenreLink("Hard Rock", "Ambient", 0.80),
        GenreLink("Hard Rock", "Easy Listening", 0.85), GenreLink("Hard Rock", "Children's", 0.95), GenreLink("Hard Rock", "Bossa Nova", 0.85),
        // Punk & Grunge vs orchestral / easy listening / chill / children's
        GenreLink("Punk", "Classical", 0.85), GenreLink("Punk", "Piano", 0.85), GenreLink("Punk", "Ballad", 0.75),
        GenreLink("Punk", "Soft Pop", 0.70), GenreLink("Punk", "Ambient", 0.85), GenreLink("Punk", "Lo-fi", 0.80),
        GenreLink("Punk", "Easy Listening", 0.90), GenreLink("Punk", "Jazz", 0.75), GenreLink("Punk", "Children's", 0.95),
        GenreLink("Grunge", "Classical", 0.75), GenreLink("Grunge", "Piano", 0.75), GenreLink("Grunge", "Soft Pop", 0.75),
        GenreLink("Grunge", "Easy Listening", 0.85), GenreLink("Grunge", "Children's", 0.95),
        // General Rock vs classical / piano / easy listening / children's
        GenreLink("Rock", "Classical", 0.60), GenreLink("Rock", "Piano", 0.60), GenreLink("Rock", "Ballad", 0.40),
        GenreLink("Rock", "Easy Listening", 0.70), GenreLink("Rock", "Children's", 0.90),
        // Industrial vs gentle / acoustic / jazz / children's
        GenreLink("Industrial", "Classical", 0.85), GenreLink("Industrial", "Piano", 0.85), GenreLink("Industrial", "Soft Pop", 0.85),
        GenreLink("Industrial", "Ballad", 0.85), GenreLink("Industrial", "Folk", 0.85), GenreLink("Industrial", "Jazz", 0.80), GenreLink("Industrial", "Children's", 0.95),
        // Aggressive / high-energy electronic vs classical / acoustic / ballad / children's
        GenreLink("Dubstep", "Classical", 0.80), GenreLink("Dubstep", "Piano", 0.80), GenreLink("Dubstep", "Ballad", 0.80),
        GenreLink("Dubstep", "Soft Pop", 0.75), GenreLink("Dubstep", "Acoustic", 0.80), GenreLink("Dubstep", "Folk", 0.75),
        GenreLink("Dubstep", "Jazz", 0.70), GenreLink("Dubstep", "Children's", 0.95),
        GenreLink("Breakcore", "Classical", 0.80), GenreLink("Breakcore", "Piano", 0.80), GenreLink("Breakcore", "Ballad", 0.80),
        GenreLink("Breakcore", "Soft Pop", 0.75), GenreLink("Breakcore", "Acoustic", 0.80), GenreLink("Breakcore", "Folk", 0.75),
        GenreLink("Breakcore", "Jazz", 0.70), GenreLink("Breakcore", "Children's", 0.95),
        GenreLink("Phonk", "Classical", 0.80), GenreLink("Phonk", "Piano", 0.80), GenreLink("Phonk", "Ballad", 0.80),
        GenreLink("Phonk", "Soft Pop", 0.75), GenreLink("Phonk", "Acoustic", 0.80), GenreLink("Phonk", "Folk", 0.75),
        GenreLink("Phonk", "Jazz", 0.70), GenreLink("Phonk", "Children's", 0.95),
        GenreLink("Techno", "Classical", 0.80), GenreLink("Techno", "Piano", 0.80), GenreLink("Techno", "Ballad", 0.80),
        GenreLink("Techno", "Soft Pop", 0.75), GenreLink("Techno", "Acoustic", 0.80), GenreLink("Techno", "Folk", 0.75),
        GenreLink("Techno", "Jazz", 0.70), GenreLink("Techno", "Children's", 0.95),
        GenreLink("EDM", "Classical", 0.80), GenreLink("EDM", "Piano", 0.80), GenreLink("EDM", "Ballad", 0.80),
        GenreLink("EDM", "Soft Pop", 0.75), GenreLink("EDM", "Acoustic", 0.80), GenreLink("EDM", "Folk", 0.75),
        GenreLink("EDM", "Jazz", 0.70), GenreLink("EDM", "Children's", 0.95),
        GenreLink("Drum & Bass", "Classical", 0.80), GenreLink("Drum & Bass", "Piano", 0.80), GenreLink("Drum & Bass", "Ballad", 0.80),
        GenreLink("Drum & Bass", "Soft Pop", 0.75), GenreLink("Drum & Bass", "Acoustic", 0.80), GenreLink("Drum & Bass", "Folk", 0.75),
        GenreLink("Drum & Bass", "Jazz", 0.70), GenreLink("Drum & Bass", "Children's", 0.95),
        GenreLink("Hyperpop", "Classical", 0.80), GenreLink("Hyperpop", "Piano", 0.80), GenreLink("Hyperpop", "Ballad", 0.80),
        GenreLink("Hyperpop", "Soft Pop", 0.75), GenreLink("Hyperpop", "Acoustic", 0.80), GenreLink("Hyperpop", "Folk", 0.75),
        GenreLink("Hyperpop", "Jazz", 0.70), GenreLink("Hyperpop", "Children's", 0.95),
        GenreLink("Eurobeat", "Classical", 0.80), GenreLink("Eurobeat", "Piano", 0.80), GenreLink("Eurobeat", "Ballad", 0.80),
        GenreLink("Eurobeat", "Soft Pop", 0.75), GenreLink("Eurobeat", "Acoustic", 0.80), GenreLink("Eurobeat", "Folk", 0.75),
        GenreLink("Eurobeat", "Jazz", 0.70), GenreLink("Eurobeat", "Children's", 0.95),
        // Hard Hip-Hop & Hip-Hop vs classical / folk / country / children's
        GenreLink("Trap", "Classical", 0.85), GenreLink("Trap", "Piano", 0.80), GenreLink("Trap", "Folk", 0.85), GenreLink("Trap", "Country", 0.85),
        GenreLink("Trap", "Soft Pop", 0.70), GenreLink("Trap", "Ballad", 0.75), GenreLink("Trap", "Children's", 0.95), GenreLink("Trap", "Metal", 0.65),
        GenreLink("Drill", "Classical", 0.85), GenreLink("Drill", "Piano", 0.80), GenreLink("Drill", "Folk", 0.85), GenreLink("Drill", "Country", 0.85),
        GenreLink("Drill", "Soft Pop", 0.70), GenreLink("Drill", "Ballad", 0.75), GenreLink("Drill", "Children's", 0.95), GenreLink("Drill", "Metal", 0.65),
        GenreLink("Gangsta Rap", "Classical", 0.85), GenreLink("Gangsta Rap", "Piano", 0.80), GenreLink("Gangsta Rap", "Folk", 0.85), GenreLink("Gangsta Rap", "Country", 0.85),
        GenreLink("Gangsta Rap", "Soft Pop", 0.70), GenreLink("Gangsta Rap", "Ballad", 0.75), GenreLink("Gangsta Rap", "Children's", 0.95), GenreLink("Gangsta Rap", "Metal", 0.65),
        GenreLink("Grime", "Classical", 0.85), GenreLink("Grime", "Piano", 0.80), GenreLink("Grime", "Folk", 0.85), GenreLink("Grime", "Country", 0.85),
        GenreLink("Grime", "Soft Pop", 0.70), GenreLink("Grime", "Ballad", 0.75), GenreLink("Grime", "Children's", 0.95), GenreLink("Grime", "Metal", 0.65),
        GenreLink("Hip-Hop", "Classical", 0.70), GenreLink("Hip-Hop", "Folk", 0.70), GenreLink("Hip-Hop", "Country", 0.75), GenreLink("Hip-Hop", "Children's", 0.90),
        // Children's & Spoken Word separations
        GenreLink("Children's", "Electronic", 0.80), GenreLink("Children's", "Blues", 0.75), GenreLink("Children's", "Spoken Word", 0.70),
        GenreLink("Spoken Word", "Metal", 0.90), GenreLink("Spoken Word", "EDM", 0.90), GenreLink("Spoken Word", "Rock", 0.75),
        GenreLink("Spoken Word", "Pop", 0.75), GenreLink("Spoken Word", "Classical", 0.70)
    )

    private val contextGenres = setOf(
        "Soundtrack", "Film Score", "Video Game Music", "Anime", "TV Soundtrack", "Musical Theatre", "Instrumental"
    )

    private data class AliasEntry(val phrase: String, val node: String, val words: Int)

    private val parentMap: Map<String, String>
    private val knownSet: Set<String>
    private val byLower: Map<String, String>
    private val aliasList: List<AliasEntry>
    private val linkMap: Map<String, List<Pair<String, Double>>>
    private val clashMap: Map<String, Map<String, Double>>
    private val expandedCache = HashMap<String, Map<String, Double>>()
    private val childrenMap: Map<String, List<String>>
    private val rootList: List<String>

    private val multiSpaceRe = Regex("\\s+")
    private val tagSplitRe = Regex("[;|/\\x00]+")
    private val id3NumericRe = Regex("^\\(?(\\d{1,3})\\)?$")

    private val id3v1Names = mapOf(
        0 to "Blues", 1 to "Classic Rock", 2 to "Country", 3 to "Dance", 4 to "Disco", 5 to "Funk",
        6 to "Grunge", 7 to "Hip-Hop", 8 to "Jazz", 9 to "Metal", 13 to "Pop", 14 to "R&B", 15 to "Rap",
        16 to "Reggae", 17 to "Rock", 18 to "Techno", 19 to "Industrial", 20 to "Alternative", 21 to "Ska",
        22 to "Death Metal", 24 to "Soundtrack", 26 to "Ambient", 27 to "Trip-Hop", 31 to "Trance",
        32 to "Classical", 33 to "Instrumental", 36 to "Game", 40 to "Alternative Rock", 42 to "Soul",
        43 to "Punk", 52 to "Electronic", 53 to "Pop-Folk", 54 to "Eurodance", 57 to "Comedy",
        80 to "Folk", 81 to "Folk-Rock", 90 to "Avantgarde", 92 to "Progressive Rock", 93 to "Psychedelic Rock",
        94 to "Symphonic Rock", 95 to "Slow Rock", 96 to "Big Band", 98 to "Easy Listening", 99 to "Acoustic",
        101 to "Speech", 102 to "Chanson", 103 to "Opera", 104 to "Chamber Music", 105 to "Sonata",
        106 to "Symphony", 112 to "Club", 113 to "Tango", 114 to "Samba", 115 to "Folklore",
        116 to "Ballad", 117 to "Power Ballad", 121 to "Punk Rock", 122 to "Drum Solo", 124 to "Euro-House",
        125 to "Dance Hall", 128 to "Club-House", 129 to "Hardcore", 131 to "Indie", 132 to "BritPop",
        137 to "Heavy Metal", 138 to "Black Metal", 141 to "Christian Rock", 143 to "Salsa", 144 to "Thrash Metal",
        145 to "Anime", 146 to "Jpop", 147 to "Synthpop"
    )

    init {
        val pm = HashMap<String, String>()
        val ks = HashSet<String>()
        val bl = HashMap<String, String>()
        val aliases = mutableListOf<AliasEntry>()
        val seen = HashSet<String>()
        val ch = LinkedHashMap<String, MutableList<String>>()
        val roots = mutableListOf<String>()

        for (n in nodes) {
            ks.add(n.name)
            bl[n.name.lowercase()] = n.name
            pm[n.name] = n.parent
            if (n.parent.isEmpty()) {
                roots.add(n.name)
            } else {
                ch.getOrPut(n.parent) { mutableListOf() }.add(n.name)
            }
            val allNames = listOf(n.name) + n.aliases
            for (a in allNames) {
                val p = normTag(a)
                if (p.isEmpty() || !seen.add("$p|${n.name}")) continue
                aliases.add(AliasEntry(p, n.name, p.split(' ').count { it.isNotEmpty() }))
            }
        }
        aliases.sortWith(Comparator { a, b ->
            val cmpWords = b.words.compareTo(a.words)
            if (cmpWords != 0) cmpWords else b.phrase.length.compareTo(a.phrase.length)
        })

        val lm = HashMap<String, MutableList<Pair<String, Double>>>()
        for (l in links) {
            lm.getOrPut(l.a) { mutableListOf() }.add(l.b to l.w)
            lm.getOrPut(l.b) { mutableListOf() }.add(l.a to l.w)
        }

        val cm = HashMap<String, HashMap<String, Double>>()
        for (c in clashes) {
            val mA = cm.getOrPut(c.a) { HashMap() }
            mA[c.b] = max(mA[c.b] ?: 0.0, c.w)
            val mB = cm.getOrPut(c.b) { HashMap() }
            mB[c.a] = max(mB[c.a] ?: 0.0, c.w)
        }

        parentMap = pm
        knownSet = ks
        byLower = bl
        aliasList = aliases
        linkMap = lm
        clashMap = cm
        childrenMap = ch
        rootList = roots
    }

    fun isKnown(name: String): Boolean = knownSet.contains(name)
    fun rootNodes(): List<String> = rootList
    fun childrenOf(parent: String): List<String> = childrenMap[parent].orEmpty()

    private fun normTag(raw: String): String {
        val trimmed = raw.trim()
        val id3Num = id3NumericRe.matchEntire(trimmed)?.groupValues?.get(1)?.toIntOrNull()
        val resolved = if (id3Num != null) id3v1Names[id3Num] ?: "" else trimmed
        if (resolved.isEmpty()) return ""
        val s = resolved.lowercase()
            .replace('-', ' ')
            .replace('_', ' ')
            .replace('.', ' ')
            .replace('/', ' ')
            .replace(',', ' ')
            .replace("'", "")
            .replace("’", "")
            .replace("&", " and ")
            .replace('(', ' ')
            .replace(')', ' ')
            .replace(':', ' ')
        return multiSpaceRe.replace(s, " ").trim()
    }

    fun mapTag(raw: String): String {
        val s = normTag(raw)
        if (s.isEmpty()) return ""
        val padded = " $s "
        for (a in aliasList) {
            if (s == a.phrase || padded.contains(" ${a.phrase} ")) {
                return a.node
            }
        }
        return ""
    }

    fun canonicalGenreName(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        if (knownSet.contains(s)) return s
        byLower[s.lowercase()]?.let { return it }
        return mapTag(s)
    }

    fun tagGenres(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        for (part in tagSplitRe.split(raw)) {
            val g = mapTag(part)
            if (g.isNotEmpty()) out.add(g)
        }
        return dedupeGenres(out, 4)
    }

    fun isAncestorGenre(anc: String, node: String): Boolean {
        var p = parentMap[node] ?: ""
        var guard = 0
        while (p.isNotEmpty() && guard < 8) {
            if (p == anc) return true
            p = parentMap[p] ?: ""
            guard++
        }
        return false
    }

    fun dedupeGenres(list: List<String>, maxCount: Int = 5): List<String> {
        val uniq = mutableListOf<String>()
        val seen = HashSet<String>()
        for (raw in list) {
            val g = raw.trim()
            if (g.isNotEmpty() && seen.add(g)) {
                uniq.add(g)
            }
        }
        val out = mutableListOf<String>()
        for (g in uniq) {
            var implied = false
            for (o in uniq) {
                if (o != g && isAncestorGenre(g, o)) {
                    implied = true
                    break
                }
            }
            if (!implied) out.add(g)
        }
        return if (maxCount > 0 && out.size > maxCount) out.subList(0, maxCount) else out
    }

    fun joinGenres(list: List<String>): String = list.joinToString("|")

    fun splitGenres(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        return s.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun cleanUserGenres(list: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (raw0 in list) {
            var raw = raw0.trim()
            if (raw.isEmpty()) continue
            if (knownSet.contains(raw)) {
                out.add(raw)
                continue
            }
            val mapped = mapTag(raw)
            if (mapped.isNotEmpty()) {
                out.add(mapped)
                continue
            }
            if (raw.length > 40) raw = raw.substring(0, 40)
            out.add(raw)
        }
        return dedupeGenres(out, 6)
    }

    private val soundtrackRe = Regex("(?i)\\b(original (motion picture |game |series |tv |film )?(soundtrack|score)|motion picture soundtrack|ost|o\\.s\\.t|gekiban\\s*ongakushu)\\b|サウンドトラック|劇伴")
    private val childrenRe = Regex("(?i)\\b(lullab(y|ies)|nursery rhymes?|cocomelon|kids songs?|children'?s songs?)\\b")
    private val animeRe = Regex("(?i)\\b(anime|anison|opening\\s+theme|ending\\s+theme|tv[\\s-]size|op\\s+theme|ed\\s+theme|character\\s+song|insert\\s+song)\\b|アニメ")
    private val vgmRe = Regex("(?i)\\b(video\\s*game|original\\s+game\\s+(soundtrack|score)|game\\s+(soundtrack|ost|music)|vgm|boss\\s+theme|battle\\s+theme)\\b")
    private val chiptuneRe = Regex("(?i)\\b(chiptune|8[\\s-]?bit|16[\\s-]?bit)\\b")
    private val lofiRe = Regex("(?i)\\b(lo[\\s-]?fi|chillhop)\\b")
    private val pianoRe = Regex("(?i)\\b(piano\\s+(ver\\.?|version|cover|solo|arrangement|collection)|solo\\s+piano)\\b")
    private val acousticRe = Regex("(?i)\\b(acoustic\\s+(ver\\.?|version|cover|live|session)|unplugged)\\b")
    private val orchestralRe = Regex("(?i)\\b(orchestral\\s+(ver\\.?|version|arrangement|suite)|philharmonic\\s+orchestra)\\b")

    fun heuristicGenres(t: Track): List<String> {
        val out = mutableListOf<String>()
        val text = "${t.album} ${t.title}"
        if (soundtrackRe.containsMatchIn(text)) out.add("Soundtrack")
        if (animeRe.containsMatchIn(text)) out.add("Anime")
        if (vgmRe.containsMatchIn(text)) out.add("Video Game Music")
        if (chiptuneRe.containsMatchIn(text)) out.add("Chiptune")
        if (lofiRe.containsMatchIn(text)) out.add("Lo-fi")
        if (pianoRe.containsMatchIn(text)) out.add("Piano")
        if (acousticRe.containsMatchIn(text)) out.add("Acoustic")
        if (orchestralRe.containsMatchIn(text)) out.add("Orchestral")
        if (childrenRe.containsMatchIn("$text ${t.artist}")) out.add("Children's")
        return dedupeGenres(out, 5)
    }

    fun formatGenreTreeForPrompt(): String {
        fun formatNode(name: String): String {
            val kids = childrenMap[name].orEmpty()
            return if (kids.isEmpty()) name else "$name (${kids.joinToString(", ") { formatNode(it) }})"
        }
        return rootList.joinToString("\n") { "- ${formatNode(it)}" } + "\n"
    }

    /** Returns grouped families: Root -> list of descendants (excluding Root itself unless standalone). */
    fun taxonomyGroups(): List<Pair<String, List<String>>> {
        fun collectDescendants(parent: String, out: MutableList<String>) {
            for (c in childrenMap[parent].orEmpty()) {
                out.add(c)
                collectDescendants(c, out)
            }
        }
        return rootList.map { r ->
            val subs = mutableListOf<String>()
            collectDescendants(r, subs)
            r to subs
        }
    }

    // ---------- Similarity & Recommendation Helpers ----------

    private const val PARENT_DECAY = 0.6
    private const val CLASH_HOP_DECAY = 0.92

    private fun expandGenre(g: String): Map<String, Double> {
        synchronized(expandedCache) {
            expandedCache[g]?.let { return it }
            val m = HashMap<String, Double>()
            var w = 1.0
            var cur: String? = g
            var guard = 0
            while (!cur.isNullOrEmpty() && guard < 6) {
                if ((m[cur] ?: 0.0) < w) m[cur] = w
                for ((other, lw) in linkMap[cur].orEmpty()) {
                    if ((m[other] ?: 0.0) < w * lw) m[other] = w * lw
                }
                cur = parentMap[cur]
                w *= PARENT_DECAY
                guard++
            }
            expandedCache[g] = m
            return m
        }
    }

    private fun pairPosSim(ga: String, gb: String): Double {
        val ea = expandGenre(ga)
        val eb = expandGenre(gb)
        var best = 0.0
        for ((node, wa) in ea) {
            val wb = eb[node]
            if (wb != null) best = max(best, wa * wb)
        }
        return best
    }

    private fun pairClash(ga: String, gb: String): Double {
        if (pairPosSim(ga, gb) >= 0.15) return 0.0
        var best = 0.0
        var wa = 1.0
        var curA: String? = ga
        var gaGuard = 0
        while (!curA.isNullOrEmpty() && gaGuard < 6) {
            val mapA = clashMap[curA]
            if (mapA != null) {
                var wb = 1.0
                var curB: String? = gb
                var gbGuard = 0
                while (!curB.isNullOrEmpty() && gbGuard < 6) {
                    val cw = mapA[curB]
                    if (cw != null) best = max(best, wa * wb * cw)
                    curB = parentMap[curB]
                    wb *= CLASH_HOP_DECAY
                    gbGuard++
                }
            }
            curA = parentMap[curA]
            wa *= CLASH_HOP_DECAY
            gaGuard++
        }
        return best
    }

    fun genreClash(a: List<String>, b: List<String>): Double {
        val styleA = a.filter { !contextGenres.contains(it) }
        val styleB = b.filter { !contextGenres.contains(it) }
        if (styleA.isEmpty() || styleB.isEmpty()) return 0.0

        var best = 0.0
        for (ga in styleA) {
            if (styleB.any { pairPosSim(ga, it) >= 0.5 }) continue
            for (gb in styleB) {
                if (styleA.any { pairPosSim(it, gb) >= 0.5 }) continue
                best = max(best, pairClash(ga, gb))
            }
        }
        return best
    }

    fun genreSim(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var rawPos = 0.0
        for (ga in a) {
            for (gb in b) {
                rawPos = max(rawPos, pairPosSim(ga, gb))
            }
        }

        val styleA = a.filter { !contextGenres.contains(it) }
        val styleB = b.filter { !contextGenres.contains(it) }
        val ctxA = a.filter { contextGenres.contains(it) }
        val ctxB = b.filter { contextGenres.contains(it) }

        var stylePos = 0.0
        for (ga in styleA) {
            for (gb in styleB) {
                stylePos = max(stylePos, pairPosSim(ga, gb))
            }
        }
        var ctxPos = 0.0
        for (ga in ctxA) {
            for (gb in ctxB) {
                ctxPos = max(ctxPos, pairPosSim(ga, gb))
            }
        }

        val basePos = when {
            styleA.isNotEmpty() && styleB.isNotEmpty() && ctxA.isNotEmpty() && ctxB.isNotEmpty() ->
                stylePos * 0.75 + ctxPos * 0.25
            styleA.isNotEmpty() && styleB.isNotEmpty() ->
                max(stylePos, rawPos)
            else -> rawPos
        }

        val clash = genreClash(a, b)
        return max(0.0, basePos - clash * 0.9)
    }

    fun genreRelation(a: List<String>, b: List<String>): GenreRelation {
        if (a.isEmpty() || b.isEmpty()) return GenreRelation.UNKNOWN
        val s = genreSim(a, b)
        val c = genreClash(a, b)
        if (c >= 0.45 && s < 0.35) return GenreRelation.CLASH
        return when {
            s >= 0.5 -> GenreRelation.SAME
            s >= 0.2 -> GenreRelation.RELATED
            else -> GenreRelation.CLASH
        }
    }

    fun effectiveGenres(t: Track, all: List<Track>): List<String> {
        if (t.genres.isNotEmpty()) return t.genres
        val artists = CanonicalKey.splitArtists(t.artist).map { CanonicalKey.norm(it) }.toSet()
        if (artists.isEmpty()) return emptyList()
        val counts = HashMap<String, Int>()
        for (o in all) {
            if (o.genres.isEmpty()) continue
            if (CanonicalKey.splitArtists(o.artist).any { artists.contains(CanonicalKey.norm(it)) }) {
                for (g in o.genres) {
                    counts[g] = (counts[g] ?: 0) + 1
                }
            }
        }
        val sorted = counts.entries.sortedByDescending { it.value }
        if (sorted.isEmpty()) return emptyList()
        val top = sorted.first().value
        return sorted.filter { it.value >= top * 0.5 }.take(3).map { it.key }
    }
}
