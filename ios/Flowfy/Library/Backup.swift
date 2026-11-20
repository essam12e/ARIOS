import Foundation

/// Backup of settings, playlists, favourites, offsets, history and saved lyrics as one JSON file.
enum Backup {
    @MainActor static func make() -> URL? {
        var lyrics: [String: String] = [:]
        for f in LyricsStore.shared.allFiles() { if let t = try? String(contentsOf: f, encoding: .utf8) { lyrics[f.lastPathComponent] = t } }
        let hist = (try? JSONEncoder().encode(History.shared.events)).flatMap { try? JSONSerialization.jsonObject(with: $0) } ?? []
        let o: [String: Any] = ["app": "Flowfy", "version": 1, "created": Date().timeIntervalSince1970,
                                "prefs": Prefs.shared.export(), "user": UserStore.shared.toJSON(), "lyrics": lyrics, "history": hist]
        guard let d = try? JSONSerialization.data(withJSONObject: o, options: [.prettyPrinted]) else { return nil }
        let df = DateFormatter(); df.dateFormat = "yyyy-MM-dd"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Flowfy-backup-\(df.string(from: Date())).json")
        try? d.write(to: url)
        return url
    }

    @MainActor static func restore(from url: URL) async -> Bool {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard let d = try? Data(contentsOf: url), let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any], o["app"] as? String == "Flowfy" ||
                o["playlists"] != nil || o["user"] != nil else { return false }
        if let p = o["prefs"] as? [String: Any] { Prefs.shared.importValues(p) }
        if let u = o["user"] as? [String: Any] { UserStore.shared.replaceAll(u) }
        if let l = o["lyrics"] as? [String: String] {
            for (name, text) in l where name.hasSuffix(".lrc") && !name.contains("/") {
                try? text.write(to: LyricsStore.shared.directory.appendingPathComponent(name), atomically: true, encoding: .utf8)
            }
        }
        if let h = o["history"], let hd = try? JSONSerialization.data(withJSONObject: h), let ev = try? JSONDecoder().decode([History.Event].self, from: hd) {
            History.shared.replace(ev)
        }
        LyricsStore.shared.clearMemory()
        Player.shared.applyEQ()
        return true
    }
}
