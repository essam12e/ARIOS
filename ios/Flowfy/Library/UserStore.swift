import Foundation

/// Playlists, favourites and per-song lyric offsets, persisted as JSON.
@MainActor
final class UserStore: ObservableObject {
    static let shared = UserStore()
    @Published private(set) var playlists: [Playlist] = []
    @Published private(set) var favorites: Set<String> = []
    @Published private(set) var offsets: [String: Int64] = [:]

    private let file = FileLocations.support.appendingPathComponent("user_store.json")

    init() { load() }

    private func load() {
        guard let d = try? Data(contentsOf: file), let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return }
        fromJSON(o)
    }

    func fromJSON(_ o: [String: Any]) {
        playlists = (o["playlists"] as? [[String: Any]] ?? []).compactMap { p in
            guard let id = p["id"] as? String, let name = p["name"] as? String else { return nil }
            return Playlist(id: id, name: name, songKeys: p["songs"] as? [String] ?? [], created: (p["created"] as? NSNumber)?.doubleValue ?? 0)
        }
        favorites = Set(o["favorites"] as? [String] ?? [])
        var off: [String: Int64] = [:]
        (o["offsets"] as? [String: Any])?.forEach { k, v in off[k] = (v as? NSNumber)?.int64Value ?? 0 }
        offsets = off
    }

    func toJSON() -> [String: Any] {
        ["playlists": playlists.map { ["id": $0.id, "name": $0.name, "created": $0.created, "songs": $0.songKeys] as [String: Any] },
         "favorites": Array(favorites),
         "offsets": offsets.mapValues { NSNumber(value: $0) }]
    }

    private func save() {
        if let d = try? JSONSerialization.data(withJSONObject: toJSON()) { try? d.write(to: file, options: .atomic) }
    }

    func replaceAll(_ o: [String: Any]) { fromJSON(o); save() }

    func isFavorite(_ key: String) -> Bool { favorites.contains(key) }
    func toggleFavorite(_ key: String) {
        if favorites.contains(key) { favorites.remove(key) } else { favorites.insert(key) }
        save()
    }

    @discardableResult
    func createPlaylist(_ name: String, keys: [String] = []) -> Playlist {
        var seen = Set<String>()
        let p = Playlist(id: UUID().uuidString, name: name, songKeys: keys.filter { seen.insert($0).inserted }, created: Date().timeIntervalSince1970 * 1000)
        playlists.append(p); save(); return p
    }
    func renamePlaylist(_ id: String, _ name: String) { update(id) { $0.name = name } }
    func deletePlaylist(_ id: String) { playlists.removeAll { $0.id == id }; save() }
    func addToPlaylist(_ id: String, _ keys: [String]) {
        update(id) { p in for k in keys where !p.songKeys.contains(k) { p.songKeys.append(k) } }
    }
    func removeFromPlaylist(_ id: String, _ key: String) { update(id) { $0.songKeys.removeAll { $0 == key } } }
    func movePlaylist(_ id: String, from: IndexSet, to: Int) { update(id) { $0.songKeys.move(fromOffsets: from, toOffset: to) } }
    private func update(_ id: String, _ f: (inout Playlist) -> Void) {
        if let i = playlists.firstIndex(where: { $0.id == id }) { f(&playlists[i]); save() }
    }

    func renameKey(_ old: String, _ new: String) {
        guard old != new else { return }
        if favorites.remove(old) != nil { favorites.insert(new) }
        for i in playlists.indices { playlists[i].songKeys = playlists[i].songKeys.map { $0 == old ? new : $0 } }
        if let v = offsets.removeValue(forKey: old) { offsets[new] = v }
        save()
    }

    func offset(_ key: String) -> Int64 { offsets[key] ?? 0 }
    func setOffset(_ key: String, _ ms: Int64) {
        if ms == 0 { offsets.removeValue(forKey: key) } else { offsets[key] = ms }
        save()
    }
}

enum FileLocations {
    static let documents: URL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    static let support: URL = {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Flowfy", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()
    static let caches: URL = {
        let u = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Flowfy", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()
    static func dir(_ base: URL, _ name: String) -> URL {
        let u = base.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
    static var music: URL { dir(documents, "Music") }
    static var downloads: URL { dir(documents, "Flowfy Downloads") }
}
