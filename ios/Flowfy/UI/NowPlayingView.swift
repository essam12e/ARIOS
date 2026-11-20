import SwiftUI

struct NowPlayingView: View {
    var onClose: () -> Void
    @ObservedObject private var player = Player.shared
    @ObservedObject private var store = UserStore.shared
    @ObservedObject private var prefs = Prefs.shared
    @ObservedObject private var lstore = LyricsStore.shared
    @StateObject private var loader = LyricsLoader()
    @State private var fullLyrics = false
    @State private var showSleep = false
    @State private var artSwipe: CGFloat = 0
    @Environment(\.verticalSizeClass) private var vClass

    var body: some View {
        ZStack {
            if let s = player.current {
                Backdrop(song: s)
                if vClass == .compact { landscape(s) } else { portrait(s) }
                if fullLyrics {
                    FullLyricsView(song: s, loader: loader) { withAnimation(.spring(response: 0.5, dampingFraction: 0.88)) { fullLyrics = false } }
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .zIndex(3)
                }
            }
        }
        .onAppear { loader.load(player.current, revision: lstore.revision) }
        .onChange(of: player.current?.id) { _ in loader.load(player.current, revision: lstore.revision) }
        .onChange(of: lstore.revision) { r in loader.load(player.current, revision: r, force: true) }
        .confirmationDialog(T("مؤقت النوم", "Sleep timer"), isPresented: $showSleep) {
            ForEach([10, 15, 30, 45, 60, 90], id: \.self) { m in Button("\(m) \(T("دقيقة", "min"))") { player.setSleep(minutes: m); AppState.shared.show(T("سيتوقف بعد \(m) دقيقة", "Stops in \(m) min")) } }
            Button(T("نهاية الأغنية", "End of song")) { player.setSleepEndOfSong(); AppState.shared.show(T("سيتوقف بنهاية الأغنية", "Stops after this song")) }
            if player.sleepAt != nil || player.sleepEndOfSong { Button(T("إلغاء المؤقت", "Cancel timer"), role: .destructive) { player.setSleep(minutes: nil) } }
        }
    }

    private func portrait(_ s: Song) -> some View {
        ScrollView(showsIndicators: false) {
            VStack(spacing: 18) {
                topBar(s)
                artwork(s, size: min(UIScreen.main.bounds.width - 56, 380))
                titleRow(s)
                ProgressBar().padding(.horizontal, 4)
                controls
                bottomRow
                LyricsCard(song: s, loader: loader) { withAnimation(.spring(response: 0.55, dampingFraction: 0.88)) { fullLyrics = true } }
                Color.clear.frame(height: 30)
            }
            .padding(.horizontal, 22)
        }
    }

    private func landscape(_ s: Song) -> some View {
        HStack(spacing: 24) {
            VStack(spacing: 12) {
                topBar(s)
                artwork(s, size: min(UIScreen.main.bounds.height - 200, 260))
                titleRow(s)
                ProgressBar()
                controls
            }
            .frame(maxWidth: 380)
            Group {
                switch loader.state {
                case .ready(let l): LyricsView(lyrics: l, song: s, fontSize: CGFloat(prefs.lyricsFontSize) * 0.9)
                default: LyricsCard(song: s, loader: loader) { fullLyrics = true }
                }
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 24).padding(.vertical, 10)
    }

    private func topBar(_ s: Song) -> some View {
        HStack {
            IconButton(system: "chevron.down", size: 18, action: onClose)
            Spacer()
            VStack(spacing: 1) {
                Text(T("يعمل الآن", "Now playing")).font(.system(size: 11, weight: .bold)).foregroundStyle(Theme.sub)
                Text(s.album).font(.system(size: 13, weight: .semibold)).lineLimit(1)
            }
            Spacer()
            Menu {
                SongMenu(song: s)
                Divider()
                Button { showSleep = true } label: { Label(sleepLabel, systemImage: "moon.zzz") }
                Menu {
                    ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { v in
                        Button { player.setSpeed(v) } label: { Text(String(format: "%.2fx", v) + (player.speed == v ? " ✓" : "")) }
                    }
                } label: { Label(T("السرعة", "Speed") + String(format: " (%.2fx)", player.speed), systemImage: "speedometer") }
                Button { onClose(); AppState.shared.go(.sound) } label: { Label(T("الصوت والمعادل", "Sound & EQ"), systemImage: "slider.vertical.3") }
                Button { AppState.shared.carMode = true } label: { Label(T("وضع السيارة", "Car mode"), systemImage: "car") }
            } label: {
                Image(systemName: "ellipsis").font(.system(size: 18, weight: .bold)).foregroundStyle(.white).frame(width: 42, height: 42)
            }
        }
    }

    private var sleepLabel: String {
        if player.sleepEndOfSong { return T("مؤقت النوم: نهاية الأغنية", "Sleep: end of song") }
        if let at = player.sleepAt { return T("مؤقت النوم: \(max(1, Int(at.timeIntervalSinceNow / 60))) د", "Sleep: \(max(1, Int(at.timeIntervalSinceNow / 60))) min") }
        return T("مؤقت النوم", "Sleep timer")
    }

    private func artwork(_ s: Song, size: CGFloat) -> some View {
        ZStack {
            if prefs.visualizer && player.isPlaying {
                VisualizerBars(color: Theme.accent.main).frame(width: size * 1.1, height: size * 0.5).offset(y: size * 0.32).blur(radius: 6).opacity(0.7)
            }
            ArtworkView(song: s, size: size, radius: 30)
                .shadow(color: Theme.accent.main.opacity(0.45), radius: 34, y: 14)
                .scaleEffect(player.isPlaying ? 1 : 0.88)
                .animation(.spring(response: 0.6, dampingFraction: 0.7), value: player.isPlaying)
                .offset(x: artSwipe)
                .rotation3DEffect(.degrees(Double(artSwipe) / 12), axis: (x: 0, y: 1, z: 0))
                .gesture(DragGesture(minimumDistance: 20)
                    .onChanged { v in if abs(v.translation.width) > abs(v.translation.height) { artSwipe = v.translation.width } }
                    .onEnded { v in
                        let w = v.translation.width * (isArabicUI ? -1 : 1)
                        if w < -80 { player.next() } else if w > 80 { player.previous() }
                        withAnimation(.spring(response: 0.45, dampingFraction: 0.75)) { artSwipe = 0 }
                    })
                .id(s.id)
                .transition(.asymmetric(insertion: .scale(scale: 0.85).combined(with: .opacity), removal: .opacity))
        }
        .frame(height: size)
        .animation(.spring(response: 0.55, dampingFraction: 0.8), value: s.id)
    }

    private func titleRow(_ s: Song) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(s.title).font(.system(size: 23, weight: .heavy)).lineLimit(2)
                Button { onClose(); AppState.shared.go(.artist(s.artist)) } label: {
                    Text(s.artist).font(.system(size: 16, weight: .medium)).foregroundStyle(Theme.accent.light.opacity(0.85)).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button { store.toggleFavorite(s.key); haptic(.medium) } label: {
                Image(systemName: store.isFavorite(s.key) ? "heart.fill" : "heart").font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(store.isFavorite(s.key) ? Theme.accent.second : .white)
                    .scaleEffect(store.isFavorite(s.key) ? 1.12 : 1)
                    .animation(.spring(response: 0.3, dampingFraction: 0.45), value: store.isFavorite(s.key))
                    .frame(width: 46, height: 46)
            }.buttonStyle(BounceStyle())
        }
    }

