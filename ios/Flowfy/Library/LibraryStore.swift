import Foundation
import AVFoundation
import MediaPlayer

/// Scans Documents (Files app, imports, downloads) and the iPhone music library.
@MainActor
final class LibraryStore: ObservableObject {
    static let shared = LibraryStore()

    @Published private(set) var songs: [Song] = []
    @Published private(set) var albums: [Album] = []
    @Published private(set) var artists: [Artist] = []
    @Published private(set) var folders: [Folder] = []
    @Published private(set) var loaded = false
    @Published private(set) var scanning = false
    @Published private(set) var mediaAuth: MPMediaLibraryAuthorizationStatus = MPMediaLibrary.authorizationStatus()

    private(set) var raw: [Song] = []
    private(set) var byID: [String: Song] = [:]
    private(set) var byKey: [String: Song] = [:]

    static let audioExt: Set<String> = ["mp3", "m4a", "aac", "flac", "wav", "aif", "aiff", "caf", "alac", "mp4", "m4b", "opus", "ogg"]

    // ---- metadata cache
    private struct Meta: Codable { var title: String; var artist: String; var album: String; var dur: Int64; var track: Int; var year: Int; var mtime: Double; var size: Int64 }
    private var metaCache: [String: Meta] = [:]
    private let metaFile = FileLocations.caches.appendingPathComponent("meta.json")

    // ---- tag edits (title/artist/album/year per song id)
    struct Override: Codable { var title: String?; var artist: String?; var album: String?; var year: Int? }
    @Published private(set) var overrides: [String: Override] = [:]
    private let overridesFile = FileLocations.support.appendingPathComponent("tag_overrides.json")

    init() {
        if let d = try? Data(contentsOf: metaFile), let m = try? JSONDecoder().decode([String: Meta].self, from: d) { metaCache = m }
        if let d = try? Data(contentsOf: overridesFile), let m = try? JSONDecoder().decode([String: Override].self, from: d) { overrides = m }
    }

    func ensureLoaded() async { if !loaded { await scan() } }

    func requestMusicAccess() {
        MPMediaLibrary.requestAuthorization { st in
            Task { @MainActor in
                self.mediaAuth = st
                if st == .authorized { await self.scan() }
            }
        }
    }

    func scan() async {
        if scanning { return }
        scanning = true
        let cache = metaCache
        let useIpod = Prefs.shared.useMusicLibrary && MPMediaLibrary.authorizationStatus() == .authorized
        let (files, newCache) = await Task.detached(priority: .userInitiated) { Self.scanFiles(cache) }.value
        metaCache = newCache
        if let d = try? JSONEncoder().encode(newCache) { try? d.write(to: metaFile, options: .atomic) }
        var all = files
        if useIpod { all += Self.scanMediaLibrary() }
        raw = all
        build()
        loaded = true
        scanning = false
    }

