import Foundation

/// Legal downloads only: Internet Archive (public domain / CC audio) and direct links the user owns.
@MainActor
final class Downloader: NSObject, ObservableObject {
    static let shared = Downloader()

    struct Item: Identifiable, Codable {
        var id: String
        var url: String
        var name: String
        var progress: Double = 0
        var bytes: Int64 = 0
        var total: Int64 = 0
        var state: Int = 0   // 0 queued, 1 running, 2 paused, 3 done, 4 failed
        var resumeData: Data?
    }

    @Published private(set) var items: [Item] = []
    var backgroundCompletion: (() -> Void)?
    private var tasks: [String: URLSessionDownloadTask] = [:]
    private lazy var session: URLSession = {
        URLSession(configuration: .default, delegate: self, delegateQueue: .main)
    }()
    private let file = FileLocations.support.appendingPathComponent("downloads.json")

    override init() {
        super.init()
        if let d = try? Data(contentsOf: file), let i = try? JSONDecoder().decode([Item].self, from: d) {
            items = i.map { var x = $0; if x.state == 1 || x.state == 0 { x.state = 2 }; return x }
        }
    }

    private func persist() { if let d = try? JSONEncoder().encode(items) { try? d.write(to: file, options: .atomic) } }

    func add(_ urlString: String, name: String? = nil) {
        guard let url = URL(string: urlString.trimmed), url.scheme?.hasPrefix("http") == true else {
            AppState.shared.show(T("رابط غير صالح", "Invalid link")); return
        }
        var n = name ?? url.lastPathComponent.removingPercentEncoding ?? "audio"
        if n.isEmpty { n = "audio" }
        let it = Item(id: UUID().uuidString, url: url.absoluteString, name: n)
        items.insert(it, at: 0)
        start(it.id)
    }

    func start(_ id: String) {
        guard let i = items.firstIndex(where: { $0.id == id }), let url = URL(string: items[i].url) else { return }
        let t: URLSessionDownloadTask
        if let rd = items[i].resumeData { t = session.downloadTask(withResumeData: rd) } else { t = session.downloadTask(with: url) }
        t.taskDescription = id
        tasks[id] = t
        items[i].state = 1; items[i].resumeData = nil
        t.resume(); persist()
    }

    func pause(_ id: String) {
        guard let t = tasks[id] else { return }
        if let i = items.firstIndex(where: { $0.id == id }) { items[i].state = 2 }
        t.cancel(byProducingResumeData: { data in
            Task { @MainActor in
                if let i = self.items.firstIndex(where: { $0.id == id }) { self.items[i].resumeData = data; self.items[i].state = 2; self.persist() }
            }
        })
        tasks[id] = nil
    }

    func remove(_ id: String) { tasks[id]?.cancel(); tasks[id] = nil; items.removeAll { $0.id == id }; persist() }
    func clearFinished() { items.removeAll { $0.state == 3 }; persist() }

    // MARK: Internet Archive search
    struct ArchiveHit: Identifiable { var id: String; var title: String; var creator: String }
    struct ArchiveFile: Identifiable { var id: String { name }; var name: String; var size: Int64; var url: String }

    static func searchArchive(_ q: String) async -> [ArchiveHit] {
        let query = "(\(q)) AND mediatype:(audio)"
        let u = Http.url("https://archive.org/advancedsearch.php", [("q", query), ("fl[]", "identifier"), ("fl[]", "title"), ("fl[]", "creator"),
                                                                    ("rows", "40"), ("output", "json")])
        guard let o = await Http.json(u) as? [String: Any], let docs = (o["response"] as? [String: Any])?["docs"] as? [[String: Any]] else { return [] }
        return docs.compactMap { d in
            guard let id = d["identifier"] as? String else { return nil }
            let creator = (d["creator"] as? String) ?? ((d["creator"] as? [String])?.first ?? "")
            return ArchiveHit(id: id, title: d["title"] as? String ?? id, creator: creator)
        }
    }

    static func archiveFiles(_ id: String) async -> [ArchiveFile] {
        guard let o = await Http.json(URL(string: "https://archive.org/metadata/\(id)")) as? [String: Any],
              let files = o["files"] as? [[String: Any]] else { return [] }
        return files.compactMap { f in
            guard let name = f["name"] as? String else { return nil }
            let ext = (name as NSString).pathExtension.lowercased()
            guard ["mp3", "m4a", "flac", "ogg", "opus", "wav"].contains(ext) else { return nil }
            let size = Int64(f["size"] as? String ?? "") ?? 0
            let enc = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name
            return ArchiveFile(name: name, size: size, url: "https://archive.org/download/\(id)/\(enc)")
        }
    }
}

extension Downloader: URLSessionDownloadDelegate {
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let id = downloadTask.taskDescription ?? ""
        Task { @MainActor in
            guard let i = self.items.firstIndex(where: { $0.id == id }) else { return }
            self.items[i].bytes = totalBytesWritten
            self.items[i].total = max(0, totalBytesExpectedToWrite)
            self.items[i].progress = totalBytesExpectedToWrite > 0 ? Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) : 0
        }
    }

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let id = downloadTask.taskDescription ?? ""
        let suggested = downloadTask.response?.suggestedFilename
        // move synchronously: the temp file is deleted when this method returns
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try? FileManager.default.moveItem(at: location, to: tmp)
        Task { @MainActor in
            guard let i = self.items.firstIndex(where: { $0.id == id }) else { return }
            var name = self.items[i].name
            if (name as NSString).pathExtension.isEmpty { name = suggested ?? (name + ".mp3") }
            var dest = FileLocations.downloads.appendingPathComponent(name)
            var n = 1
            while FileManager.default.fileExists(atPath: dest.path) {
                dest = FileLocations.downloads.appendingPathComponent("\((name as NSString).deletingPathExtension) (\(n)).\((name as NSString).pathExtension)"); n += 1
            }
            do { try FileManager.default.moveItem(at: tmp, to: dest); self.items[i].state = 3; self.items[i].progress = 1 } catch { self.items[i].state = 4 }
            self.tasks[id] = nil
            self.persist()
            Task { await LibraryStore.shared.scan() }
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        let id = task.taskDescription ?? ""
        let rd = (error as NSError).userInfo[NSURLSessionDownloadTaskResumeData] as? Data
        Task { @MainActor in
            guard let i = self.items.firstIndex(where: { $0.id == id }), self.items[i].state == 1 else { return }
            self.items[i].state = rd != nil ? 2 : 4
            self.items[i].resumeData = rd
            self.tasks[id] = nil
            self.persist()
        }
    }
}