    private var controls: some View {
        HStack {
            IconButton(system: "shuffle", size: 18, tint: player.shuffle ? Theme.accent.main : Theme.sub) { player.toggleShuffle(); haptic() }
            Spacer()
            IconButton(system: "backward.fill", size: 26) { player.previous(); haptic() }
            Spacer()
            Button { player.toggle(); haptic(.medium) } label: {
                ZStack {
                    Circle().fill(LinearGradient(colors: [Theme.accent.light, Theme.accent.main], startPoint: .topLeading, endPoint: .bottomTrailing))
                    if player.loading { ProgressView().tint(.black) } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 32, weight: .bold)).foregroundStyle(.black)
                    }
                }
                .frame(width: 78, height: 78)
                .shadow(color: Theme.accent.main.opacity(0.6), radius: 22)
            }.buttonStyle(BounceStyle(scale: 0.88))
            Spacer()
            IconButton(system: "forward.fill", size: 26) { player.next(); haptic() }
            Spacer()
            IconButton(system: player.repeatMode == 2 ? "repeat.1" : "repeat", size: 18, tint: player.repeatMode == 0 ? Theme.sub : Theme.accent.main) { player.cycleRepeat(); haptic() }
        }
        .environment(\.layoutDirection, .leftToRight)
    }

    private var bottomRow: some View {
        HStack {
            IconButton(system: "quote.bubble", size: 18, tint: Theme.sub) { withAnimation(.spring(response: 0.55, dampingFraction: 0.88)) { fullLyrics = true } }
            Spacer()
            IconButton(system: player.sleepAt != nil || player.sleepEndOfSong ? "moon.zzz.fill" : "moon.zzz", size: 18,
                       tint: player.sleepAt != nil || player.sleepEndOfSong ? Theme.accent.main : Theme.sub) { showSleep = true }
            Spacer()
            if let s = player.current { IconButton(system: "square.and.arrow.up", size: 18, tint: Theme.sub) { AppState.shared.shareLyrics = s } }
            Spacer()
            IconButton(system: "list.bullet", size: 18, tint: Theme.sub) { AppState.shared.showQueue = true }
        }
    }
}

struct QueueView: View {
    @ObservedObject private var player = Player.shared
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(player.queue.enumerated()), id: \.offset) { i, s in
                    HStack(spacing: 12) {
                        ArtworkView(song: s, size: 42, radius: 9)
                        VStack(alignment: .leading) {
                            Text(s.title).foregroundStyle(i == player.index ? Theme.accent.main : .white).lineLimit(1)
                            Text(s.artist).font(.caption).foregroundStyle(Theme.sub).lineLimit(1)
                        }
                        Spacer()
                        if i == player.index { EqualizerIcon(animating: player.isPlaying).frame(width: 18, height: 14) }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { player.jump(to: i) }
                    .listRowBackground(Color.white.opacity(i == player.index ? 0.08 : 0.03))
                }
                .onMove { player.moveQueue(from: $0, to: $1) }
                .onDelete { idx in idx.sorted(by: >).forEach { player.removeFromQueue($0) } }
            }
            .scrollContentBackground(.hidden).background(Theme.bg)
            .navigationTitle(T("الطابور", "Queue")).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(T("إغلاق", "Close")) { dismiss() } }
                ToolbarItem(placement: .primaryAction) { EditButton() }
            }
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
    }
}
