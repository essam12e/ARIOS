import SwiftUI

struct SongRow: View {
    var song: Song
    var queue: [Song]
    var index: Int
    var trailing: AnyView? = nil
    @ObservedObject private var player = Player.shared
    @ObservedObject private var store = UserStore.shared

    var body: some View {
        let playing = player.current?.id == song.id
        HStack(spacing: 12) {
            ZStack {
                ArtworkView(song: song, size: 50, radius: 12)
                if playing {
                    RoundedRectangle(cornerRadius: 12).fill(.black.opacity(0.45)).frame(width: 50, height: 50)
                    EqualizerIcon(animating: player.isPlaying).frame(width: 20, height: 16)
                }
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(song.title).font(.system(size: 15.5, weight: .semibold))
                    .foregroundStyle(playing ? Theme.accent.main : .white).lineLimit(1)
                HStack(spacing: 4) {
                    if store.isFavorite(song.key) { Image(systemName: "heart.fill").font(.system(size: 9)).foregroundStyle(Theme.accent.second) }
                    Text("\(song.artist) • \(fmtTime(song.durationMs))").font(.system(size: 12.5)).foregroundStyle(Theme.sub).lineLimit(1)
                }
            }
            Spacer(minLength: 4)
            if let trailing { trailing }
            Menu { SongMenu(song: song) } label: {
                Image(systemName: "ellipsis").font(.system(size: 16, weight: .bold)).foregroundStyle(Theme.sub)
                    .frame(width: 36, height: 44).contentShape(Rectangle())
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 5)
        .background(playing ? RoundedRectangle(cornerRadius: 16).fill(Theme.accent.main.opacity(0.08)).padding(.horizontal, 8) : nil)
        .contentShape(Rectangle())
        .onTapGesture {
            haptic()
            if playing { AppState.shared.showPlayer = true } else { Player.shared.play(queue, start: index) }
        }
        .contextMenu { SongMenu(song: song) }
    }
}

struct EqualizerIcon: View {
    var animating: Bool
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 20, paused: !animating)) { t in
            let x = t.date.timeIntervalSinceReferenceDate
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(0..<4) { i in
                    Capsule().fill(Theme.accent.main)
                        .frame(width: 3, height: animating ? 4 + 12 * abs(sin(x * (3.2 + Double(i) * 0.9) + Double(i))) : 4)
                }
            }
        }
    }
}

/// Song actions used by context menus and the "…" buttons.
struct SongMenu: View {
    var song: Song
    var inPlaylist: String? = nil
    @ObservedObject private var store = UserStore.shared

    var body: some View {
        Button { Player.shared.playNext([song]); AppState.shared.show(T("ستُشغّل بعد الحالية", "Plays next")) } label: {
            Label(T("تشغيل التالي", "Play next"), systemImage: "text.insert")
        }
        Button { Player.shared.addToQueue([song]); AppState.shared.show(T("أضيفت للطابور", "Added to queue")) } label: {
            Label(T("إضافة للطابور", "Add to queue"), systemImage: "text.append")
        }
        Button { AppState.shared.addToPlaylist = [song] } label: { Label(T("إضافة لقائمة", "Add to playlist"), systemImage: "music.note.list") }
        Button { store.toggleFavorite(song.key) } label: {
            Label(store.isFavorite(song.key) ? T("إزالة من المفضلة", "Remove favorite") : T("إضافة للمفضلة", "Favorite"),
                  systemImage: store.isFavorite(song.key) ? "heart.slash" : "heart")
        }
        if let pid = inPlaylist {
            Button(role: .destructive) { store.removeFromPlaylist(pid, song.key) } label: { Label(T("إزالة من القائمة", "Remove from playlist"), systemImage: "minus.circle") }
        }
        Divider()
        Button { AppState.shared.go(.album(song.albumKey)) } label: { Label(T("الذهاب للألبوم", "Go to album"), systemImage: "square.stack") }
        Button { AppState.shared.go(.artist(song.artist)) } label: { Label(T("الذهاب للفنان", "Go to artist"), systemImage: "music.mic") }
        Menu {
            Button { AppState.shared.lyricsSearch = song } label: { Label(T("بحث عن الكلمات", "Search lyrics"), systemImage: "magnifyingglass") }
            Button { AppState.shared.lyricsEdit = song } label: { Label(T("تعديل ومزامنة", "Edit & sync"), systemImage: "pencil") }
            Button { AppState.shared.shareLyrics = song } label: { Label(T("مشاركة الكلمات", "Share lyrics"), systemImage: "square.and.arrow.up") }
        } label: { Label(T("الكلمات", "Lyrics"), systemImage: "quote.bubble") }
        Button { AppState.shared.tagEdit = song } label: { Label(T("تعديل المعلومات", "Edit info"), systemImage: "tag") }
        if let u = song.fileURL {
            ShareLink(item: u) { Label(T("مشاركة الملف", "Share file"), systemImage: "square.and.arrow.up.on.square") }
            Button(role: .destructive) {
                Task { await LibraryStore.shared.delete(song); AppState.shared.show(T("تم الحذف", "Deleted")) }
            } label: { Label(T("حذف الملف", "Delete file"), systemImage: "trash") }
        }
    }
}

