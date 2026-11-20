import Foundation

struct LyricsCandidate: Identifiable, @unchecked Sendable {
    let id = UUID()
    var sourceId: String
    var sourceName: String
    var title: String
    var artist: String
    var album: String?
    var durationSec: Int?
    var syncedHint: Bool?
    var load: @Sendable () async -> Lyrics?
}

protocol LyricsProvider: Sendable {
    var id: String { get }
    var displayName: String { get }
    func search(_ q: LyricsQuery) async -> [LyricsCandidate]
}

enum Http {
    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 15
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c)
    }()

    static func get(_ url: URL?, headers: [String: String] = [:]) async -> Data? {
        guard let url else { return nil }
        var r = URLRequest(url: url)
        r.setValue("Flowfy/1.0 (iOS music player)", forHTTPHeaderField: "User-Agent")
        headers.forEach { r.setValue($1, forHTTPHeaderField: $0) }
        guard let (d, resp) = try? await session.data(for: r), let h = resp as? HTTPURLResponse, (200..<300).contains(h.statusCode) else { return nil }
        return d
    }
    static func json(_ url: URL?, headers: [String: String] = [:]) async -> Any? {
        guard let d = await get(url, headers: headers) else { return nil }
        return try? JSONSerialization.jsonObject(with: d)
    }
    static func url(_ base: String, _ items: [(String, String?)]) -> URL? {
        var c = URLComponents(string: base)
        c?.queryItems = items.compactMap { k, v in v.map { URLQueryItem(name: k, value: $0) } }
        return c?.url
    }
}

private func str(_ o: Any?) -> String? { if let s = o as? String, !s.isEmpty, s != "null" { return s }; return nil }

struct LrcLibProvider: LyricsProvider {
    let id = "lrclib", displayName = "LRCLIB"

    private func cand(_ o: [String: Any], _ q: LyricsQuery) -> LyricsCandidate {
        let synced = str(o["syncedLyrics"]), plain = str(o["plainLyrics"])
        let dur = (o["duration"] as? Double).map { Int($0) }.flatMap { $0 > 0 ? $0 : nil }
        let name = displayName
        return LyricsCandidate(sourceId: id, sourceName: name, title: str(o["trackName"]) ?? "", artist: str(o["artistName"]) ?? "",
                               album: str(o["albumName"]), durationSec: dur, syncedHint: synced != nil) {
            guard let t = synced ?? plain else { return nil }
            return LrcParser.parse(t, source: name, durationMs: q.durationMs)
        }
    }

    func search(_ q: LyricsQuery) async -> [LyricsCandidate] {
        var out: [LyricsCandidate] = []
        if let o = await Http.json(Http.url("https://lrclib.net/api/get", [("track_name", q.title), ("artist_name", q.artist),
                                                                           ("album_name", (q.album ?? "").isEmpty ? nil : q.album),
                                                                           ("duration", String(q.durationMs / 1000))])) as? [String: Any] {
            out.append(cand(o, q))
        }
        if let a = await Http.json(Http.url("https://lrclib.net/api/search", [("track_name", q.title), ("artist_name", q.artist.isEmpty ? nil : q.artist)])) as? [[String: Any]] {
            out += a.prefix(12).map { cand($0, q) }
        }
        if out.isEmpty, let a = await Http.json(Http.url("https://lrclib.net/api/search", [("q", "\(q.artist) \(q.title)".trimmed)])) as? [[String: Any]] {
            out += a.prefix(12).map { cand($0, q) }
        }
        return out
    }
}

struct NetEaseProvider: LyricsProvider {
    let id = "netease", displayName = "NetEase"
    static let headers = ["Referer": "https://music.163.com/",
                          "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"]

    func search(_ q: LyricsQuery) async -> [LyricsCandidate] {
        guard let url = URL(string: "https://music.163.com/api/search/get/web") else { return [] }
        var r = URLRequest(url: url)
        r.httpMethod = "POST"
        Self.headers.forEach { r.setValue($1, forHTTPHeaderField: $0) }
        r.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let s = "\(q.title) \(q.artist)".trimmed.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
        r.httpBody = "s=\(s)&type=1&limit=10&offset=0".data(using: .utf8)
        guard let (d, _) = try? await Http.session.data(for: r),
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let songs = (o["result"] as? [String: Any])?["songs"] as? [[String: Any]] else { return [] }
        let name = displayName
        return songs.prefix(10).compactMap { s in
            guard let sid = (s["id"] as? NSNumber)?.int64Value else { return nil }
            let artists = (s["artists"] as? [[String: Any]])?.compactMap { $0["name"] as? String }.joined(separator: ", ") ?? ""
            let dur = ((s["duration"] as? NSNumber)?.intValue ?? 0) / 1000
            return LyricsCandidate(sourceId: id, sourceName: name, title: s["name"] as? String ?? "", artist: artists,
                                   album: (s["album"] as? [String: Any])?["name"] as? String, durationSec: dur > 0 ? dur : nil, syncedHint: nil) {
                await Self.load(sid, q.durationMs, name)
            }
        }
    }

