import Foundation

struct LyricsQuery: Hashable {
    var title: String
    var artist: String
    var album: String?
    var durationMs: Int64
}

extension String {
    func re(_ pattern: String, _ template: String, ci: Bool = false) -> String {
        guard let r = try? NSRegularExpression(pattern: pattern, options: ci ? [.caseInsensitive] : []) else { return self }
        return r.stringByReplacingMatches(in: self, range: NSRange(startIndex..., in: self), withTemplate: template)
    }
    func reSplit(_ pattern: String) -> [String] {
        guard let r = try? NSRegularExpression(pattern: pattern) else { return [self] }
        var out: [String] = []
        var last = startIndex
        for m in r.matches(in: self, range: NSRange(startIndex..., in: self)) {
            guard let rr = Range(m.range, in: self) else { continue }
            out.append(String(self[last..<rr.lowerBound]))
            last = rr.upperBound
        }
        out.append(String(self[last...]))
        return out
    }
    func reContains(_ pattern: String, ci: Bool = false) -> Bool {
        range(of: pattern, options: ci ? [.regularExpression, .caseInsensitive] : [.regularExpression]) != nil
    }
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

enum LyricsMatcher {
    static let noiseWords = #"\b(official|video|audio|lyrics?|lyric video|visuali[sz]er|remaster(ed)?|hd|hq|4k|8k|explicit|clean|live|version|edit|mv|m/v|music video|prod\.?|kalimat|كلمات|فيديو|حصري|حصريا|official music video|exclusive|cover|clip|radio edit|extended|original mix)\b"#

    static func stripTashkeel(_ s: String) -> String { s.re("[\u{064B}-\u{0670}\u{065F}\u{0640}\u{06D6}-\u{06ED}]", "") }