    nonisolated private static func scanFiles(_ cache: [String: Meta]) -> ([Song], [String: Meta]) {
        let fm = FileManager.default
        let root = FileLocations.documents
        var out: [Song] = []
        var newCache: [String: Meta] = [:]
        _ = FileLocations.music
        guard let en = fm.enumerator(at: root, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey, .creationDateKey, .isRegularFileKey],
                                     options: [.skipsHiddenFiles]) else { return ([], [:]) }
        let rootPath = root.standardizedFileURL.path
        for case let url as URL in en {
            let ext = url.pathExtension.lowercased()
            guard audioExt.contains(ext) else { continue }
            let rv = try? url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey, .creationDateKey, .isRegularFileKey])
            guard rv?.isRegularFile == true else { continue }
            var rel = url.standardizedFileURL.path
            if rel.hasPrefix(rootPath) { rel = String(rel.dropFirst(rootPath.count)) }
            while rel.hasPrefix("/") { rel.removeFirst() }
            let mtime = rv?.contentModificationDate?.timeIntervalSince1970 ?? 0
            let size = Int64(rv?.fileSize ?? 0)
            var meta: Meta
            if let c = cache[rel], c.mtime == mtime, c.size == size { meta = c } else {
                meta = readMeta(url)
                meta.mtime = mtime; meta.size = size
            }
            if meta.dur < 1000 && meta.dur != 0 { continue }
            newCache[rel] = meta
            let folderRel = (rel as NSString).deletingLastPathComponent
            out.append(Song(id: "f:" + rel, fileURL: url, mediaID: nil, title: meta.title, artist: meta.artist, album: meta.album,
                            durationMs: meta.dur, folder: folderRel.isEmpty ? T("الملفات", "Files") : folderRel,
                            dateAdded: rv?.creationDate ?? Date(timeIntervalSince1970: mtime), track: meta.track, year: meta.year,
                            ext: ext.uppercased(), size: size))
        }
        return (out, newCache)
    }

    nonisolated private static func readMeta(_ url: URL) -> Meta {
        let asset = AVURLAsset(url: url)
        var title = "", artist = "", album = "", track = 0, year = 0
        for item in asset.commonMetadata {
            guard let key = item.commonKey else { continue }
            switch key {
            case .commonKeyTitle: title = item.stringValue ?? title
            case .commonKeyArtist: artist = item.stringValue ?? artist
            case .commonKeyAlbumName: album = item.stringValue ?? album
            case .commonKeyCreationDate: year = Int((item.stringValue ?? "").prefix(4)) ?? year
            default: break
            }
        }
        for fmt in asset.availableMetadataFormats {
            for item in asset.metadata(forFormat: fmt) {
                let id = item.identifier?.rawValue ?? ""
                if track == 0, id.contains("TRCK") || id.contains("trkn") {
                    if let s = item.stringValue { track = Int(s.split(separator: "/").first ?? "") ?? 0 }
                    else if let d = item.dataValue, d.count >= 4 { track = Int(d[3]) }
                }
                if year == 0, id.contains("TYER") || id.contains("TDRC") || id.contains("day") {
                    year = Int((item.stringValue ?? "").prefix(4)) ?? 0
                }
            }
        }
        let secs = CMTimeGetSeconds(asset.duration)
        let dur = secs.isFinite ? Int64(secs * 1000) : 0
        if title.trimmed.isEmpty { title = url.deletingPathExtension().lastPathComponent }
        return Meta(title: title.trimmed, artist: artist.trimmed.isEmpty ? Song.unknown : artist.trimmed,
                    album: album.trimmed.isEmpty ? Song.unknown : album.trimmed, dur: dur, track: track, year: year, mtime: 0, size: 0)
    }

    private static func scanMediaLibrary() -> [Song] {
        guard let items = MPMediaQuery.songs().items else { return [] }
        return items.compactMap { it in
            guard it.assetURL != nil, !it.hasProtectedAsset, !it.isCloudItem else { return nil }
            let dur = Int64(it.playbackDuration * 1000)
            return Song(id: "m:\(it.persistentID)", fileURL: nil, mediaID: it.persistentID,
                        title: (it.title ?? "").trimmed.isEmpty ? T("بدون عنوان", "Untitled") : it.title!,
                        artist: (it.artist ?? "").trimmed.isEmpty ? Song.unknown : it.artist!,
                        album: (it.albumTitle ?? "").trimmed.isEmpty ? Song.unknown : it.albumTitle!,
                        durationMs: dur, folder: T("مكتبة الآيفون", "iPhone Library"), dateAdded: it.dateAdded,
                        track: it.albumTrackNumber, year: Calendar.current.component(.year, from: it.releaseDate ?? Date(timeIntervalSince1970: 0)) > 1970
                            ? Calendar.current.component(.year, from: it.releaseDate!) : 0,
                        ext: "M4A", size: 0)
        }
    }

    func rebuild() { build() }

    private func apply(_ s: Song) -> Song {
        guard let o = overrides[s.id] else { return s }
        var x = s
        if let t = o.title, !t.isEmpty { x.title = t }
        if let a = o.artist, !a.isEmpty { x.artist = a }
        if let al = o.album, !al.isEmpty { x.album = al }
        if let y = o.year { x.year = y }
        return x
    }

    private func build() {
        let excluded = Prefs.shared.excludedFolders
        let list = raw.filter { s in !excluded.contains(where: { s.folder == $0 || s.folder.hasPrefix($0 + "/") }) }.map(apply)
        songs = list.sorted { nameLess($0.title, $1.title) }
        var ids: [String: Song] = [:], keys: [String: Song] = [:]
        for s in raw.map(apply) { ids[s.id] = s; if keys[s.key] == nil { keys[s.key] = s } }
        byID = ids; byKey = keys
        let groupedAlbums = Dictionary(grouping: list, by: { $0.albumKey })
        albums = groupedAlbums.map { k, v in
            let tracks = v.sorted { $0.track != $1.track ? $0.track < $1.track : nameLess($0.title, $1.title) }
            let counts = Dictionary(grouping: v, by: { $0.artist }).mapValues(\.count)
            let artist = counts.max { $0.value < $1.value }?.key ?? Song.unknown
            return Album(id: k, title: v[0].album, artist: artist, year: v.map(\.year).max() ?? 0, songs: tracks)
        }.sorted { nameLess($0.title, $1.title) }
        let albumsCopy = albums
        artists = Dictionary(grouping: list, by: { $0.artist.lowercased() }).map { _, v in
            let name = v[0].artist
            return Artist(name: name, songs: v.sorted { nameLess($0.title, $1.title) },
                          albums: albumsCopy.filter { a in a.songs.contains { $0.artist.caseInsensitiveCompare(name) == .orderedSame } })
        }.sorted { nameLess($0.name, $1.name) }
        folders = Dictionary(grouping: list, by: { $0.folder }).map { p, v in
            Folder(path: p, name: (p as NSString).lastPathComponent.isEmpty ? p : (p as NSString).lastPathComponent,
                   songs: v.sorted { nameLess($0.title, $1.title) })
        }.sorted { nameLess($0.name, $1.name) }
    }

    var allFolders: [String] { Array(Set(raw.map(\.folder))).sorted { nameLess($0, $1) } }

    func setOverride(_ song: Song, title: String, artist: String, album: String, year: Int) {
        let oldKey = song.key
        overrides[song.id] = Override(title: title, artist: artist, album: album, year: year)
        if let d = try? JSONEncoder().encode(overrides) { try? d.write(to: overridesFile, options: .atomic) }
        build()
        if let n = byID[song.id], n.key != oldKey {
            UserStore.shared.renameKey(oldKey, n.key)
            LyricsStore.shared.renameKey(oldKey, n.key)
        }
    }

    // ---- import / delete
    func importFiles(_ urls: [URL]) async -> Int {
        var n = 0
        for u in urls {
            let access = u.startAccessingSecurityScopedResource()
            defer { if access { u.stopAccessingSecurityScopedResource() } }
            var dest = FileLocations.music.appendingPathComponent(u.lastPathComponent)
            var i = 1
            while FileManager.default.fileExists(atPath: dest.path) {
                dest = FileLocations.music.appendingPathComponent("\(u.deletingPathExtension().lastPathComponent) (\(i)).\(u.pathExtension)"); i += 1
            }
            do { try FileManager.default.copyItem(at: u, to: dest); n += 1 } catch { }
        }
        await scan()
        return n
    }

    func delete(_ song: Song) async {
        if let u = song.fileURL { try? FileManager.default.removeItem(at: u) }
        await scan()
    }

    /// Lyrics stored in the file's own tags.
    nonisolated static func embeddedLyrics(_ song: Song) -> String? {
        if let id = song.mediaID {
            let q = MPMediaQuery.songs()
            q.addFilterPredicate(MPMediaPropertyPredicate(value: NSNumber(value: id), forProperty: MPMediaItemPropertyPersistentID))
            if let l = q.items?.first?.lyrics, !l.trimmed.isEmpty { return l }
        }
        guard let url = song.fileURL else { return nil }
        let asset = AVURLAsset(url: url)
        if let l = asset.lyrics, !l.trimmed.isEmpty { return l }
        for fmt in asset.availableMetadataFormats {
            for item in asset.metadata(forFormat: fmt) {
                let id = item.identifier?.rawValue ?? ""
                if id.contains("USLT") || id.contains("lyr") || id.uppercased().contains("LYRICS"), let s = item.stringValue, !s.trimmed.isEmpty { return s }
            }
        }
        return nil
    }

    /// A file URL AVAudioFile can read (iPhone library songs are exported once to the cache).
    nonisolated static func playableURL(_ song: Song) async -> URL? {
        if let u = song.fileURL { return u }
        guard let id = song.mediaID else { return nil }
        let out = FileLocations.dir(FileLocations.caches, "export").appendingPathComponent("\(id).m4a")
        if FileManager.default.fileExists(atPath: out.path) { return out }
        let q = MPMediaQuery.songs()
        q.addFilterPredicate(MPMediaPropertyPredicate(value: NSNumber(value: id), forProperty: MPMediaItemPropertyPersistentID))
        guard let asset = q.items?.first?.assetURL.map({ AVURLAsset(url: $0) }),
              let ex = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else { return nil }
        let tmp = out.deletingLastPathComponent().appendingPathComponent("\(id).tmp.m4a")
        try? FileManager.default.removeItem(at: tmp)
        ex.outputURL = tmp
        ex.outputFileType = .m4a
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in ex.exportAsynchronously { c.resume() } }
        guard ex.status == .completed else { return nil }
        try? FileManager.default.moveItem(at: tmp, to: out)
        return out
    }
}
