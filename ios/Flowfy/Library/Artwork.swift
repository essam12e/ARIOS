import UIKit
import AVFoundation
import MediaPlayer
import CryptoKit

/// Per-song artwork: embedded tags → iPhone library → online (iTunes Search), cached on disk.
@MainActor
final class Artwork: ObservableObject {
    static let shared = Artwork()
    @Published private(set) var revision = 0
    private let mem = NSCache<NSString, UIImage>()
    private let dir = FileLocations.dir(FileLocations.caches, "art")
    private var missing: Set<String> = []
    private var inflight: [String: Task<UIImage?, Never>] = [:]

    private func hash(_ k: String) -> String { Insecure.SHA1.hash(data: Data(k.utf8)).map { String(format: "%02x", $0) }.joined() }
    private func customURL(_ s: Song) -> URL { FileLocations.dir(FileLocations.support, "art").appendingPathComponent(hash(s.key) + ".jpg") }

    func image(for s: Song, size: CGFloat = 300) async -> UIImage? {
        let mk = "\(s.id)|\(Int(size))" as NSString
        if let i = mem.object(forKey: mk) { return i }
        if missing.contains(s.id) && !FileManager.default.fileExists(atPath: customURL(s).path) { return nil }
        if let t = inflight[s.id] {
            let img = await t.value
            return img.map { scaled($0, size, mk) }
        }
        let t = Task<UIImage?, Never> { await self.load(s) }
        inflight[s.id] = t
        let img = await t.value
        inflight[s.id] = nil
        guard let img else { missing.insert(s.id); return nil }
        return scaled(img, size, mk)
    }

    private func scaled(_ img: UIImage, _ size: CGFloat, _ key: NSString) -> UIImage {
        let px = size * UIScreen.main.scale
        let maxSide = max(img.size.width, img.size.height) * img.scale
        var out = img
        if maxSide > px * 1.2 {
            let r = px / maxSide
            let sz = CGSize(width: img.size.width * img.scale * r, height: img.size.height * img.scale * r)
            let fmt = UIGraphicsImageRendererFormat(); fmt.scale = 1
            out = UIGraphicsImageRenderer(size: sz, format: fmt).image { _ in img.draw(in: CGRect(origin: .zero, size: sz)) }
        }
        mem.setObject(out, forKey: key)
        return out
    }

    private func load(_ s: Song) async -> UIImage? {
        if let d = try? Data(contentsOf: customURL(s)), let i = UIImage(data: d) { return i }
        if let i = await Task.detached(priority: .utility, operation: { Self.embedded(s) }).value { return i }
        let cache = dir.appendingPathComponent(hash(s.key) + ".jpg")
        if let d = try? Data(contentsOf: cache), let i = UIImage(data: d) { return i }
        guard Prefs.shared.autoFetchArtwork else { return nil }
        if let d = await Self.online(s), let i = UIImage(data: d) {
            try? d.write(to: cache)
            return i
        }
        return nil
    }

    nonisolated private static func embedded(_ s: Song) -> UIImage? {
        if let id = s.mediaID {
            let q = MPMediaQuery.songs()
            q.addFilterPredicate(MPMediaPropertyPredicate(value: NSNumber(value: id), forProperty: MPMediaItemPropertyPersistentID))
            return q.items?.first?.artwork?.image(at: CGSize(width: 800, height: 800))
        }
        guard let u = s.fileURL else { return nil }
        let asset = AVURLAsset(url: u)
        for item in asset.commonMetadata where item.commonKey == .commonKeyArtwork {
            if let d = item.dataValue, let i = UIImage(data: d) { return i }
        }
        return nil
    }

    /// iTunes Search API, accepting only results whose title and artist really match.
    nonisolated private static func online(_ s: Song) async -> Data? {
        let qs = LyricsMatcher.queries(title: s.title, artist: s.artist == Song.unknown ? "" : s.artist, album: nil, durationMs: s.durationMs)
        for q in qs.prefix(2) {
            let term = "\(q.artist) \(q.title)".trimmed
            guard let o = await Http.json(Http.url("https://itunes.apple.com/search", [("term", term), ("entity", "song"), ("limit", "10")])) as? [String: Any],
                  let res = o["results"] as? [[String: Any]] else { continue }
            let best = res.map { r -> (Double, String?) in
                let t = LyricsMatcher.similarity(LyricsMatcher.clean(r["trackName"] as? String ?? ""), q.title)
                let a = q.artist.isEmpty ? 0.5 : LyricsMatcher.similarity(r["artistName"] as? String ?? "", q.artist)
                return (t * 0.6 + a * 0.4 + (t >= 0.6 && a >= 0.5 ? 0.2 : 0), r["artworkUrl100"] as? String)
            }.max { $0.0 < $1.0 }
            if let b = best, b.0 >= 0.75, let url = b.1?.replacingOccurrences(of: "100x100bb", with: "800x800bb"),
               let d = await Http.get(URL(string: url)) { return d }
        }
        return nil
    }

    func setCustom(_ s: Song, _ img: UIImage) {
        if let d = img.jpegData(compressionQuality: 0.9) { try? d.write(to: customURL(s)) }
        forget(s)
    }
    func removeCustom(_ s: Song) { try? FileManager.default.removeItem(at: customURL(s)); forget(s) }
    func refetch(_ s: Song) {
        try? FileManager.default.removeItem(at: dir.appendingPathComponent(hash(s.key) + ".jpg"))
        forget(s)
    }
    private func forget(_ s: Song) {
        mem.removeAllObjects(); missing.remove(s.id); revision += 1
    }
    func clearCache() {
        (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil))?.forEach { try? FileManager.default.removeItem(at: $0) }
        mem.removeAllObjects(); missing.removeAll(); revision += 1
    }
}