    static func normalise(_ s: String) -> String {
        var x = stripTashkeel(s).lowercased()
        for (a, b) in [("أ", "ا"), ("إ", "ا"), ("آ", "ا"), ("ٱ", "ا"), ("ة", "ه"), ("ى", "ي"), ("ؤ", "و"), ("ئ", "ي")] {
            x = x.replacingOccurrences(of: a, with: b)
        }
        return x.re(#"[^\p{L}\p{N} ]"#, " ").re(#"\s+"#, " ").trimmed
    }

    static func clean(_ t: String) -> String {
        var s = t.re(#"\.(mp3|m4a|flac|ogg|opus|wav|aac|webm)$"#, "", ci: true)
        // bracketed noise
        if let r = try? NSRegularExpression(pattern: #"[(\[{【「]([^)\]}】」]*)[)\]}】」]"#) {
            let ns = s as NSString
            var out = ""
            var last = 0
            for m in r.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
                out += ns.substring(with: NSRange(location: last, length: m.range.location - last))
                let inner = ns.substring(with: m.range(at: 1))
                out += (inner.reContains(noiseWords, ci: true) || inner.trimmed.isEmpty) ? " " : ns.substring(with: m.range)
                last = m.range.location + m.range.length
            }
            out += ns.substring(from: last)
            s = out
        }
        s = s.re(#"\s*[(\[]?\s*(feat|ft|featuring|with)\.?\s+[^)\]]*[)\]]?"#, " ", ci: true)
        s = s.re(#"^\s*\d{1,3}\s*[.\-_)]\s*"#, "")
        s = s.re(#"[\p{So}\p{Cn}]"#, " ")
        s = s.replacingOccurrences(of: "_", with: " ").re(#"\s+"#, " ")
        return s.trimmingCharacters(in: CharacterSet(charactersIn: " -|•–—/:"))
    }

    private static func splitParts(_ s: String) -> [String] {
        s.reSplit(#"\s+[|｜/•]\s+|\s*[|｜]\s*"#).map(\.trimmed).filter { !$0.isEmpty }
    }
    static func splitArtists(_ a: String) -> [String] {
        a.reSplit(#"\s+-\s+|\s*[|｜/,&،]\s*|\s+x\s+|\s+(?i:feat\.?|ft\.?)\s+"#).map(clean).filter { !$0.trimmed.isEmpty }
    }
    private static func sameName(_ a: String, _ b: String) -> Bool {
        let x = normalise(a), y = normalise(b)
        return !x.isEmpty && !y.isEmpty && (x == y || x.contains(y) || y.contains(x))
    }

    static func parse(_ rawTitle: String, _ rawArtist: String) -> (titles: [String], artists: [String]) {
        var artists: [String] = []
        func addA(_ a: String) { if !artists.contains(a) { artists.append(a) } }
        splitArtists(rawArtist).forEach(addA)
        var titles: [String] = []
        func addT(_ t: String) { if !titles.contains(t) { titles.append(t) } }
        for part in splitParts(clean(rawTitle)) {
            let dash = part.reSplit(#"\s+[-–—]\s+"#)
            if dash.count >= 2 {
                let a = dash[0].trimmed, b = dash.dropFirst().joined(separator: " - ").trimmed
                if artists.contains(where: { sameName($0, a) }) { addT(b) }
                else if artists.contains(where: { sameName($0, b) }) { addT(a) }
                else { addT(b); addA(a) }
            } else { addT(part) }
        }
        var cleaned: [String] = []
        for t in titles {
            var x = t
            for a in artists where a.count > 2 { x = x.replacingOccurrences(of: a, with: "", options: .caseInsensitive) }
            let c = clean(x)
            let v = c.trimmed.isEmpty ? t : c
            if !v.trimmed.isEmpty && !cleaned.contains(v) { cleaned.append(v) }
        }
        return (cleaned.isEmpty ? [clean(rawTitle)] : cleaned, artists)
    }

    static func queries(title: String, artist: String, album: String?, durationMs: Int64) -> [LyricsQuery] {
        let p = parse(title, artist)
        var out: [LyricsQuery] = []
        func add(_ t: String?, _ a: String?) {
            guard let t, !t.trimmed.isEmpty else { return }
            let q = LyricsQuery(title: t, artist: a ?? "", album: album, durationMs: durationMs)
            if !out.contains(q) { out.append(q) }
        }
        let latinT = p.titles.filter { !Lyrics.isRTL($0) }, rtlT = p.titles.filter { Lyrics.isRTL($0) }
        let latinA = p.artists.filter { !Lyrics.isRTL($0) }, rtlA = p.artists.filter { Lyrics.isRTL($0) }
        add(latinT.first, latinA.first ?? p.artists.first)
        add(rtlT.first.map(stripTashkeel), (rtlA.first ?? p.artists.first).map(stripTashkeel))
        add(p.titles.first, p.artists.first)
        p.titles.forEach { add(stripTashkeel($0), "") }
        return Array(out.prefix(4))
    }

    private static func bigrams(_ s: String) -> [String] {
        let c = Array(s.replacingOccurrences(of: " ", with: ""))
        guard c.count >= 2 else { return [] }
        return (0..<(c.count - 1)).map { String(c[$0...$0 + 1]) }
    }

    static func similarity(_ a: String, _ b: String) -> Double {
        let x = normalise(a), y = normalise(b)
        if x.isEmpty || y.isEmpty { return 0 }
        if x == y { return 1 }
        let shorter = Double(min(x.count, y.count)), longer = Double(max(x.count, y.count))
        if (x.contains(y) || y.contains(x)) && shorter / longer >= 0.5 { return 0.9 }
        let ta = Set(x.split(separator: " ")), tb = Set(y.split(separator: " "))
        let jac = Double(ta.intersection(tb).count) / Double(max(1, ta.union(tb).count))
        let ba = bigrams(x), by = bigrams(y)
        var bb = by, hit = 0
        for g in ba { if let i = bb.firstIndex(of: g) { bb.remove(at: i); hit += 1 } }
        let dice = (ba.isEmpty || by.isEmpty) ? 0 : 2.0 * Double(hit) / Double(ba.count + by.count)
        return max(jac, dice)
    }

    struct Score { var total: Double; var title: Double; var artist: Double; var durationDiff: Double? }

    static func score(_ c: LyricsCandidate, _ queries: [LyricsQuery]) -> Score {
        let title = queries.map { similarity(clean(c.title), $0.title) }.max() ?? 0
        let aq = queries.map(\.artist).filter { !$0.trimmed.isEmpty }
        let artist: Double
        if aq.isEmpty { artist = 0.5 } else {
            let cand = splitArtists(c.artist).isEmpty ? [c.artist] : splitArtists(c.artist)
            artist = aq.map { q in cand.map { similarity($0, q) }.max() ?? 0 }.max() ?? 0
        }
        let dur = queries.first?.durationMs ?? 0
        let diff: Double? = (c.durationSec != nil && dur > 0) ? abs(Double(c.durationSec!) - Double(dur) / 1000) : nil
        var total = title * 0.6 + artist * 0.3
        if let d = diff { total += d <= 2 ? 0.2 : d <= 5 ? 0.1 : d <= 12 ? -0.1 : -0.5 }
        if c.syncedHint == true { total += 0.03 }
        return Score(total: total, title: title, artist: artist, durationDiff: diff)
    }

    static func isConfident(_ s: Score) -> Bool {
        if s.title < 0.6 { return false }
        if let d = s.durationDiff, d > 12 { return false }
        if let d = s.durationDiff, d <= 3 { return true }
        return s.title >= 0.75 && s.artist >= 0.5
    }
}
