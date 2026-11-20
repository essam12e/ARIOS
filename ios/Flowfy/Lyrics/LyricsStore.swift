import Foundation
import CryptoKit

struct ScoredCandidate: Identifiable {
    var candidate: LyricsCandidate
    var score: LyricsMatcher.Score
    var id: UUID { candidate.id }
    var confident: Bool { LyricsMatcher.isConfident(score) }
}

/// Lyrics cache + lookup: saved/edited → embedded tags → sidecar .lrc → online sources → on-device timing.
@MainActor
final class LyricsStore: ObservableObject {
    static let shared = LyricsStore()
    static let embedded = "Embedded", autoSyncSource = "AutoSync", manual = "Manual"

    @Published private(set) var revision = 0
    @Published private(set) var syncing: Set<String> = []

    private let dir = FileLocations.dir(FileLocations.support, "lyrics")
    private var memory: [String: Lyrics] = [:]
    private var noEmbedded: Set<String> = []
    private var syncTried: Set<String> = []

    private func hash(_ k: String) -> String { Insecure.SHA1.hash(data: Data(k.utf8)).map { String(format: "%02x", $0) }.joined() }
    private func fileFor(_ s: Song) -> URL { dir.appendingPathComponent(hash(s.key) + ".lrc") }
    private func missFor(_ s: Song) -> URL { dir.appendingPathComponent(hash(s.key) + ".miss") }
    private func noSyncFor(_ s: Song) -> URL { dir.appendingPathComponent(hash(s.key) + ".nosync") }

    func isCached(_ s: Song) -> Bool { FileManager.default.fileExists(atPath: fileFor(s).path) }

    private func readCache(_ s: Song) -> Lyrics? {
        guard let raw = try? String(contentsOf: fileFor(s), encoding: .utf8) else { return nil }
        var src = "Flowfy"
        if let r = raw.range(of: #"\[re:([^\]]+)\]"#, options: .regularExpression) {
            src = String(raw[r]).replacingOccurrences(of: "[re:", with: "").replacingOccurrences(of: "]", with: "")
        }
        return LrcParser.parse(raw, source: src, durationMs: s.durationMs)
    }

    func getLocal(_ s: Song) async -> Lyrics? {
        if let l = memory[s.key] { return l }
        if let l = readCache(s) { memory[s.key] = l; return l }
        if !noEmbedded.contains(s.id) {
            let text = await Task.detached { LibraryStore.embeddedLyrics(s) }.value
            if let t = text, let l = LrcParser.parse(t, source: Self.embedded, durationMs: s.durationMs), !l.lines.isEmpty {
                memory[s.key] = l; return l
            }
            noEmbedded.insert(s.id)
        }
        if let u = s.fileURL {
            let side = u.deletingPathExtension().appendingPathExtension("lrc")
            if let t = try? String(contentsOf: side, encoding: .utf8), let l = LrcParser.parse(t, source: "LRC", durationMs: s.durationMs) {
                memory[s.key] = l; return l
            }
        }
        return nil
    }

    func get(_ s: Song, allowNetwork: Bool? = nil) async -> Lyrics? {
        let net = allowNetwork ?? Prefs.shared.autoFetchLyrics
        if let l = await getLocal(s) {
            if !l.synced && Prefs.shared.autoSyncLyrics { requestSync(s, l, allowNetwork: net) }
            return l
        }
        guard net else { return nil }
        let miss = missFor(s)
        if let a = try? FileManager.default.attributesOfItem(atPath: miss.path), let d = a[.modificationDate] as? Date,
           Date().timeIntervalSince(d) < 24 * 3600 { return nil }
        if let best = await withTimeout(30, { await self.autoFind(s) }) ?? nil {
            save(s, best, manual: false)
            if !best.synced && Prefs.shared.autoSyncLyrics { requestSync(s, best, allowNetwork: true) }
            return best
        }
        try? Data().write(to: miss)
        return nil
    }

    // ---- automatic timing
    func markNoAutoSync(_ s: Song) {
        syncTried.insert(s.key)
        try? Data("user".utf8).write(to: noSyncFor(s))
    }

    func requestSync(_ s: Song, _ plain: Lyrics, allowNetwork: Bool, force: Bool = false) {
        if !force && (syncTried.contains(s.key) || FileManager.default.fileExists(atPath: noSyncFor(s).path)) { return }
        syncTried.insert(s.key)
        Task { _ = await autoSync(s, plain, allowNetwork: allowNetwork) }
    }

    static let section = #"^\s*[\[(][^\])]{0,40}[\])]\s*$"#

    func autoSync(_ s: Song, _ plain: Lyrics, allowNetwork: Bool = true) async -> Bool {
        syncing.insert(s.key)
        defer { syncing.remove(s.key) }
        if allowNetwork, let online = await withTimeout(25, { await self.findSyncedMatching(s, plain) }) ?? nil {
            save(s, online, manual: false)
            UserStore.shared.setOffset(s.key, 0)
            try? FileManager.default.removeItem(at: noSyncFor(s))
            return true
        }
        let lines = plain.lines.map { $0.text.trimmed }.filter { !$0.isEmpty && !$0.reContains(Self.section) }
        guard let url = await LibraryStore.playableURL(s) else { return false }
        let lrc = await Task.detached(priority: .utility) { AutoSync.sync(url: url, lines: lines) }.value
        if let lrc {
            saveRaw(s, lrc, source: Self.autoSyncSource)
            UserStore.shared.setOffset(s.key, 0)
            try? FileManager.default.removeItem(at: noSyncFor(s))
            return true
        }
        try? Data().write(to: noSyncFor(s))
        return false
    }

