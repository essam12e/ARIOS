import SwiftUI

enum LyricsLoad: Equatable {
    case idle, loading, ready(Lyrics), notFound
}

/// Loads lyrics for the current song and keeps them in sync with edits.
@MainActor
final class LyricsLoader: ObservableObject {
    @Published private(set) var state: LyricsLoad = .idle
    private var songID: String?
    private var rev = -1
    private var task: Task<Void, Never>?

    var lyrics: Lyrics? { if case .ready(let l) = state { return l }; return nil }

    func load(_ s: Song?, revision: Int, force: Bool = false) {
        guard let s else { state = .idle; songID = nil; return }
        if !force && s.id == songID && revision == rev { return }
        let sameSong = s.id == songID
        songID = s.id; rev = revision
        task?.cancel()
        if !sameSong { state = .loading }
        task = Task {
            let l = await LyricsStore.shared.get(s)
            guard !Task.isCancelled, self.songID == s.id else { return }
            self.state = l.map { .ready($0) } ?? .notFound
        }
    }
}

/// Publishes the active line index ~20×/s (only when it changes).
@MainActor
final class LyricsClock: ObservableObject {
    @Published private(set) var active = -1
    var lyrics: Lyrics?
    var offset: Int64 = 0
    private var timer: Timer?

    init() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in Task { @MainActor in self?.tick() } }
    }
    deinit { timer?.invalidate() }

    var position: Int64 { Player.shared.positionMs() - offset + 120 }

    func tick() {
        guard let l = lyrics, l.synced else { if active != -1 { active = -1 }; return }
        let i = l.index(at: position)
        if i != active { active = i }
    }
}

struct LyricsView: View {
    var lyrics: Lyrics
    var song: Song
    var fontSize: CGFloat
    var compact = false
    @StateObject private var clock = LyricsClock()
    @ObservedObject private var prefs = Prefs.shared
    @ObservedObject private var store = UserStore.shared
    @State private var userScrolling = false
    @State private var lastUserScroll = Date.distantPast

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(showsIndicators: false) {
                VStack(spacing: compact ? 10 : 18) {
                    Color.clear.frame(height: compact ? 20 : 140)
                    ForEach(Array(lyrics.lines.enumerated()), id: \.offset) { i, line in
                        LyricLineView(line: line, index: i, active: clock.active, synced: lyrics.synced, fontSize: fontSize,
                                      center: prefs.lyricsCenter, showTranslation: prefs.showTranslation, wordByWord: prefs.wordByWord,
                                      clock: clock)
                            .id(i)
                            .onTapGesture {
                                guard lyrics.synced else { return }
                                haptic()
                                Player.shared.seek(to: max(0, line.start + store.offset(song.key)))
                                if !Player.shared.isPlaying { Player.shared.play() }
                            }
                    }
                    Text(T("المصدر: ", "Source: ") + lyrics.source).font(.system(size: 12)).foregroundStyle(Theme.faint).padding(.top, 20)
                    Color.clear.frame(height: compact ? 20 : 260)
                }
                .padding(.horizontal, compact ? 18 : 26)
            }
            .simultaneousGesture(DragGesture(minimumDistance: 8).onChanged { _ in lastUserScroll = Date() })
            .onChange(of: clock.active) { i in
                guard i >= 0, Date().timeIntervalSince(lastUserScroll) > 2.5 else { return }
                withAnimation(.spring(response: 0.75, dampingFraction: 0.86)) { proxy.scrollTo(i, anchor: UnitPoint(x: 0.5, y: compact ? 0.4 : 0.35)) }
            }
            .onAppear {
                clock.lyrics = lyrics; clock.offset = store.offset(song.key); clock.tick()
                if clock.active >= 0 { proxy.scrollTo(clock.active, anchor: UnitPoint(x: 0.5, y: 0.35)) }
            }
            .onChange(of: lyrics) { l in clock.lyrics = l; clock.tick() }
            .onChange(of: store.offset(song.key)) { o in clock.offset = o; clock.tick() }
        }
        .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.08),
                                     .init(color: .black, location: 0.86), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
    }
}

