import Foundation
import AVFoundation
import MediaPlayer
import UIKit

/// Playback engine: two AVAudioPlayerNodes (crossfade) → mixer → 10-band EQ → time/pitch → output.
@MainActor
final class Player: ObservableObject {
    static let shared = Player()

    @Published private(set) var queue: [Song] = []
    @Published private(set) var index: Int = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var loading = false
    @Published var shuffle: Bool = Prefs.shared.shuffle
    @Published var repeatMode: Int = Prefs.shared.repeatMode   // 0 off, 1 all, 2 one
    @Published private(set) var sleepAt: Date?
    @Published var sleepEndOfSong = false
    @Published private(set) var speed: Double = Prefs.shared.playbackSpeed
    @Published private(set) var pitch: Double = 0

    var current: Song? { queue.indices.contains(index) ? queue[index] : nil }
    var durationMs: Int64 { file.map { Int64(Double($0.length) / $0.processingFormat.sampleRate * 1000) } ?? (current?.durationMs ?? 0) }

    private let engine = AVAudioEngine()
    private let nodes = [AVAudioPlayerNode(), AVAudioPlayerNode()]
    private let mixers = [AVAudioMixerNode(), AVAudioMixerNode()]
    private let sum = AVAudioMixerNode()
    let eq = AVAudioUnitEQ(numberOfBands: 10)
    private let timePitch = AVAudioUnitTimePitch()
    private var formats: [AVAudioFormat?] = [nil, nil]
    private var active = 0
    private var file: AVAudioFile?
    private var startFrame: AVAudioFramePosition = 0
    private var pausedPos: Int64 = 0
    private var generation = 0
    private var trackGain: [Float] = [1, 1]
    private var crossfading = false
    private var ticker: Timer?
    private var originalQueue: [Song]?
    private var playedMs: Int64 = 0
    private var lastTick = Date()
    private var engineReady = false
    static let eqFreqs: [Float] = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]

    init() {
        setupEngine()
        setupSession()
        setupRemote()
        applyEQ()
        ticker = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in Task { @MainActor in self?.tick() } }
    }

    // MARK: engine

    private func setupEngine() {
        nodes.forEach { engine.attach($0) }
        mixers.forEach { engine.attach($0) }
        engine.attach(sum); engine.attach(eq); engine.attach(timePitch)
        let fmt = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 2)
        for i in 0..<2 {
            engine.connect(nodes[i], to: mixers[i], format: fmt)
            formats[i] = fmt
            engine.connect(mixers[i], to: sum, fromBus: 0, toBus: AVAudioNodeBus(i), format: fmt)
        }
        engine.connect(sum, to: eq, format: fmt)
        engine.connect(eq, to: timePitch, format: fmt)
        engine.connect(timePitch, to: engine.mainMixerNode, format: fmt)
        for (i, f) in Self.eqFreqs.enumerated() {
            let b = eq.bands[i]
            b.filterType = i == 0 ? .lowShelf : (i == 9 ? .highShelf : .parametric)
            b.frequency = f; b.bandwidth = 1.0; b.gain = 0; b.bypass = false
        }
        timePitch.rate = Float(speed)
        engine.prepare()
        Spectrum.shared.install(on: engine.mainMixerNode)
        NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.recoverEngine() }
        }
    }

    private func startEngine() {
        if !engine.isRunning {
            do { try AVAudioSession.sharedInstance().setActive(true); try engine.start(); engineReady = true } catch { engineReady = false }
        }
    }

    /// Starting a node while the engine is stopped raises an exception, so always go through here.
    private func playNode(_ i: Int) {
        startEngine()
        guard engine.isRunning else { isPlaying = false; return }
        nodes[i].play()
    }

    private func recoverEngine() {
        let pos = positionMs()
        let wasPlaying = isPlaying
        guard file != nil else { return }
        startEngine()
        seek(to: pos)
        if wasPlaying { playNode(active) } else { isPlaying = false }
    }

    func applyEQ() {
        let p = Prefs.shared
        let gains = p.eqGains
        let bass = Float(p.bassBoost)
        for i in 0..<10 {
            var g = i < gains.count ? gains[i] : 0
            if i < 3 { g += bass * (i == 0 ? 1 : i == 1 ? 0.8 : 0.4) }
            eq.bands[i].gain = max(-12, min(15, g))
        }
        eq.bypass = !p.eqEnabled && bass == 0
        eq.globalGain = p.eqEnabled ? -2 : 0
    }

    func setSpeed(_ v: Double) { speed = v; Prefs.shared.playbackSpeed = v; timePitch.rate = Float(v); updateNowPlaying() }
    func setPitch(_ cents: Double) { pitch = cents; timePitch.pitch = Float(cents) }

    // MARK: session / remote

    private func setupSession() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, mode: .default, policy: .longFormAudio)
        let nc = NotificationCenter.default
        nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            let type = (n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap { AVAudioSession.InterruptionType(rawValue: $0) }
            let opts = (n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map { AVAudioSession.InterruptionOptions(rawValue: $0) } ?? []
            Task { @MainActor in
                guard let self else { return }
                if type == .began { self.pausedPos = self.positionMs(); self.isPlaying = false; self.updateNowPlaying() }
                else if type == .ended, opts.contains(.shouldResume) { self.play() }
            }
        }
        nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] n in
            let reason = (n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap { AVAudioSession.RouteChangeReason(rawValue: $0) }
            Task { @MainActor in if reason == .oldDeviceUnavailable { self?.pause() } }
        }
        nc.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.saveState() }
        }
    }

    private func setupRemote() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in Task { @MainActor in self?.play() }; return .success }
        c.pauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.pause() }; return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.toggle() }; return .success }
        c.nextTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.next() }; return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.previous() }; return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            if let e = e as? MPChangePlaybackPositionCommandEvent { Task { @MainActor in self?.seek(to: Int64(e.positionTime * 1000)) } }
            return .success
        }
        c.likeCommand.isEnabled = false; c.dislikeCommand.isEnabled = false
    }

    private var artworkCache: (String, MPMediaItemArtwork)?

    func updateNowPlaying() {
        guard let s = current else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: s.title,
            MPMediaItemPropertyArtist: s.artist,
            MPMediaItemPropertyAlbumTitle: s.album,
            MPMediaItemPropertyPlaybackDuration: Double(durationMs) / 1000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(positionMs()) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? speed : 0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
        ]
        if let c = artworkCache, c.0 == s.id { info[MPMediaItemPropertyArtwork] = c.1 }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        if artworkCache?.0 != s.id {
            Task {
                if let img = await Artwork.shared.image(for: s, size: 600) {
                    let art = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
                    self.artworkCache = (s.id, art)
                    if self.current?.id == s.id {
                        var i = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                        i[MPMediaItemPropertyArtwork] = art
                        MPNowPlayingInfoCenter.default().nowPlayingInfo = i
                    }
                }
            }
        }
    }

    // MARK: queue control

    func play(_ songs: [Song], start: Int = 0, shuffled: Bool = false) {
        guard !songs.isEmpty else { return }
        var list = songs
        var i = max(0, min(start, songs.count - 1))
        originalQueue = nil
        if shuffled {
            originalQueue = songs
            list.shuffle(); i = 0
            if !shuffle { shuffle = true; Prefs.shared.shuffle = true }
        } else if shuffle {
            originalQueue = songs
            let first = list.remove(at: i)
            list.shuffle(); list.insert(first, at: 0); i = 0
        }
        queue = list
        load(i, startMs: nil, autoplay: true)
    }

    func playNext(_ songs: [Song]) {
        if queue.isEmpty { play(songs); return }
        queue.insert(contentsOf: songs, at: min(queue.count, index + 1)); saveState()
    }
    func addToQueue(_ songs: [Song]) {
        if queue.isEmpty { play(songs); return }
        queue.append(contentsOf: songs); saveState()
    }
    func removeFromQueue(_ i: Int) {
        guard queue.indices.contains(i), i != index else { return }
        queue.remove(at: i); if i < index { index -= 1 }; saveState()
    }
    func moveQueue(from: IndexSet, to: Int) {
        let cur = current
        queue.move(fromOffsets: from, toOffset: to)
        if let cur, let n = queue.firstIndex(where: { $0.id == cur.id }) { index = n }
        saveState()
    }
    func jump(to i: Int) { if queue.indices.contains(i) { load(i, startMs: nil, autoplay: true) } }

    func toggleShuffle() {
        shuffle.toggle(); Prefs.shared.shuffle = shuffle
        guard let cur = current else { return }
        if shuffle {
            originalQueue = queue
            var rest = queue; rest.remove(at: index); rest.shuffle()
            queue = [cur] + rest; index = 0
        } else if let o = originalQueue {
            queue = o; index = o.firstIndex(where: { $0.id == cur.id }) ?? 0; originalQueue = nil
        }
        saveState()
    }
    func cycleRepeat() { repeatMode = (repeatMode + 1) % 3; Prefs.shared.repeatMode = repeatMode }

    func toggle() { isPlaying ? pause() : play() }

    func play() {
        guard current != nil else { return }
        if file == nil { load(index, startMs: pausedPos, autoplay: true); return }
        let stopped = !engine.isRunning
        startEngine()
        // the engine stopped (interruption / route change): reschedule from the saved position
        if stopped { seek(to: pausedPos) }
        mixers[active].outputVolume = trackGain[active]
        isPlaying = true
        if !nodes[active].isPlaying { playNode(active) }
        updateNowPlaying()
    }

    func pause() {
        pausedPos = positionMs()
        nodes.forEach { $0.pause() }
        if crossfading { finishCrossfade() }
        isPlaying = false
        updateNowPlaying(); saveState()
    }

    func next() {
        guard !queue.isEmpty else { return }
        if index + 1 < queue.count { load(index + 1, startMs: nil, autoplay: true) }
        else if repeatMode == 1 { load(0, startMs: nil, autoplay: true) }
        else { load(index, startMs: 0, autoplay: false) }
    }

    func previous() {
        if positionMs() > 3000 || index == 0 { seek(to: 0); if !isPlaying { play() } }
        else { load(index - 1, startMs: nil, autoplay: true) }
    }

    // MARK: loading

    func load(_ i: Int, startMs: Int64?, autoplay: Bool, crossfade: Bool = false) {
        guard queue.indices.contains(i) else { return }
        rememberPosition()
        commitPlayed()
        generation += 1
        let g = generation
        let song = queue[i]
        index = i
        loading = true
        if !crossfade {
            nodes.forEach { $0.stop() }
            if crossfading { crossfading = false }
            mixers.forEach { $0.outputVolume = 1 }
        }
        Task {
            let url = await LibraryStore.playableURL(song)
            guard g == self.generation else { return }
            self.loading = false
            guard let url, let f = try? AVAudioFile(forReading: url) else {
                // unreadable: skip ahead
                if autoplay && i + 1 < self.queue.count { self.load(i + 1, startMs: nil, autoplay: true) } else { self.isPlaying = false }
                return
            }
            if crossfade { self.active = 1 - self.active }
            let a = self.active
            self.file = f
            if self.formats[a] != f.processingFormat {
                self.nodes[a].stop()
                self.engine.disconnectNodeOutput(self.nodes[a])
                self.engine.connect(self.nodes[a], to: self.mixers[a], format: f.processingFormat)
                self.formats[a] = f.processingFormat
            }
            if Prefs.shared.normalize { self.trackGain[a] = await Loudness.gain(for: url, key: song.key) } else { self.trackGain[a] = 1 }
            guard g == self.generation else { return }
            var start = startMs ?? 0
            if startMs == nil, Prefs.shared.resumeLong, song.durationMs > 20 * 60_000 { start = Positions.shared.get(song.key) }
            self.schedule(from: start, generation: g)
            self.startEngine()
            if crossfade {
                self.mixers[a].outputVolume = 0
                self.isPlaying = true
                self.playNode(a)
                self.beginCrossfade(from: 1 - a, to: a)
            } else {
                self.mixers[a].outputVolume = self.trackGain[a]
                if autoplay { self.isPlaying = true; self.playNode(a) } else { self.isPlaying = false; self.pausedPos = start }
            }
            self.updateNowPlaying()
            self.saveState()
            History.shared.started(song)
        }
    }

    private func schedule(from ms: Int64, generation g: Int) {
        guard let f = file else { return }
        let node = nodes[active]
        let sr = f.processingFormat.sampleRate
        let frame = AVAudioFramePosition(Double(max(0, ms)) / 1000 * sr)
        startFrame = min(frame, max(0, f.length - 1))
        pausedPos = Int64(Double(startFrame) / sr * 1000)
        let count = AVAudioFrameCount(max(1, f.length - startFrame))
        node.scheduleSegment(f, startingFrame: startFrame, frameCount: count, at: nil, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            Task { @MainActor in
                guard let self, g == self.generation else { return }
                self.trackEnded()
            }
        }
    }

    func seek(to ms: Int64) {
        guard file != nil else { pausedPos = ms; return }
        if crossfading { finishCrossfade() }
        generation += 1
        let g = generation
        let wasPlaying = isPlaying
        nodes[active].stop()
        schedule(from: max(0, min(ms, durationMs - 200)), generation: g)
        if wasPlaying { startEngine(); playNode(active) }
        updateNowPlaying()
    }

    func positionMs() -> Int64 {
        guard let f = file else { return pausedPos }
        let node = nodes[active]
        if node.isPlaying || isPlaying, let nt = node.lastRenderTime, nt.isSampleTimeValid, let pt = node.playerTime(forNodeTime: nt) {
            let ms = Int64(Double(startFrame + max(0, pt.sampleTime)) / f.processingFormat.sampleRate * 1000)
            return min(max(0, ms), durationMs)
        }
        return pausedPos
    }

    private func trackEnded() {
        commitPlayed()
        if let s = current { Positions.shared.set(s.key, 0) }
        if sleepEndOfSong { sleepEndOfSong = false; sleepAt = nil; pause(); seek(to: 0); return }
        if repeatMode == 2 { seek(to: 0); if !isPlaying { play() }; return }
        if index + 1 < queue.count { load(index + 1, startMs: nil, autoplay: true) }
        else if repeatMode == 1 { load(0, startMs: nil, autoplay: true) }
        else { isPlaying = false; pausedPos = 0; file = nil; updateNowPlaying() }
    }

    // MARK: crossfade

    private var fadeStart = Date()
    private var fadeFrom = 0, fadeTo = 1

    private func beginCrossfade(from: Int, to: Int) {
        crossfading = true; fadeFrom = from; fadeTo = to; fadeStart = Date()
    }

    private func stepCrossfade() {
        let dur = Double(max(1, Prefs.shared.crossfadeSec))
        let t = min(1, Date().timeIntervalSince(fadeStart) / dur)
        mixers[fadeTo].outputVolume = Float(t) * trackGain[fadeTo]
        mixers[fadeFrom].outputVolume = Float(1 - t) * trackGain[fadeFrom]
        if t >= 1 { finishCrossfade() }
    }

    private func finishCrossfade() {
        crossfading = false
        nodes[fadeFrom].stop()
        mixers[fadeFrom].outputVolume = 1
        mixers[fadeTo].outputVolume = trackGain[fadeTo]
    }

    // MARK: ticking: crossfade, sleep timer, stats, resume points

    private func tick() {
        let now = Date()
        if isPlaying { playedMs += Int64(now.timeIntervalSince(lastTick) * 1000 * speed) }
        lastTick = now
        if crossfading { stepCrossfade() }
        if isPlaying, !crossfading, !loading, Prefs.shared.crossfadeSec > 0, repeatMode != 2, !sleepEndOfSong,
           index + 1 < queue.count || repeatMode == 1 {
            let remaining = durationMs - positionMs()
            if remaining > 0, remaining <= Int64(Prefs.shared.crossfadeSec * 1000), durationMs > 15_000 {
                let nextI = index + 1 < queue.count ? index + 1 : 0
                if let s = current { Positions.shared.set(s.key, 0) }
                load(nextI, startMs: nil, autoplay: true, crossfade: true)
            }
        }
        if let at = sleepAt {
            let left = at.timeIntervalSince(now)
            if left <= 0 {
                sleepAt = nil; pause(); engine.mainMixerNode.outputVolume = 1
            } else if Prefs.shared.sleepFade && left < 30 {
                engine.mainMixerNode.outputVolume = Float(max(0.05, left / 30))
            }
        }
        if Int(now.timeIntervalSince1970 * 4) % 20 == 0 { rememberPosition() }
    }

    func setSleep(minutes: Int?) {
        engine.mainMixerNode.outputVolume = 1
        sleepEndOfSong = false
        sleepAt = minutes.map { Date().addingTimeInterval(Double($0) * 60) }
    }
    func setSleepEndOfSong() { sleepAt = nil; sleepEndOfSong = true; engine.mainMixerNode.outputVolume = 1 }

    private func commitPlayed() {
        if let s = current, playedMs > 0 { History.shared.played(s, ms: playedMs) }
        playedMs = 0
    }

    private func rememberPosition() {
        guard let s = current, Prefs.shared.resumeLong, s.durationMs > 20 * 60_000 else { return }
        let p = positionMs()
        if p > 10_000 && s.durationMs - p > 30_000 { Positions.shared.set(s.key, p) }
    }

    // MARK: persistence

    func saveState() {
        let p = Prefs.shared
        p.savedQueue = queue.map(\.id)
        p.savedIndex = index
        p.savedPosition = positionMs()
    }

    func restore() {
        guard queue.isEmpty else { return }
        let p = Prefs.shared
        let lib = LibraryStore.shared
        let songs = p.savedQueue.compactMap { lib.byID[$0] }
        guard !songs.isEmpty else { return }
        queue = songs
        index = min(max(0, p.savedIndex), songs.count - 1)
        pausedPos = p.savedPosition
        updateNowPlaying()
    }

    /// Replaces stale Song values (after a rescan or tag edit) without interrupting playback.
    func refreshSongs() {
        let lib = LibraryStore.shared
        queue = queue.map { lib.byID[$0.id] ?? $0 }
    }
}
