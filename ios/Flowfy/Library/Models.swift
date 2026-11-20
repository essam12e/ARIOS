import Foundation

struct Song: Identifiable, Hashable {
    /// "f:<relative path>" for files in Documents, "m:<persistentID>" for the iPhone music library.
    var id: String
    var fileURL: URL?
    var mediaID: UInt64?
    var title: String
    var artist: String
    var album: String
    var durationMs: Int64
    var folder: String
    var dateAdded: Date
    var track: Int
    var year: Int
    var ext: String
    var size: Int64

    var key: String { Song.makeKey(title, artist, durationMs) }
    var albumKey: String { "\(album.lowercased())|\(album == Song.unknown ? artist.lowercased() : "")" }
    var isFile: Bool { fileURL != nil }

    static let unknown = T("غير معروف", "Unknown")
    static func makeKey(_ t: String, _ a: String, _ d: Int64) -> String {
        "\(t.trimmed.lowercased())|\(a.trimmed.lowercased())|\((d + 500) / 1000)"
    }

    static func == (a: Song, b: Song) -> Bool { a.id == b.id && a.title == b.title && a.artist == b.artist }
    func hash(into h: inout Hasher) { h.combine(id) }
}

struct Album: Identifiable, Hashable {
    var id: String
    var title: String
    var artist: String
    var year: Int
    var songs: [Song]
    var cover: Song { songs[0] }
}

struct Artist: Identifiable, Hashable {
    var id: String { name.lowercased() }
    var name: String
    var songs: [Song]
    var albums: [Album]
    var cover: Song { songs[0] }
}

struct Folder: Identifiable, Hashable {
    var id: String { path }
    var path: String
    var name: String
    var songs: [Song]
}

struct Playlist: Identifiable, Codable, Hashable {
    var id: String
    var name: String
    var songKeys: [String]
    var created: Double
}

/// Localised text: Arabic first, English when the phone is not in Arabic.
let isArabicUI: Bool = {
    if let forced = UserDefaults.standard.string(forKey: "ui_lang"), !forced.isEmpty { return forced == "ar" }
    return (Locale.preferredLanguages.first ?? "ar").hasPrefix("ar")
}()
func T(_ ar: String, _ en: String) -> String { isArabicUI ? ar : en }

func fmtTime(_ ms: Int64) -> String {
    let s = max(0, ms / 1000)
    if s >= 3600 { return String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) }
    return String(format: "%d:%02d", s / 60, s % 60)
}

/// Alphabetical comparison that puts Latin before Arabic and ignores leading "the"/"ال".
func sortName(_ s: String) -> String {
    var x = LyricsMatcher.stripTashkeel(s).trimmed.lowercased()
    if x.hasPrefix("the ") { x = String(x.dropFirst(4)) }
    return x
}
func nameLess(_ a: String, _ b: String) -> Bool {
    sortName(a).localizedStandardCompare(sortName(b)) == .orderedAscending
}

/// First letter used by the alphabet bar.
func indexLetter(_ s: String) -> String {
    let x = sortName(s)
    guard let c = x.first else { return "#" }
    if c.isLetter {
        let up = String(c).uppercased()
        let ar = ["أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ة": "ه", "ى": "ي"]
        return ar[up] ?? up
    }
    return "#"
}
