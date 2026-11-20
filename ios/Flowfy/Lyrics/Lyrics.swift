import Foundation

struct LyricWord: Hashable {
    var start: Int64
    var end: Int64
    var text: String
}

struct LyricLine: Hashable {
    var start: Int64
    var end: Int64
    var text: String
    var words: [LyricWord]
    var translation: String? = nil
    var realWordTiming: Bool = false
}

struct Lyrics: Hashable {
    var lines: [LyricLine]
    var synced: Bool
    var source: String

    var hasTranslation: Bool { lines.contains { !($0.translation ?? "").isEmpty } }

    /// Index of the line active at `pos` (ms), -1 before the first line.
    func index(at pos: Int64) -> Int {
        guard synced, !lines.isEmpty else { return -1 }
        var lo = 0, hi = lines.count - 1, ans = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if lines[mid].start <= pos { ans = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        return ans
    }

    static func isRTL(_ s: String) -> Bool {
        s.unicodeScalars.contains { (0x0590...0x08FF).contains($0.value) || (0xFB1D...0xFEFC).contains($0.value) }
    }
}

enum LrcParser {
    private static let timeTag = try! NSRegularExpression(pattern: #"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]"#)
    private static let wordTag = try! NSRegularExpression(pattern: #"<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>"#)
    private static let offsetTag = try! NSRegularExpression(pattern: #"\[offset:\s*([+-]?\d+)\s*\]"#, options: .caseInsensitive)
    private static let metaTag = try! NSRegularExpression(pattern: #"^\[(ar|ti|al|by|re|ve|au|length|tool|#):.*\]$"#, options: .caseInsensitive)
    private static let yrcLine = try! NSRegularExpression(pattern: #"^\[(\d+),(\d+)\](.*)$"#)
    private static let yrcWord = try! NSRegularExpression(pattern: #"\((\d+),(\d+),\d+\)([^(]*)"#)

    private static func groups(_ m: NSTextCheckingResult, _ s: String) -> [String] {
        (0..<m.numberOfRanges).map { i in
            let r = m.range(at: i)
            guard r.location != NSNotFound, let rr = Range(r, in: s) else { return "" }
            return String(s[rr])
        }
    }
    private static func ms(_ g: [String]) -> Int64 {
        let min = Int64(g[1]) ?? 0, sec = Int64(g[2]) ?? 0
        let frac = g[3]
        let f: Int64
        switch frac.count {
        case 0: f = 0
        case 1: f = (Int64(frac) ?? 0) * 100
        case 2: f = (Int64(frac) ?? 0) * 10
        default: f = Int64(frac.prefix(3)) ?? 0
        }
        return min * 60_000 + sec * 1000 + f
    }
    private static func matches(_ re: NSRegularExpression, _ s: String) -> [NSTextCheckingResult] {
        re.matches(in: s, range: NSRange(s.startIndex..., in: s))
    }
    private static func full(_ re: NSRegularExpression, _ s: String) -> NSTextCheckingResult? {
        guard let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)), m.range.location == 0,
              m.range.length == (s as NSString).length else { return nil }
        return m
    }

    static func looksSynced(_ text: String) -> Bool {
        !matches(timeTag, text).isEmpty || text.split(separator: "\n").contains { full(yrcLine, $0.trimmingCharacters(in: .whitespaces)) != nil }
    }

    static func parse(_ text: String, source: String, durationMs: Int64 = 0, translation: String? = nil) -> Lyrics? {
        let raw = text.replacingOccurrences(of: "\r", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty { return nil }
        let rawLines = raw.components(separatedBy: "\n")
        if rawLines.filter({ full(yrcLine, $0.trimmingCharacters(in: .whitespaces)) != nil }).count >= 3,
           let y = parseYrc(rawLines, source: source, durationMs: durationMs) {
            return mergeTranslation(y, translation)
        }
        if matches(timeTag, raw).isEmpty {
            let lines = rawLines.map { $0.trimmingCharacters(in: .whitespaces) }.filter { full(metaTag, $0) == nil }
            return Lyrics(lines: lines.map { LyricLine(start: 0, end: 0, text: $0, words: []) }, synced: false, source: source)
        }
        var offset: Int64 = 0
        if let m = offsetTag.firstMatch(in: raw, range: NSRange(raw.startIndex..., in: raw)) { offset = Int64(groups(m, raw)[1]) ?? 0 }
        struct Entry { var time: Int64; var body: String; var order: Int }
        var entries: [Entry] = []
        var order = 0
        for line0 in rawLines {
            let line = line0.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || full(metaTag, line) != nil || full(offsetTag, line) != nil { continue }
            var stamps: [Int64] = []
            var rest = line
            while let m = timeTag.firstMatch(in: rest, range: NSRange(rest.startIndex..., in: rest)), m.range.location == 0 {
                stamps.append(ms(groups(m, rest)))
                rest = String((rest as NSString).substring(from: m.range.length))
            }
            if stamps.isEmpty { continue }
            for st in stamps { entries.append(Entry(time: max(0, st - offset), body: rest, order: order)); order += 1 }
        }
        if entries.isEmpty { return nil }
        entries.sort { $0.time != $1.time ? $0.time < $1.time : $0.order < $1.order }
        var grouped: [(Int64, [String])] = []
        for e in entries {
            if let last = grouped.last, last.0 == e.time { grouped[grouped.count - 1].1.append(e.body) }
            else { grouped.append((e.time, [e.body])) }
        }
        var lines: [LyricLine] = []
        for (i, g) in grouped.enumerated() {
            let next = i + 1 < grouped.count ? grouped[i + 1].0 : (durationMs > g.0 ? durationMs : g.0 + 6000)
            let trans = g.1.dropFirst().map { stripWordTags($0).trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.joined(separator: " / ")
            lines.append(buildLine(start: g.0, end: next, body: g.1[0], translation: trans.isEmpty ? nil : trans))
        }
        var out: [LyricLine] = []
        for l in lines {
            if l.text.trimmingCharacters(in: .whitespaces).isEmpty, out.last?.text.trimmingCharacters(in: .whitespaces).isEmpty == true { continue }
            out.append(l)
        }
        while let f = out.first, f.text.trimmingCharacters(in: .whitespaces).isEmpty { out.removeFirst() }
        if out.isEmpty { out = lines }
        return mergeTranslation(Lyrics(lines: out, synced: true, source: source), translation)
    }

    private static func stripWordTags(_ s: String) -> String {
        wordTag.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: "")
    }

    private static func buildLine(start: Int64, end: Int64, body: String, translation: String?) -> LyricLine {
        let ms_ = matches(wordTag, body)
        let ns = body as NSString
        if !ms_.isEmpty {
            var words: [LyricWord] = []
            let pre = ns.substring(to: ms_[0].range.location)
            if !pre.trimmingCharacters(in: .whitespaces).isEmpty { words.append(LyricWord(start: start, end: ms(groups(ms_[0], body)), text: pre)) }
            for (i, m) in ms_.enumerated() {
                let ws = ms(groups(m, body))
                let textStart = m.range.location + m.range.length
                let textEnd = i + 1 < ms_.count ? ms_[i + 1].range.location : ns.length
                let t = ns.substring(with: NSRange(location: textStart, length: max(0, textEnd - textStart)))
                let we = i + 1 < ms_.count ? ms(groups(ms_[i + 1], body)) : end
                if !t.isEmpty { words.append(LyricWord(start: ws, end: max(ws, we), text: t)) }
            }
            let text = words.map(\.text).joined().replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
            return LyricLine(start: start, end: end, text: text, words: normaliseWords(words), translation: translation, realWordTiming: true)
        }
        let text = body.trimmingCharacters(in: .whitespaces)
        return LyricLine(start: start, end: end, text: text, words: estimateWords(text, start: start, end: end), translation: translation)
    }

    private static func normaliseWords(_ words: [LyricWord]) -> [LyricWord] {
        var out: [LyricWord] = []
        for w in words {
            let t = w.text.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            if t.trimmingCharacters(in: .whitespaces).isEmpty {
                if !out.isEmpty { out[out.count - 1].text = out[out.count - 1].text.trimmingCharacters(in: .whitespaces) + " " }
                continue
            }
            if !out.isEmpty, t.hasPrefix(" "), !out[out.count - 1].text.hasSuffix(" ") { out[out.count - 1].text += " " }
            var nw = w; nw.text = String(t.drop(while: { $0 == " " }))
            out.append(nw)
        }
        if !out.isEmpty { out[out.count - 1].text = out[out.count - 1].text.trimmingCharacters(in: .whitespaces) }
        return out
    }

    /// Estimated word timings when the file only has line timestamps.
    static func estimateWords(_ text: String, start: Int64, end: Int64) -> [LyricWord] {
        let re = try! NSRegularExpression(pattern: #"\S+\s*"#)
        let parts = matches(re, text).compactMap { Range($0.range, in: text).map { String(text[$0]) } }
        if parts.isEmpty { return [] }
        let chars = parts.reduce(0) { $0 + max(1, $1.trimmingCharacters(in: .whitespaces).count) }
        let lineDur = max(300, end - start)
        let sung = min(lineDur, max(600, Int64(chars) * 95 + 500))
        var t = start
        var out: [LyricWord] = []
        for (i, p) in parts.enumerated() {
            let d = sung * Int64(max(1, p.trimmingCharacters(in: .whitespaces).count)) / Int64(chars)
            let last = i == parts.count - 1
            out.append(LyricWord(start: t, end: last ? start + sung : t + d, text: last ? p.trimmingCharacters(in: .whitespaces) : p))
            t += d
        }
        return out
    }

    private static func parseYrc(_ rawLines: [String], source: String, durationMs: Int64) -> Lyrics? {
        var lines: [LyricLine] = []
        for l in rawLines {
            let s = l.trimmingCharacters(in: .whitespaces)
            guard let m = full(yrcLine, s) else { continue }
            let g = groups(m, s)
            let start = Int64(g[1]) ?? 0, dur = Int64(g[2]) ?? 0
            let words = matches(yrcWord, g[3]).map { wm -> LyricWord in
                let gg = groups(wm, g[3])
                let ws = Int64(gg[1]) ?? 0
                return LyricWord(start: ws, end: ws + (Int64(gg[2]) ?? 0), text: gg[3])
            }.filter { !$0.text.isEmpty }
            if words.isEmpty { continue }
            lines.append(LyricLine(start: start, end: start + dur, text: words.map(\.text).joined().trimmingCharacters(in: .whitespaces),
                                   words: normaliseWords(words), realWordTiming: true))
        }
        if lines.isEmpty { return nil }
        lines.sort { $0.start < $1.start }
        for i in lines.indices {
            let next = i + 1 < lines.count ? lines[i + 1].start : max(lines[i].end, durationMs)
            lines[i].end = max(lines[i].end, next)
        }
        return Lyrics(lines: lines, synced: true, source: source)
    }

    private static func mergeTranslation(_ l: Lyrics, _ translation: String?) -> Lyrics {
        guard let translation, !translation.isEmpty, l.synced, let t = parse(translation, source: l.source), t.synced else { return l }
        let byTime = t.lines.filter { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty }
        if byTime.isEmpty { return l }
        var r = l
        for i in r.lines.indices where r.lines[i].translation == nil && !r.lines[i].text.isEmpty {
            if let m = byTime.min(by: { abs($0.start - r.lines[i].start) < abs($1.start - r.lines[i].start) }),
               abs(m.start - r.lines[i].start) <= 600, m.text != r.lines[i].text {
                r.lines[i].translation = m.text
            }
        }
        return r
    }
}

enum LrcWriter {
    static func stamp(_ ms: Int64) -> String {
        let m = ms / 60000, s = (ms / 1000) % 60, cs = (ms % 1000) / 10
        return String(format: "[%02lld:%02lld.%02lld]", m, s, cs)
    }
    private static func wordStamp(_ ms: Int64) -> String { stamp(ms).replacingOccurrences(of: "[", with: "<").replacingOccurrences(of: "]", with: ">") }

    static func write(_ l: Lyrics, title: String? = nil, artist: String? = nil) -> String {
        var s = ""
        if let title, !title.isEmpty { s += "[ti:\(title)]\n" }
        if let artist, !artist.isEmpty { s += "[ar:\(artist)]\n" }
        s += "[by:Flowfy]\n"
        for line in l.lines {
            if !l.synced { s += line.text + "\n"; continue }
            s += stamp(line.start)
            if line.realWordTiming, !line.words.isEmpty { for w in line.words { s += wordStamp(w.start) + w.text } }
            else { s += line.text }
            s += "\n"
            if let t = line.translation, !t.isEmpty { s += stamp(line.start) + t + "\n" }
        }
        return s
    }
}
