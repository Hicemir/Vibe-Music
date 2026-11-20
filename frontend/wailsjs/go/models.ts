export namespace main {
	
	export class AIProviderPreset {
	    id: string;
	    name: string;
	    kind: string;
	    local: boolean;
	    needsKey: boolean;
	    defaultBaseUrl: string;
	    defaultModel: string;
	    note: string;
	
	    static createFrom(source: any = {}) {
	        return new AIProviderPreset(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.name = source["name"];
	        this.kind = source["kind"];
	        this.local = source["local"];
	        this.needsKey = source["needsKey"];
	        this.defaultBaseUrl = source["defaultBaseUrl"];
	        this.defaultModel = source["defaultModel"];
	        this.note = source["note"];
	    }
	}
	export class AISettings {
	    enabled: boolean;
	    autoOnNew: boolean;
	    provider: string;
	    baseUrl: string;
	    apiKey: string;
	    model: string;
	    localModelId: string;
	    localCustomGguf: string;
	    localGpu: boolean;
	    allowCustomGenres: boolean;
	    webSearch: boolean;
	    geminiGrounding: boolean;
	
	    static createFrom(source: any = {}) {
	        return new AISettings(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.enabled = source["enabled"];
	        this.autoOnNew = source["autoOnNew"];
	        this.provider = source["provider"];
	        this.baseUrl = source["baseUrl"];
	        this.apiKey = source["apiKey"];
	        this.model = source["model"];
	        this.localModelId = source["localModelId"];
	        this.localCustomGguf = source["localCustomGguf"];
	        this.localGpu = source["localGpu"];
	        this.allowCustomGenres = source["allowCustomGenres"];
	        this.webSearch = source["webSearch"];
	        this.geminiGrounding = source["geminiGrounding"];
	    }
	}
	export class AITestResult {
	    ok: boolean;
	    message: string;
	    latencyMs: number;
	
	    static createFrom(source: any = {}) {
	        return new AITestResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.message = source["message"];
	        this.latencyMs = source["latencyMs"];
	    }
	}
	export class DiscoverItem {
	    videoId: string;
	    title: string;
	    artist: string;
	    duration: number;
	    thumbUrl: string;
	    url: string;
	
	    static createFrom(source: any = {}) {
	        return new DiscoverItem(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.videoId = source["videoId"];
	        this.title = source["title"];
	        this.artist = source["artist"];
	        this.duration = source["duration"];
	        this.thumbUrl = source["thumbUrl"];
	        this.url = source["url"];
	    }
	}
	export class DownloadJob {
	    id: number;
	    url: string;
	    mode: string;
	    status: string;
	    title: string;
	    playlist: string;
	    percent: number;
	    speed: string;
	    index: number;
	    total: number;
	    done: number;
	    error: string;
	    startedAt: number;
	
	    static createFrom(source: any = {}) {
	        return new DownloadJob(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.url = source["url"];
	        this.mode = source["mode"];
	        this.status = source["status"];
	        this.title = source["title"];
	        this.playlist = source["playlist"];
	        this.percent = source["percent"];
	        this.speed = source["speed"];
	        this.index = source["index"];
	        this.total = source["total"];
	        this.done = source["done"];
	        this.error = source["error"];
	        this.startedAt = source["startedAt"];
	    }
	}
	export class GenreLink {
	    a: string;
	    b: string;
	    w: number;
	
	    static createFrom(source: any = {}) {
	        return new GenreLink(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.a = source["a"];
	        this.b = source["b"];
	        this.w = source["w"];
	    }
	}
	export class GenreNode {
	    name: string;
	    parent: string;
	
	    static createFrom(source: any = {}) {
	        return new GenreNode(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.name = source["name"];
	        this.parent = source["parent"];
	    }
	}
	export class GenreStatus {
	    running: boolean;
	    mode: string;
	    phase: string;
	    done: number;
	    total: number;
	    identified: number;
	    aiIdentified: number;
	    unidentified: number;
	    unchecked: number;
	    pendingAI: number;
	    message: string;
	    error: string;
	
	    static createFrom(source: any = {}) {
	        return new GenreStatus(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.running = source["running"];
	        this.mode = source["mode"];
	        this.phase = source["phase"];
	        this.done = source["done"];
	        this.total = source["total"];
	        this.identified = source["identified"];
	        this.aiIdentified = source["aiIdentified"];
	        this.unidentified = source["unidentified"];
	        this.unchecked = source["unchecked"];
	        this.pendingAI = source["pendingAI"];
	        this.message = source["message"];
	        this.error = source["error"];
	    }
	}
	export class GenreTargets {
	    missing: number;
	    allAuto: number;
	    pendingAI: number;
	    total: number;
	
	    static createFrom(source: any = {}) {
	        return new GenreTargets(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.missing = source["missing"];
	        this.allAuto = source["allAuto"];
	        this.pendingAI = source["pendingAI"];
	        this.total = source["total"];
	    }
	}
	export class GenreTaxonomy {
	    nodes: GenreNode[];
	    links: GenreLink[];
	    clashes: GenreLink[];
	
	    static createFrom(source: any = {}) {
	        return new GenreTaxonomy(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.nodes = this.convertValues(source["nodes"], GenreNode);
	        this.links = this.convertValues(source["links"], GenreLink);
	        this.clashes = this.convertValues(source["clashes"], GenreLink);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class LocalAIModel {
	    id: string;
	    name: string;
	    sizeMb: number;
	    minRam: string;
	    url: string;
	    filename: string;
	    note: string;
	
	    static createFrom(source: any = {}) {
	        return new LocalAIModel(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.name = source["name"];
	        this.sizeMb = source["sizeMb"];
	        this.minRam = source["minRam"];
	        this.url = source["url"];
	        this.filename = source["filename"];
	        this.note = source["note"];
	    }
	}
	export class LocalAIStatus {
	    runtimeReady: boolean;
	    runtimeVersion: string;
	    runtimeVariant: string;
	    vulkanAvail: boolean;
	    installedModels: string[];
	    diskUsageBytes: number;
	    state: string;
	    phase: string;
	    percent: number;
	    downloadedBytes: number;
	    totalBytes: number;
	    message: string;
	    error: string;
	
	    static createFrom(source: any = {}) {
	        return new LocalAIStatus(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.runtimeReady = source["runtimeReady"];
	        this.runtimeVersion = source["runtimeVersion"];
	        this.runtimeVariant = source["runtimeVariant"];
	        this.vulkanAvail = source["vulkanAvail"];
	        this.installedModels = source["installedModels"];
	        this.diskUsageBytes = source["diskUsageBytes"];
	        this.state = source["state"];
	        this.phase = source["phase"];
	        this.percent = source["percent"];
	        this.downloadedBytes = source["downloadedBytes"];
	        this.totalBytes = source["totalBytes"];
	        this.message = source["message"];
	        this.error = source["error"];
	    }
	}
	export class LyricLine {
	    time: number;
	    text: string;
	
	    static createFrom(source: any = {}) {
	        return new LyricLine(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.time = source["time"];
	        this.text = source["text"];
	    }
	}
	export class Lyrics {
	    synced: boolean;
	    lines: LyricLine[];
	
	    static createFrom(source: any = {}) {
	        return new Lyrics(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.synced = source["synced"];
	        this.lines = this.convertValues(source["lines"], LyricLine);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class MoveStatus {
	    running: boolean;
	    done: number;
	    total: number;
	    moved: number;
	    skipped: number;
	    failed: number;
	    message: string;
	
	    static createFrom(source: any = {}) {
	        return new MoveStatus(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.running = source["running"];
	        this.done = source["done"];
	        this.total = source["total"];
	        this.moved = source["moved"];
	        this.skipped = source["skipped"];
	        this.failed = source["failed"];
	        this.message = source["message"];
	    }
	}
	export class PlayerState {
	    trackId: string;
	    playlistId: number;
	    playing: boolean;
	    loaded: boolean;
	    position: number;
	    duration: number;
	    volume: number;
	    muted: boolean;
	    shuffle: boolean;
	    repeat: string;
	    queueIndex: number;
	    queueLen: number;
	    hasNext: boolean;
	    hasPrev: boolean;
	    sleepAt: number;
	    sleepEnd: boolean;
	
	    static createFrom(source: any = {}) {
	        return new PlayerState(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.trackId = source["trackId"];
	        this.playlistId = source["playlistId"];
	        this.playing = source["playing"];
	        this.loaded = source["loaded"];
	        this.position = source["position"];
	        this.duration = source["duration"];
	        this.volume = source["volume"];
	        this.muted = source["muted"];
	        this.shuffle = source["shuffle"];
	        this.repeat = source["repeat"];
	        this.queueIndex = source["queueIndex"];
	        this.queueLen = source["queueLen"];
	        this.hasNext = source["hasNext"];
	        this.hasPrev = source["hasPrev"];
	        this.sleepAt = source["sleepAt"];
	        this.sleepEnd = source["sleepEnd"];
	    }
	}
	export class Playlist {
	    id: number;
	    name: string;
	    createdAt: number;
	    lastPlayed: number;
	    trackCount: number;
	    duration: number;
	    coverTracks: string[];
	
	    static createFrom(source: any = {}) {
	        return new Playlist(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.name = source["name"];
	        this.createdAt = source["createdAt"];
	        this.lastPlayed = source["lastPlayed"];
	        this.trackCount = source["trackCount"];
	        this.duration = source["duration"];
	        this.coverTracks = source["coverTracks"];
	    }
	}
	export class Settings {
	    musicDirs: string[];
	    downloadDir: string;
	    downloadFormat: string;
	    volume: number;
	    shuffle: boolean;
	    repeat: string;
	    fadeSeconds: number;
	    eqEnabled: boolean;
	    eq: number[];
	    autoUpdateYtdlp: boolean;
	    closeToTray: boolean;
	    normalize: boolean;
	    windowWidth: number;
	    windowHeight: number;
	    windowMaximised: boolean;
	    homeOrder: string[];
	    homeHidden: string[];
	    quickPicksRows: string;
	    cookieBrowser: string;
	    browserBackground: boolean;
	    ai: AISettings;
	
	    static createFrom(source: any = {}) {
	        return new Settings(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.musicDirs = source["musicDirs"];
	        this.downloadDir = source["downloadDir"];
	        this.downloadFormat = source["downloadFormat"];
	        this.volume = source["volume"];
	        this.shuffle = source["shuffle"];
	        this.repeat = source["repeat"];
	        this.fadeSeconds = source["fadeSeconds"];
	        this.eqEnabled = source["eqEnabled"];
	        this.eq = source["eq"];
	        this.autoUpdateYtdlp = source["autoUpdateYtdlp"];
	        this.closeToTray = source["closeToTray"];
	        this.normalize = source["normalize"];
	        this.windowWidth = source["windowWidth"];
	        this.windowHeight = source["windowHeight"];
	        this.windowMaximised = source["windowMaximised"];
	        this.homeOrder = source["homeOrder"];
	        this.homeHidden = source["homeHidden"];
	        this.quickPicksRows = source["quickPicksRows"];
	        this.cookieBrowser = source["cookieBrowser"];
	        this.browserBackground = source["browserBackground"];
	        this.ai = this.convertValues(source["ai"], AISettings);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class Track {
	    id: string;
	    ytId: string;
	    title: string;
	    artist: string;
	    album: string;
	    duration: number;
	    year: number;
	    addedAt: number;
	    liked: boolean;
	    playCount: number;
	    hasCover: boolean;
	    format: string;
	    bitrate: number;
	    lastPlayed: number;
	    genres: string[];
	    genreSource: string;
	
	    static createFrom(source: any = {}) {
	        return new Track(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.ytId = source["ytId"];
	        this.title = source["title"];
	        this.artist = source["artist"];
	        this.album = source["album"];
	        this.duration = source["duration"];
	        this.year = source["year"];
	        this.addedAt = source["addedAt"];
	        this.liked = source["liked"];
	        this.playCount = source["playCount"];
	        this.hasCover = source["hasCover"];
	        this.format = source["format"];
	        this.bitrate = source["bitrate"];
	        this.lastPlayed = source["lastPlayed"];
	        this.genres = source["genres"];
	        this.genreSource = source["genreSource"];
	    }
	}
	export class YtMusicCollection {
	    id: string;
	    browseId: string;
	    playlistUrl: string;
	    title: string;
	    subtitle: string;
	    trackCountHint: number;
	    thumbnailUrl: string;
	    type: string;
	
	    static createFrom(source: any = {}) {
	        return new YtMusicCollection(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.browseId = source["browseId"];
	        this.playlistUrl = source["playlistUrl"];
	        this.title = source["title"];
	        this.subtitle = source["subtitle"];
	        this.trackCountHint = source["trackCountHint"];
	        this.thumbnailUrl = source["thumbnailUrl"];
	        this.type = source["type"];
	    }
	}
	export class YtMusicLibraryResponse {
	    collections: YtMusicCollection[];
	    syncedIds: string[];
	    error: string;
	
	    static createFrom(source: any = {}) {
	        return new YtMusicLibraryResponse(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.collections = this.convertValues(source["collections"], YtMusicCollection);
	        this.syncedIds = source["syncedIds"];
	        this.error = source["error"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class YtMusicStatus {
	    loggedIn: boolean;
	    syncing: boolean;
	    message: string;
	
	    static createFrom(source: any = {}) {
	        return new YtMusicStatus(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.loggedIn = source["loggedIn"];
	        this.syncing = source["syncing"];
	        this.message = source["message"];
	    }
	}
	export class YtdlpStatus {
	    state: string;
	    version: string;
	    path: string;
	    message: string;
	
	    static createFrom(source: any = {}) {
	        return new YtdlpStatus(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.state = source["state"];
	        this.version = source["version"];
	        this.path = source["path"];
	        this.message = source["message"];
	    }
	}

}