    private func findSyncedMatching(_ s: Song, _ plain: Lyrics) async -> Lyrics? {
        let qs = queries(for: s)
        let mine = plain.lines.map { LyricsMatcher.normalise($0.text) }.filter { $0.count > 3 }
        if mine.isEmpty { return nil }
        for p in allLyricsProviders where Prefs.shared.sourceEnabled(p.id) {
            let cands = await searchProvider(p, qs).map { ScoredCandidate(candidate: $0, score: LyricsMatcher.score($0, qs)) }
                .filter { ($0.score.durationDiff ?? 0) <= 15 }.sorted { $0.score.total > $1.score.total }.prefix(4)
            for c in cands {
                guard let l = await c.candidate.load(), l.synced else { continue }
                let theirs = l.lines.map { LyricsMatcher.normalise($0.text) }
                let hit = mine.filter { m in theirs.contains { LyricsMatcher.similarity(m, $0) >= 0.8 } }.count
                if Double(hit) >= Double(mine.count) * 0.5 { return l }
            }
        }
        return nil
    }

    func queries(for s: Song) -> [LyricsQuery] {
        let q = LyricsMatcher.queries(title: s.title, artist: s.artist == Song.unknown ? "" : s.artist,
                                      album: s.album == Song.unknown ? nil : s.album, durationMs: s.durationMs)
        return q.isEmpty ? [LyricsQuery(title: s.title, artist: "", album: nil, durationMs: s.durationMs)] : q
    }

    private func searchProvider(_ p: LyricsProvider, _ qs: [LyricsQuery]) async -> [LyricsCandidate] {
        var all: [LyricsCandidate] = []
        await withTaskGroup(of: [LyricsCandidate].self) { g in
            for q in qs.prefix(3) { g.addTask { await withTimeout(12, { await p.search(q) }) ?? [] } }
            for await r in g { all += r }
        }
        var seen = Set<String>()
        return all.filter { seen.insert("\($0.sourceId)|\($0.title.lowercased())|\($0.artist.lowercased())|\($0.durationSec ?? -1)").inserted }
    }

    private func autoFind(_ s: Song) async -> Lyrics? {
        let qs = queries(for: s)
        var plainFallback: Lyrics?
        for p in allLyricsProviders where Prefs.shared.sourceEnabled(p.id) {
            let ranked = await searchProvider(p, qs).map { ScoredCandidate(candidate: $0, score: LyricsMatcher.score($0, qs)) }
                .filter(\.confident).sorted { $0.score.total > $1.score.total }
            for c in ranked.prefix(3) {
                guard let l = await c.candidate.load(), l.lines.count >= 2 else { continue }
                if l.synced { return l }
                if plainFallback == nil { plainFallback = l }
            }
        }
        return plainFallback
    }

    func searchAll(_ qs: [LyricsQuery]) async -> [ScoredCandidate] {
        var all: [LyricsCandidate] = []
        await withTaskGroup(of: [LyricsCandidate].self) { g in
            for p in allLyricsProviders where Prefs.shared.sourceEnabled(p.id) { g.addTask { await self.searchProvider(p, qs) } }
            for await r in g { all += r }
        }
        return all.map { ScoredCandidate(candidate: $0, score: LyricsMatcher.score($0, qs)) }.sorted { $0.score.total > $1.score.total }
    }

    func save(_ s: Song, _ l: Lyrics, manual: Bool) {
        saveRaw(s, LrcWriter.write(l, title: s.title, artist: s.artist), source: manual ? Self.manual : l.source)
    }

    func saveRaw(_ s: Song, _ lrc: String, source: String = LyricsStore.manual) {
        let body = lrc.components(separatedBy: "\n").filter { !$0.hasPrefix("[re:") }.joined(separator: "\n")
        let text = "[re:\(source)]\n" + body
        try? text.write(to: fileFor(s), atomically: true, encoding: .utf8)
        try? FileManager.default.removeItem(at: missFor(s))
        memory[s.key] = LrcParser.parse(text, source: source, durationMs: s.durationMs)
        revision += 1
    }

    func delete(_ s: Song) {
        try? FileManager.default.removeItem(at: fileFor(s))
        try? FileManager.default.removeItem(at: missFor(s))
        memory[s.key] = nil; noEmbedded.remove(s.id); revision += 1
    }

    func renameKey(_ old: String, _ new: String) {
        let a = dir.appendingPathComponent(hash(old) + ".lrc"), b = dir.appendingPathComponent(hash(new) + ".lrc")
        if FileManager.default.fileExists(atPath: a.path) && !FileManager.default.fileExists(atPath: b.path) { try? FileManager.default.moveItem(at: a, to: b) }
        memory[old] = nil; revision += 1
    }

    func allFiles() -> [URL] { (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil))?.filter { $0.pathExtension == "lrc" } ?? [] }
    var directory: URL { dir }
    func clearMemory() { memory.removeAll(); noEmbedded.removeAll(); revision += 1 }
    func clearCache() {
        (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil))?.forEach { try? FileManager.default.removeItem(at: $0) }
        memory.removeAll(); noEmbedded.removeAll(); syncTried.removeAll(); revision += 1
    }
}

/// Runs `op`, returning nil if it takes longer than `seconds`.
func withTimeout<T: Sendable>(_ seconds: Double, _ op: @escaping @Sendable () async -> T) async -> T? {
    await withTaskGroup(of: T?.self) { g in
        g.addTask { await op() }
        g.addTask { try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)); return nil }
        let first = await g.next() ?? nil
        g.cancelAll()
        return first
    }
}
