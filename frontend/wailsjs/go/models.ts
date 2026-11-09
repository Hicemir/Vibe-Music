export namespace main {
	
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
	export class PlayerState {
	    trackId: number;
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
	    coverTracks: number[];
	
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
	    }
	}
	export class Track {
	    id: number;
	    path: string;
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
	
	    static createFrom(source: any = {}) {
	        return new Track(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.path = source["path"];
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

