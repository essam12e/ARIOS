import Foundation
import SwiftUI

/// Observable settings stored in UserDefaults.
final class Prefs: ObservableObject {
    static let shared = Prefs()
    private let d = UserDefaults.standard

    private func b(_ k: String, _ def: Bool) -> Bool { d.object(forKey: k) == nil ? def : d.bool(forKey: k) }
    private func i(_ k: String, _ def: Int) -> Int { d.object(forKey: k) == nil ? def : d.integer(forKey: k) }
    private func f(_ k: String, _ def: Double) -> Double { d.object(forKey: k) == nil ? def : d.double(forKey: k) }
    private func set(_ k: String, _ v: Any?) { objectWillChange.send(); d.set(v, forKey: k) }

    var lyricsFontSize: Int { get { i("lyrics_font", 26) } set { set("lyrics_font", newValue) } }
    var showTranslation: Bool { get { b("show_translation", true) } set { set("show_translation", newValue) } }
    var autoFetchLyrics: Bool { get { b("auto_lyrics", true) } set { set("auto_lyrics", newValue) } }
    var autoFetchArtwork: Bool { get { b("auto_art", true) } set { set("auto_art", newValue) } }
    var autoSyncLyrics: Bool { get { b("auto_sync", true) } set { set("auto_sync", newValue) } }
    var lyricsCenter: Bool { get { b("lyrics_center", false) } set { set("lyrics_center", newValue) } }
    var wordByWord: Bool { get { b("word_by_word", true) } set { set("word_by_word", newValue) } }
    var playbackSpeed: Double { get { f("speed", 1) } set { set("speed", newValue) } }
    var libraryTab: Int { get { i("library_tab", 3) } set { d.set(newValue, forKey: "library_tab") } }
    var songSort: Int { get { i("song_sort", 0) } set { set("song_sort", newValue) } }
    var sortDescending: Bool { get { b("sort_desc", false) } set { set("sort_desc", newValue) } }
    var albumGrid: Bool { get { b("album_grid", true) } set { set("album_grid", newValue) } }
    var useMusicLibrary: Bool { get { b("use_ipod", true) } set { set("use_ipod", newValue) } }

    var crossfadeSec: Int { get { i("crossfade", 0) } set { set("crossfade", newValue) } }
    var normalize: Bool { get { b("normalize", false) } set { set("normalize", newValue) } }
    var resumeLong: Bool { get { b("resume_long", true) } set { set("resume_long", newValue) } }
    var eqEnabled: Bool { get { b("eq_on", false) } set { set("eq_on", newValue) } }
    var eqGains: [Float] {
        get { (d.array(forKey: "eq_gains") as? [Double])?.map { Float($0) } ?? Array(repeating: 0, count: 10) }
        set { set("eq_gains", newValue.map { Double($0) }) }
    }
    var eqPreset: String { get { d.string(forKey: "eq_preset") ?? "flat" } set { set("eq_preset", newValue) } }
    var bassBoost: Double { get { f("bass", 0) } set { set("bass", newValue) } }
    var sleepFade: Bool { get { b("sleep_fade", true) } set { set("sleep_fade", newValue) } }
    var excludedFolders: Set<String> {
        get { Set(d.stringArray(forKey: "excluded") ?? []) }
        set { set("excluded", Array(newValue)) }
    }

    var accent: Int { get { i("accent", 6) } set { set("accent", newValue) } }
    var amoled: Bool { get { b("amoled", false) } set { set("amoled", newValue) } }
    var coverColors: Bool { get { b("dynamic_color", false) } set { set("dynamic_color", newValue) } }
    var visualizer: Bool { get { b("visualizer", true) } set { set("visualizer", newValue) } }

    func sourceEnabled(_ id: String) -> Bool { b("src_\(id)", true) }
    func setSourceEnabled(_ id: String, _ v: Bool) { set("src_\(id)", v) }

    var savedQueue: [String] { get { d.stringArray(forKey: "queue") ?? [] } set { d.set(newValue, forKey: "queue") } }
    var savedIndex: Int { get { i("queue_index", 0) } set { d.set(newValue, forKey: "queue_index") } }
    var savedPosition: Int64 { get { Int64(i("queue_pos", 0)) } set { d.set(Int(newValue), forKey: "queue_pos") } }
    var shuffle: Bool { get { b("shuffle", false) } set { d.set(newValue, forKey: "shuffle") } }
    var repeatMode: Int { get { i("repeat", 0) } set { d.set(newValue, forKey: "repeat") } }

    static let backupKeys = ["lyrics_font", "show_translation", "auto_lyrics", "auto_art", "auto_sync", "lyrics_center", "word_by_word",
                             "speed", "song_sort", "sort_desc", "album_grid", "crossfade", "normalize", "resume_long", "eq_on", "eq_gains",
                             "eq_preset", "bass", "sleep_fade", "excluded", "accent", "amoled", "dynamic_color", "visualizer",
                             "src_lrclib", "src_netease", "src_qq", "src_ovh", "use_ipod"]
    func export() -> [String: Any] {
        var m: [String: Any] = [:]
        for k in Self.backupKeys { if let v = d.object(forKey: k) { m[k] = v } }
        return m
    }
    func importValues(_ m: [String: Any]) {
        objectWillChange.send()
        for (k, v) in m where Self.backupKeys.contains(k) { d.set(v, forKey: k) }
    }
}