struct LyricLineView: View {
    var line: LyricLine
    var index: Int
    var active: Int
    var synced: Bool
    var fontSize: CGFloat
    var center: Bool
    var showTranslation: Bool
    var wordByWord: Bool
    @ObservedObject var clock: LyricsClock

    var body: some View {
        let isActive = synced && index == active
        let passed = synced && index < active
        let dist = synced && active >= 0 ? abs(index - active) : 0
        let rtl = Lyrics.isRTL(line.text)
        // physical side for the line's own script
        let align: Alignment = center ? .center : (rtl == isArabicUI ? .leading : .trailing)
        let textAlign: TextAlignment = center ? .center : (rtl == isArabicUI ? .leading : .trailing)
        VStack(alignment: center ? .center : (rtl == isArabicUI ? .leading : .trailing), spacing: 4) {
            Group {
                if line.text.trimmed.isEmpty {
                    Text("♪").font(Theme.lyrics(fontSize))
                } else if isActive && wordByWord && !line.words.isEmpty {
                    TimelineView(.animation) { _ in wordText(clock.position) }
                } else {
                    Text(line.text).font(Theme.lyrics(fontSize))
                        .foregroundStyle(.white.opacity(!synced ? 0.9 : isActive ? 1 : passed ? 0.42 : 0.3))
                }
            }
            .multilineTextAlignment(textAlign)
            .environment(\.layoutDirection, rtl ? .rightToLeft : .leftToRight)
            if showTranslation, let t = line.translation, !t.isEmpty {
                Text(t).font(Theme.lyrics(fontSize * 0.62)).foregroundStyle(.white.opacity(isActive ? 0.75 : 0.32))
                    .multilineTextAlignment(textAlign)
            }
        }
        .frame(maxWidth: .infinity, alignment: align)
        .scaleEffect(isActive ? 1.0 : 0.94, anchor: center ? .center : (rtl == isArabicUI ? .leading : .trailing))
        .blur(radius: synced && !isActive ? min(2.2, Double(dist) * 0.55) : 0)
        .shadow(color: isActive ? Theme.accent.main.opacity(0.55) : .clear, radius: 16)
        .animation(.spring(response: 0.5, dampingFraction: 0.8), value: isActive)
        .contentShape(Rectangle())
    }

    private func wordText(_ pos: Int64) -> Text {
        line.words.reduce(Text("")) { acc, w in
            let span = max(1, w.end - w.start)
            let p = min(1, max(0, Double(pos - w.start) / Double(span)))
            let c: Color = p >= 1 ? .white : p <= 0 ? .white.opacity(0.38) : Color(white: 1, opacity: 0.38 + 0.62 * p)
            return acc + Text(w.text).font(Theme.lyrics(fontSize)).foregroundColor(c)
        }
    }
}

/// Compact lyrics card shown under the player controls.
struct LyricsCard: View {
    var song: Song
    @ObservedObject var loader: LyricsLoader
    @ObservedObject private var lstore = LyricsStore.shared
    var onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(T("الكلمات", "Lyrics")).font(.system(size: 15, weight: .heavy))
                Spacer()
                if lstore.syncing.contains(song.key) {
                    HStack(spacing: 6) { ProgressView().scaleEffect(0.7); Text(T("مزامنة تلقائية…", "Auto-syncing…")).font(.system(size: 12, weight: .semibold)) }
                        .foregroundStyle(Theme.accent.light)
                }
                Image(systemName: "arrow.up.left.and.arrow.down.right").font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.sub)
            }
            switch loader.state {
            case .ready(let l):
                LyricsView(lyrics: l, song: song, fontSize: 21, compact: true).frame(height: 230)
            case .loading, .idle:
                HStack { ProgressView(); Text(T("نبحث عن الكلمات…", "Finding lyrics…")).foregroundStyle(Theme.sub) }.frame(maxWidth: .infinity, minHeight: 120)
            case .notFound:
                VStack(spacing: 10) {
                    Text(T("ما لقينا كلمات لهالأغنية", "No lyrics found for this song")).foregroundStyle(Theme.sub)
                    HStack {
                        Button(T("بحث يدوي", "Search")) { AppState.shared.lyricsSearch = song }
                        Button(T("كتابة الكلمات", "Write lyrics")) { AppState.shared.lyricsEdit = song }
                    }.buttonStyle(.bordered).tint(Theme.accent.main)
                }.frame(maxWidth: .infinity, minHeight: 120)
            }
        }
        .padding(16)
        .glass(26, glow: true)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
    }
}

