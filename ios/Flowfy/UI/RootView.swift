import SwiftUI

struct RootView: View {
    @StateObject private var app = AppState.shared
    @ObservedObject private var player = Player.shared
    @ObservedObject private var prefs = Prefs.shared
    @ObservedObject private var lib = LibraryStore.shared
    @State private var dragY: CGFloat = 0

    var body: some View {
        ZStack(alignment: .bottom) {
            AppBackground()
            NavigationStack(path: $app.path) {
                LibraryView()
                    .navigationDestination(for: Route.self) { r in destination(r) }
            }
            .tint(Theme.accent.main)
            .safeAreaInset(edge: .bottom) { Color.clear.frame(height: player.current == nil ? 0 : 76) }

            if player.current != nil && !app.showPlayer {
                MiniPlayer()
                    .padding(.horizontal, 10).padding(.bottom, 4)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if app.showPlayer {
                NowPlayingView(onClose: closePlayer)
                    .offset(y: max(0, dragY))
                    .gesture(DragGesture(minimumDistance: 20, coordinateSpace: .global)
                        .onChanged { v in if v.translation.height > 0 && abs(v.translation.height) > abs(v.translation.width) { dragY = v.translation.height } }
                        .onEnded { v in
                            if v.translation.height > 140 || v.predictedEndTranslation.height > 380 { closePlayer() }
                            else { withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { dragY = 0 } }
                        })
                    .transition(.move(edge: .bottom))
                    .zIndex(2)
            }

            if let t = app.toast {
                Text(t).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 18).padding(.vertical, 12)
                    .glass(18, glow: true)
                    .padding(.bottom, player.current == nil ? 30 : 100)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .zIndex(5)
            }
        }
        .animation(.spring(response: 0.5, dampingFraction: 0.86), value: app.showPlayer)
        .animation(.spring(response: 0.5, dampingFraction: 0.86), value: player.current?.id)
        .sheet(item: Binding(get: { app.addToPlaylist.map(SongList.init) }, set: { app.addToPlaylist = $0?.songs })) { l in
            AddToPlaylistSheet(songs: l.songs)
        }
        .sheet(item: $app.tagEdit) { s in TagEditorView(song: s) }
        .sheet(item: $app.lyricsSearch) { s in LyricsSearchView(song: s) }
        .fullScreenCover(item: $app.lyricsEdit) { s in LyricsEditorView(song: s) }
        .fullScreenCover(item: $app.shareLyrics) { s in ShareLyricsView(song: s) }
        .fullScreenCover(isPresented: $app.carMode) { CarModeView() }
        .sheet(isPresented: $app.showQueue) { QueueView() }
        .task {
            await lib.ensureLoaded()
            player.restore()
        }
        .onChange(of: lib.songs.count) { _ in player.refreshSongs() }
    }

    private func closePlayer() {
        withAnimation(.spring(response: 0.45, dampingFraction: 0.9)) { app.showPlayer = false }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { dragY = 0 }
    }

    @ViewBuilder private func destination(_ r: Route) -> some View {
        switch r {
        case .album(let id): AlbumDetail(id: id)
        case .artist(let n): ArtistDetail(name: n)
        case .folder(let p): FolderDetail(path: p)
        case .playlist(let id): PlaylistDetail(id: id)
        case .smart(let k): SmartDetail(kind: k)
        case .settings: SettingsView()
        case .sound: SoundView()
        case .stats: StatsView()
        case .wrapped: WrappedView()
        case .downloads: DownloadsView()
        case .duplicates: DuplicatesView()
        case .search: SearchView()
        case .folders: ExcludedFoldersView()
        case .backup: BackupView()
        }
    }
}

struct SongList: Identifiable { var songs: [Song]; var id: String { songs.map(\.id).joined(separator: ",") } }

struct MiniPlayer: View {
    @ObservedObject private var player = Player.shared
    @ObservedObject private var store = UserStore.shared
    @State private var dragX: CGFloat = 0

    var body: some View {
        if let s = player.current {
            HStack(spacing: 12) {
                ArtworkView(song: s, size: 48, radius: 12)
                    .shadow(color: Theme.accent.main.opacity(0.4), radius: 10)
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.title).font(.system(size: 15, weight: .bold)).lineLimit(1)
                    Text(s.artist).font(.system(size: 12.5, weight: .medium)).foregroundStyle(Theme.sub).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .offset(x: dragX)
                IconButton(system: store.isFavorite(s.key) ? "heart.fill" : "heart", size: 17, tint: store.isFavorite(s.key) ? Theme.accent.second : .white) {
                    store.toggleFavorite(s.key); haptic()
                }
                IconButton(system: player.isPlaying ? "pause.fill" : "play.fill", size: 21) { player.toggle(); haptic() }
                IconButton(system: "forward.fill", size: 17) { player.next() }
            }
            .padding(.leading, 8).padding(.trailing, 4).padding(.vertical, 8)
            .glass(20, glow: true)
            .overlay(alignment: .bottom) { MiniProgress().padding(.horizontal, 18) }
            .contentShape(Rectangle())
            .onTapGesture { AppState.shared.showPlayer = true }
            .gesture(DragGesture(minimumDistance: 15)
                .onChanged { v in
                    if v.translation.height < -40 { AppState.shared.showPlayer = true }
                    else if abs(v.translation.width) > abs(v.translation.height) { dragX = v.translation.width * 0.5 }
                }
                .onEnded { v in
                    let w = v.translation.width * (isArabicUI ? -1 : 1)
                    if w < -70 { player.next() } else if w > 70 { player.previous() }
                    withAnimation(.spring()) { dragX = 0 }
                })
        }
    }
}

struct MiniProgress: View {
    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { _ in
            let p = Player.shared
            let f = p.durationMs > 0 ? CGFloat(p.positionMs()) / CGFloat(p.durationMs) : 0
            GeometryReader { g in
                Capsule().fill(Theme.gradient).frame(width: g.size.width * min(1, max(0, f)), height: 2.5)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }.frame(height: 2.5)
        }
    }
}