struct CollectionMenu: View {
    var songs: [Song]
    var body: some View {
        Button { Player.shared.play(songs) } label: { Label(T("تشغيل", "Play"), systemImage: "play.fill") }
        Button { Player.shared.play(songs, shuffled: true) } label: { Label(T("عشوائي", "Shuffle"), systemImage: "shuffle") }
        Button { Player.shared.playNext(songs) } label: { Label(T("تشغيل التالي", "Play next"), systemImage: "text.insert") }
        Button { Player.shared.addToQueue(songs) } label: { Label(T("إضافة للطابور", "Add to queue"), systemImage: "text.append") }
        Button { AppState.shared.addToPlaylist = songs } label: { Label(T("إضافة لقائمة", "Add to playlist"), systemImage: "music.note.list") }
    }
}

struct AddToPlaylistSheet: View {
    var songs: [Song]
    @ObservedObject private var store = UserStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        TextField(T("قائمة جديدة…", "New playlist…"), text: $name)
                        Button(T("إنشاء", "Create")) {
                            guard !name.trimmed.isEmpty else { return }
                            store.createPlaylist(name.trimmed, keys: songs.map(\.key))
                            AppState.shared.show(T("أُنشئت القائمة", "Playlist created")); dismiss()
                        }.disabled(name.trimmed.isEmpty)
                    }
                }
                Section {
                    ForEach(store.playlists) { p in
                        Button {
                            store.addToPlaylist(p.id, songs.map(\.key))
                            AppState.shared.show(T("أضيفت إلى \(p.name)", "Added to \(p.name)")); dismiss()
                        } label: {
                            HStack { Image(systemName: "music.note.list").foregroundStyle(Theme.accent.main); Text(p.name).foregroundStyle(.white); Spacer()
                                Text("\(p.songKeys.count)").foregroundStyle(Theme.sub) }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden).background(Theme.bg)
            .navigationTitle(T("إضافة لقائمة", "Add to playlist")).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(T("إلغاء", "Cancel")) { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
    }
}

// MARK: - Detail screens

struct DetailScreen: View {
    var title: String
    var subtitle: String
    var cover: Song?
    var songs: [Song]
    var playlistID: String? = nil
    var icon: String? = nil

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 2) {
                VStack(spacing: 12) {
                    if let icon, cover == nil {
                        Image(systemName: icon).font(.system(size: 64, weight: .semibold)).foregroundStyle(Theme.accent.main)
                            .frame(width: 200, height: 200).glass(36, glow: true)
                    } else {
                        ArtworkView(song: cover, size: 220, radius: 28).shadow(color: Theme.accent.main.opacity(0.35), radius: 30, y: 10)
                    }
                    Text(title).font(.system(size: 24, weight: .heavy)).multilineTextAlignment(.center)
                    Text(subtitle).font(.system(size: 14)).foregroundStyle(Theme.sub)
                    PlayButtons(songs: songs)
                }
                .padding(.vertical, 16).padding(.horizontal, 20)
                ForEach(Array(songs.enumerated()), id: \.element.id) { i, s in
                    SongRow(song: s, queue: songs, index: i).staggerIn(i)
                        .contextMenu { SongMenu(song: s, inPlaylist: playlistID) }
                }
                Color.clear.frame(height: 30)
            }
        }
        .background(Backdrop(song: cover).opacity(0.9))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AlbumDetail: View {
    var id: String
    @ObservedObject private var lib = LibraryStore.shared
    var body: some View {
        if let a = lib.albums.first(where: { $0.id == id }) {
            DetailScreen(title: a.title, subtitle: "\(a.artist)\(a.year > 0 ? " • \(a.year)" : "") • \(a.songs.count) \(T("أغنية", "songs"))", cover: a.cover, songs: a.songs)
        } else { Text(T("غير موجود", "Not found")).foregroundStyle(Theme.sub) }
    }
}

struct ArtistDetail: View {
    var name: String
    @ObservedObject private var lib = LibraryStore.shared
    var body: some View {
        if let a = lib.artists.first(where: { $0.name.caseInsensitiveCompare(name) == .orderedSame }) {
            DetailScreen(title: a.name, subtitle: "\(a.albums.count) \(T("ألبوم", "albums")) • \(a.songs.count) \(T("أغنية", "songs"))", cover: a.cover, songs: a.songs)
        } else { Text(T("غير موجود", "Not found")).foregroundStyle(Theme.sub) }
    }
}