struct FullLyricsView: View {
    var song: Song
    @ObservedObject var loader: LyricsLoader
    var onClose: () -> Void
    @ObservedObject private var prefs = Prefs.shared
    @ObservedObject private var player = Player.shared
    @ObservedObject private var store = UserStore.shared
    @ObservedObject private var lstore = LyricsStore.shared

    var body: some View {
        ZStack {
            Backdrop(song: song)
            if prefs.visualizer { VisualizerBars(color: Theme.accent.main).frame(height: 160).frame(maxHeight: .infinity, alignment: .bottom).opacity(0.35).ignoresSafeArea() }
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    ArtworkView(song: song, size: 46, radius: 10)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(song.title).font(.system(size: 16, weight: .bold)).lineLimit(1)
                        Text(song.artist).font(.system(size: 13)).foregroundStyle(Theme.sub).lineLimit(1)
                    }
                    Spacer()
                    Menu {
                        Button { prefs.lyricsFontSize = min(44, prefs.lyricsFontSize + 2) } label: { Label(T("تكبير الخط", "Larger text"), systemImage: "textformat.size.larger") }
                        Button { prefs.lyricsFontSize = max(16, prefs.lyricsFontSize - 2) } label: { Label(T("تصغير الخط", "Smaller text"), systemImage: "textformat.size.smaller") }
                        Toggle(T("الترجمة", "Translation"), isOn: Binding(get: { prefs.showTranslation }, set: { prefs.showTranslation = $0 }))
                        Toggle(T("كلمة بكلمة", "Word by word"), isOn: Binding(get: { prefs.wordByWord }, set: { prefs.wordByWord = $0 }))
                        Toggle(T("توسيط", "Centered"), isOn: Binding(get: { prefs.lyricsCenter }, set: { prefs.lyricsCenter = $0 }))
                        Divider()
                        Button { AppState.shared.lyricsSearch = song } label: { Label(T("بحث عن كلمات أخرى", "Search other lyrics"), systemImage: "magnifyingglass") }
                        Button { AppState.shared.lyricsEdit = song } label: { Label(T("تعديل ومزامنة", "Edit & sync"), systemImage: "pencil") }
                        Button { AppState.shared.shareLyrics = song } label: { Label(T("مشاركة", "Share"), systemImage: "square.and.arrow.up") }
                    } label: { Image(systemName: "textformat").font(.system(size: 18, weight: .semibold)).foregroundStyle(.white).frame(width: 40, height: 40) }
                    IconButton(system: "chevron.down", size: 18, action: onClose)
                }
                .padding(.horizontal, 16).padding(.top, 8)

                switch loader.state {
                case .ready(let l):
                    if !l.synced {
                        UnsyncedBanner(song: song, lyrics: l)
                    }
                    LyricsView(lyrics: l, song: song, fontSize: CGFloat(prefs.lyricsFontSize))
                case .notFound:
                    Spacer()
                    Text(T("ما لقينا كلمات", "No lyrics")).foregroundStyle(Theme.sub)
                    Button(T("بحث يدوي", "Search")) { AppState.shared.lyricsSearch = song }.buttonStyle(.bordered).tint(Theme.accent.main).padding()
                    Spacer()
                default:
                    Spacer(); ProgressView(); Spacer()
                }

