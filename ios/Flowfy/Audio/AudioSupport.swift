import Foundation
import AVFoundation
import Accelerate

/// Spectrum bands for the visualizer, filled from a tap on the output mixer.
final class Spectrum {
    static let shared = Spectrum()
    static let bandCount = 32
    private let lock = NSLock()
    private var _bands = [Float](repeating: 0, count: Spectrum.bandCount)
    private var fftSetup: vDSP_DFT_Setup?
    private let n = 1024

    var bands: [Float] { lock.lock(); defer { lock.unlock() }; return _bands }

    func install(on node: AVAudioNode) {
        fftSetup = vDSP_DFT_zop_CreateSetup(nil, vDSP_Length(n), .FORWARD)
        node.installTap(onBus: 0, bufferSize: AVAudioFrameCount(n), format: nil) { [weak self] buf, _ in self?.process(buf) }
    }

    private func process(_ buf: AVAudioPCMBuffer) {
        guard let setup = fftSetup, let ch = buf.floatChannelData, Int(buf.frameLength) >= n else { return }
        var re = [Float](repeating: 0, count: n), im = [Float](repeating: 0, count: n)
        var oR = [Float](repeating: 0, count: n), oI = [Float](repeating: 0, count: n)
        for i in 0..<n {
            let w = 0.5 - 0.5 * cos(2 * Float.pi * Float(i) / Float(n - 1))
            re[i] = ch[0][i] * w
        }
        vDSP_DFT_Execute(setup, re, im, &oR, &oI)
        var mags = [Float](repeating: 0, count: n / 2)
        for i in 0..<(n / 2) { mags[i] = sqrt(oR[i] * oR[i] + oI[i] * oI[i]) }
        var out = [Float](repeating: 0, count: Self.bandCount)
        let minF: Float = 2, maxF = Float(n / 2 - 1)
        for b in 0..<Self.bandCount {
            let lo = Int(minF * pow(maxF / minF, Float(b) / Float(Self.bandCount)))
            let hi = max(lo + 1, Int(minF * pow(maxF / minF, Float(b + 1) / Float(Self.bandCount))))
            var m: Float = 0
            for k in lo..<min(hi, n / 2) { m = max(m, mags[k]) }
            out[b] = min(1, max(0, (20 * log10(m + 1e-6) + 10) / 50))
        }
        lock.lock()
        for i in 0..<Self.bandCount { _bands[i] = max(out[i], _bands[i] * 0.82) }
        lock.unlock()
    }
}

/// Rough loudness estimate (RMS of sampled blocks) → gain toward −14 LUFS-ish target, cached per song.
enum Loudness {
    private static var cache: [String: Float] = {
        (UserDefaults.standard.dictionary(forKey: "loudness") as? [String: Double])?.mapValues { Float($0) } ?? [:]
    }()

    @MainActor static func gain(for url: URL, key: String) async -> Float {
        if let g = cache[key] { return g }
        let g = await Task.detached(priority: .utility) { measure(url) }.value
        cache[key] = g
        UserDefaults.standard.set(cache.mapValues { Double($0) }, forKey: "loudness")
        return g
    }

    private static func measure(_ url: URL) -> Float {
        guard let f = try? AVAudioFile(forReading: url), f.length > 0,
              let buf = AVAudioPCMBuffer(pcmFormat: f.processingFormat, frameCapacity: 8192) else { return 1 }
        var sum = 0.0, count = 0.0
        let blocks = 40
        for b in 0..<blocks {
            f.framePosition = AVAudioFramePosition(Double(f.length) * (0.05 + 0.9 * Double(b) / Double(blocks)))
            guard (try? f.read(into: buf, frameCount: 8192)) != nil, let d = buf.floatChannelData else { continue }
            for i in 0..<Int(buf.frameLength) { let v = Double(d[0][i]); sum += v * v; count += 1 }
        }
        guard count > 0 else { return 1 }
        let rms = sqrt(sum / count)
        let db = 20 * log10(max(rms, 1e-5))
        let target = -16.0
        return Float(min(2.0, max(0.35, pow(10, (target - db) / 20))))
    }
}

/// Resume points for long audio (podcasts, audiobooks).
final class Positions {
    static let shared = Positions()
    private var map: [String: Int64] = (UserDefaults.standard.dictionary(forKey: "positions") as? [String: Int]).map { $0.mapValues { Int64($0) } } ?? [:]
    func get(_ key: String) -> Int64 { map[key] ?? 0 }
    func set(_ key: String, _ ms: Int64) {
        if ms <= 0 { map[key] = nil } else { map[key] = ms }
        UserDefaults.standard.set(map.mapValues { Int($0) }, forKey: "positions")
    }
}

/// Listening history for statistics and "Wrapped".
@MainActor
final class History: ObservableObject {
    static let shared = History()
    struct Event: Codable { var key: String; var title: String; var artist: String; var album: String; var at: Double; var ms: Int64 }
    @Published private(set) var events: [Event] = []
    private let file = FileLocations.support.appendingPathComponent("history.json")

    init() {
        if let d = try? Data(contentsOf: file), let e = try? JSONDecoder().decode([Event].self, from: d) { events = e }
    }

    func started(_ s: Song) {}

    func played(_ s: Song, ms: Int64) {
        guard ms >= 10_000 else { return }
        events.append(Event(key: s.key, title: s.title, artist: s.artist, album: s.album, at: Date().timeIntervalSince1970, ms: ms))
        if events.count > 20000 { events.removeFirst(events.count - 20000) }
        save()
    }

    func save() { if let d = try? JSONEncoder().encode(events) { try? d.write(to: file, options: .atomic) } }
    func clear() { events = []; save() }
    func replace(_ e: [Event]) { events = e; save() }

    func playCount(_ key: String) -> Int { events.filter { $0.key == key }.count }
    func lastPlayed(_ key: String) -> Double { events.last { $0.key == key }?.at ?? 0 }
}