    static func load(_ sid: Int64, _ durationMs: Int64, _ name: String) async -> Lyrics? {
        var o = await Http.json(URL(string: "https://music.163.com/api/song/lyric/v1?id=\(sid)&lv=-1&kv=-1&tv=-1&yv=-1&rv=-1&cp=false"), headers: headers) as? [String: Any]
        if o == nil { o = await Http.json(URL(string: "https://music.163.com/api/song/lyric?id=\(sid)&lv=-1&kv=-1&tv=-1"), headers: headers) as? [String: Any] }
        guard let o else { return nil }
        let lrc = (o["lrc"] as? [String: Any])?["lyric"] as? String ?? ""
        let yrc = (o["yrc"] as? [String: Any])?["lyric"] as? String ?? ""
        let tl = (o["tlyric"] as? [String: Any])?["lyric"] as? String ?? ""
        if !yrc.trimmed.isEmpty, let l = LrcParser.parse(yrc, source: name, durationMs: durationMs, translation: tl), l.lines.count > 2 { return l }
        if lrc.trimmed.isEmpty { return nil }
        return LrcParser.parse(lrc, source: name, durationMs: durationMs, translation: tl)
    }
}

struct QQMusicProvider: LyricsProvider {
    let id = "qq", displayName = "QQ Music"
    static let headers = ["Referer": "https://y.qq.com/", "Origin": "https://y.qq.com"]

    func search(_ q: LyricsQuery) async -> [LyricsCandidate] {
        let u = Http.url("https://c.y.qq.com/soso/fcgi-bin/client_search_cp", [("w", "\(q.title) \(q.artist)".trimmed), ("format", "json"),
                                                                               ("p", "1"), ("n", "10"), ("cr", "1"), ("new_json", "1")])
        guard let o = await Http.json(u, headers: Self.headers) as? [String: Any],
              let list = ((o["data"] as? [String: Any])?["song"] as? [String: Any])?["list"] as? [[String: Any]] else { return [] }
        let name = displayName
        return list.prefix(10).map { s in
            let mid = (s["mid"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (s["songmid"] as? String ?? "")
            let singers = (s["singer"] as? [[String: Any]])?.compactMap { $0["name"] as? String }.joined(separator: ", ") ?? ""
            let dur = (s["interval"] as? NSNumber)?.intValue ?? 0
            return LyricsCandidate(sourceId: id, sourceName: name, title: (s["name"] as? String) ?? (s["songname"] as? String ?? ""), artist: singers,
                                   album: (s["album"] as? [String: Any])?["name"] as? String, durationSec: dur > 0 ? dur : nil, syncedHint: nil) {
                await Self.load(mid, q.durationMs, name)
            }
        }
    }

    static func load(_ mid: String, _ durationMs: Int64, _ name: String) async -> Lyrics? {
        guard let d = await Http.get(URL(string: "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=\(mid)&format=json&nobase64=0&g_tk=5381"), headers: headers),
              var body = String(data: d, encoding: .utf8) else { return nil }
        if let a = body.firstIndex(of: "("), let b = body.lastIndex(of: ")"), a < b { body = String(body[body.index(after: a)..<b]) }
        guard let o = try? JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any] else { return nil }
        func decode(_ f: String) -> String {
            guard let v = o[f] as? String, !v.isEmpty else { return "" }
            if let dd = Data(base64Encoded: v, options: .ignoreUnknownCharacters), let s = String(data: dd, encoding: .utf8) { return s }
            return v
        }
        let lrc = decode("lyric").replacingOccurrences(of: "&apos;", with: "'").replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&#58;", with: ":").replacingOccurrences(of: "&#46;", with: ".")
            .replacingOccurrences(of: "&#32;", with: " ").replacingOccurrences(of: "&#45;", with: "-").replacingOccurrences(of: "&#10;", with: "\n")
        if lrc.trimmed.isEmpty { return nil }
        return LrcParser.parse(lrc, source: name, durationMs: durationMs, translation: decode("trans"))
    }
}

struct LyricsOvhProvider: LyricsProvider {
    let id = "ovh", displayName = "Lyrics.ovh"
    func search(_ q: LyricsQuery) async -> [LyricsCandidate] {
        guard !q.artist.trimmed.isEmpty,
              let a = q.artist.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))),
              let t = q.title.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))),
              let o = await Http.json(URL(string: "https://api.lyrics.ovh/v1/\(a)/\(t)")) as? [String: Any],
              let text = str(o["lyrics"]) else { return [] }
        let l = LrcParser.parse(text.re(#"\n{3,}"#, "\n\n"), source: displayName, durationMs: q.durationMs)
        return [LyricsCandidate(sourceId: id, sourceName: displayName, title: q.title, artist: q.artist, album: q.album, durationSec: nil, syncedHint: false) { l }]
    }
}

let allLyricsProviders: [LyricsProvider] = [LrcLibProvider(), NetEaseProvider(), QQMusicProvider(), LyricsOvhProvider()]