                if loader.lyrics?.synced == true {
                    HStack(spacing: 10) {
                        Button { store.setOffset(song.key, store.offset(song.key) - 250) } label: { Image(systemName: "minus") }
                        Text(String(format: T("تقديم/تأخير %.2f ث", "Offset %.2fs"), Double(store.offset(song.key)) / 1000))
                            .font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.sub)
                            .onTapGesture { store.setOffset(song.key, 0) }
                        Button { store.setOffset(song.key, store.offset(song.key) + 250) } label: { Image(systemName: "plus") }
                    }
                    .font(.system(size: 13, weight: .bold)).foregroundStyle(.white)
                    .padding(.horizontal, 14).padding(.vertical, 6).glass(14)
                }
                ProgressBar().padding(.horizontal, 22).padding(.top, 8)
                HStack(spacing: 34) {
                    IconButton(system: "backward.fill", size: 22) { player.previous() }
                    Button { player.toggle(); haptic() } label: {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 26, weight: .bold)).foregroundStyle(.black)
                            .frame(width: 62, height: 62).background(Circle().fill(Theme.accent.light)).softGlow(Theme.accent.main)
                    }.buttonStyle(BounceStyle())
                    IconButton(system: "forward.fill", size: 22) { player.next() }
                }
                .padding(.bottom, 14)
            }
        }
    }
}

struct UnsyncedBanner: View {
    var song: Song
    var lyrics: Lyrics
    @ObservedObject private var lstore = LyricsStore.shared
    var body: some View {
        HStack(spacing: 10) {
            if lstore.syncing.contains(song.key) {
                ProgressView().tint(Theme.accent.light)
                Text(T("نضبط التوقيت تلقائياً من صوت الأغنية…", "Timing the lyrics from the audio…")).font(.system(size: 13, weight: .semibold))
            } else {
                Image(systemName: "waveform").foregroundStyle(Theme.accent.main)
                Text(T("الكلمات بدون توقيت", "Lyrics are not timed")).font(.system(size: 13, weight: .semibold))
                Spacer()
                Button(T("مزامنة تلقائية", "Auto-sync")) {
                    Task {
                        let ok = await lstore.autoSync(song, lyrics)
                        AppState.shared.show(ok ? T("تمت المزامنة التلقائية", "Synced automatically") : T("تعذّرت المزامنة", "Couldn't sync"))
                    }
                }.font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.accent.light)
            }
        }
        .padding(12).glass(16).padding(.horizontal, 16).padding(.top, 8)
    }
}

struct ProgressBar: View {
    @ObservedObject private var player = Player.shared
    @State private var dragging: Double?
    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.25)) { _ in
            let dur = max(1, Double(player.durationMs))
            let pos = dragging ?? Double(player.positionMs())
            VStack(spacing: 6) {
                GeometryReader { g in
                    let f = CGFloat(min(1, max(0, pos / dur)))
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.14)).frame(height: dragging == nil ? 5 : 9)
                        Capsule().fill(LinearGradient(colors: [Theme.accent.main, Theme.accent.light], startPoint: .leading, endPoint: .trailing))
                            .frame(width: max(5, g.size.width * f), height: dragging == nil ? 5 : 9)
                            .shadow(color: Theme.accent.main.opacity(0.7), radius: 8)
                    }
                    .frame(maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 0)
                        .onChanged { v in dragging = Double(min(1, max(0, v.location.x / g.size.width))) * dur }
                        .onEnded { _ in if let d = dragging { player.seek(to: Int64(d)) }; dragging = nil })
                    .animation(.spring(response: 0.3), value: dragging == nil)
                }
                .frame(height: 22)
                .environment(\.layoutDirection, .leftToRight)
                HStack {
                    Text(fmtTime(Int64(pos))); Spacer(); Text("-" + fmtTime(Int64(dur - pos)))
                }
                .font(.system(size: 12, weight: .semibold).monospacedDigit()).foregroundStyle(Theme.sub)
                .environment(\.layoutDirection, .leftToRight)
            }
        }
    }
}