struct FolderDetail: View {
    var path: String
    @ObservedObject private var lib = LibraryStore.shared
    var body: some View {
        if let f = lib.folders.first(where: { $0.path == path }) {
            DetailScreen(title: f.name, subtitle: f.path, cover: nil, songs: f.songs, icon: "folder.fill")
        } else { Text(T("غير موجود", "Not found")).foregroundStyle(Theme.sub) }
    }
}

struct PlaylistDetail: View {
    var id: String
    @ObservedObject private var lib = LibraryStore.shared
    @ObservedObject private var store = UserStore.shared
    @State private var renaming = false
    @State private var name = ""
    @State private var editing = false

    var body: some View {
        if let p = store.playlists.first(where: { $0.id == id }) {
            let songs = p.songKeys.compactMap { lib.byKey[$0] }
            Group {
                if editing {
                    List {
                        ForEach(songs) { s in
                            HStack { ArtworkView(song: s, size: 40, radius: 8); Text(s.title).lineLimit(1) }
                        }
                        .onMove { store.movePlaylist(id, from: $0, to: $1) }
                        .onDelete { idx in idx.forEach { store.removeFromPlaylist(id, songs[$0].key) } }
                    }
                    .environment(\.editMode, .constant(.active))
                    .scrollContentBackground(.hidden)
                } else {
                    DetailScreen(title: p.name, subtitle: "\(songs.count) \(T("أغنية", "songs"))", cover: songs.first, songs: songs, playlistID: id, icon: "music.note.list")
                }
            }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button { editing.toggle() } label: { Label(editing ? T("تم", "Done") : T("ترتيب وحذف", "Reorder"), systemImage: "arrow.up.arrow.down") }
                        Button { name = p.name; renaming = true } label: { Label(T("إعادة تسمية", "Rename"), systemImage: "pencil") }
                        Button(role: .destructive) { store.deletePlaylist(id); AppState.shared.path.removeLast() } label: { Label(T("حذف", "Delete"), systemImage: "trash") }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .alert(T("إعادة تسمية", "Rename"), isPresented: $renaming) {
                TextField(T("الاسم", "Name"), text: $name)
                Button(T("حفظ", "Save")) { if !name.trimmed.isEmpty { store.renamePlaylist(id, name.trimmed) } }
                Button(T("إلغاء", "Cancel"), role: .cancel) {}
            }
        } else { Text(T("غير موجود", "Not found")).foregroundStyle(Theme.sub) }
    }
}

struct SmartDetail: View {
    var kind: String
    @ObservedObject private var lib = LibraryStore.shared
    @ObservedObject private var history = History.shared
    var body: some View {
        if let k = SmartKind.all.first(where: { $0.id == kind }) {
            let songs = k.songs()
            DetailScreen(title: k.title, subtitle: "\(songs.count) \(T("أغنية", "songs"))", cover: nil, songs: songs, icon: k.icon)
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Button { UserStore.shared.createPlaylist(k.title, keys: songs.map(\.key)); AppState.shared.show(T("حُفظت كقائمة", "Saved as playlist")) } label: {
                            Image(systemName: "square.and.arrow.down")
                        }
                    }
                }
        }
    }
}

struct SearchView: View {
    @ObservedObject private var lib = LibraryStore.shared
    @State private var q = ""
    var body: some View {
        let nq = LyricsMatcher.normalise(q)
        let songs = nq.isEmpty ? [] : lib.songs.filter { LyricsMatcher.normalise("\($0.title) \($0.artist) \($0.album)").contains(nq) }
        let artists = nq.isEmpty ? [] : lib.artists.filter { LyricsMatcher.normalise($0.name).contains(nq) }.prefix(6)
        let albums = nq.isEmpty ? [] : lib.albums.filter { LyricsMatcher.normalise($0.title).contains(nq) }.prefix(6)
        ScrollView {
            LazyVStack(spacing: 2) {
                if !artists.isEmpty { SectionTitle(T("الفنانون", "Artists")) }
                ForEach(Array(artists)) { a in
                    Button { AppState.shared.go(.artist(a.name)) } label: { CollectionRow(cover: a.cover, title: a.name, subtitle: "\(a.songs.count) \(T("أغنية", "songs"))", round: true) }
                }
                if !albums.isEmpty { SectionTitle(T("الألبومات", "Albums")) }
                ForEach(Array(albums)) { a in
                    Button { AppState.shared.go(.album(a.id)) } label: { CollectionRow(cover: a.cover, title: a.title, subtitle: a.artist) }
                }
                if !songs.isEmpty { SectionTitle(T("الأغاني", "Songs")) }
                ForEach(Array(songs.prefix(200).enumerated()), id: \.element.id) { i, s in SongRow(song: s, queue: songs, index: i) }
            }
        }
        .searchable(text: $q, placement: .navigationBarDrawer(displayMode: .always), prompt: T("ابحث في مكتبتك", "Search your library"))
        .navigationTitle(T("بحث", "Search"))
    }
}
