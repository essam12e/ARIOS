import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @ObservedObject private var lib = LibraryStore.shared
    @ObservedObject private var prefs = Prefs.shared
    @ObservedObject private var store = UserStore.shared
    @State private var tab: Int = Prefs.shared.libraryTab
    @State private var importing = false
    @Namespace private var ns

    static let tabs = [T("مجلدات", "Folders"), T("الفنانون", "Artists"), T("الألبومات", "Albums"),
                       T("المسارات", "Tracks"), T("قوائم التشغيل", "Playlists"), T("المفضلة", "Favorites")]

    var body: some View {
        VStack(spacing: 0) {
            header
            tabBar
            TabView(selection: $tab) {
                FoldersTab().tag(0)
                ArtistsTab().tag(1)
                AlbumsTab().tag(2)
                SongsTab().tag(3)
                PlaylistsTab().tag(4)
                FavoritesTab().tag(5)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .onChange(of: tab) { prefs.libraryTab = $0 }
        }
        .background(Color.clear)
        .toolbar(.hidden, for: .navigationBar)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.audio, .mp3, .mpeg4Audio], allowsMultipleSelection: true) { r in
            if case .success(let urls) = r {
                Task {
                    let n = await lib.importFiles(urls)
                    AppState.shared.show(T("تم استيراد \(n) ملف", "Imported \(n) file(s)"))
                }
            }
        }
        .refreshable { await lib.scan() }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Image("Logo").resizable().scaledToFit().frame(width: 38, height: 38)
                .shadow(color: Theme.accent.main.opacity(0.6), radius: 12)
            Text("Flowfy").font(.system(size: 26, weight: .heavy, design: .rounded))
                .foregroundStyle(LinearGradient(colors: [Theme.accent.light, Theme.accent.main], startPoint: .leading, endPoint: .trailing))
            Spacer()
            IconButton(system: "magnifyingglass", size: 18) { AppState.shared.go(.search) }
            IconButton(system: "plus", size: 18) { importing = true }
            Menu {
                Button { AppState.shared.go(.downloads) } label: { Label(T("التنزيلات", "Downloads"), systemImage: "arrow.down.circle") }
                Button { AppState.shared.go(.sound) } label: { Label(T("الصوت والمعادل", "Sound & EQ"), systemImage: "slider.vertical.3") }
                Button { AppState.shared.go(.stats) } label: { Label(T("الإحصائيات", "Statistics"), systemImage: "chart.bar") }
                Button { AppState.shared.go(.duplicates) } label: { Label(T("المكررات", "Duplicates"), systemImage: "square.on.square") }
                Button { AppState.shared.carMode = true } label: { Label(T("وضع السيارة", "Car mode"), systemImage: "car") }
                Button { Task { await lib.scan() } } label: { Label(T("إعادة الفحص", "Rescan"), systemImage: "arrow.clockwise") }
                Divider()
                Button { AppState.shared.go(.settings) } label: { Label(T("الإعدادات", "Settings"), systemImage: "gearshape") }
            } label: {
                Image(systemName: "ellipsis.circle").font(.system(size: 20, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 42, height: 42)
            }
        }
        .padding(.horizontal, 16).padding(.top, 6)
    }

    private var tabBar: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(Self.tabs.enumerated()), id: \.offset) { i, t in
                        Button {
                            withAnimation(.spring(response: 0.45, dampingFraction: 0.8)) { tab = i }
                            haptic()
                        } label: {
                            Text(t).font(.system(size: 14.5, weight: .bold))
                                .foregroundStyle(tab == i ? Color.black : Theme.sub)
                                .padding(.horizontal, 16).padding(.vertical, 9)
                                .background {
                                    if tab == i {
                                        Capsule().fill(LinearGradient(colors: [Theme.accent.light, Theme.accent.main], startPoint: .topLeading, endPoint: .bottomTrailing))
                                            .matchedGeometryEffect(id: "tab", in: ns)
                                            .shadow(color: Theme.accent.main.opacity(0.5), radius: 10)
                                    } else {
                                        Capsule().fill(Color.white.opacity(0.06))
                                    }
                                }
                        }
                        .buttonStyle(BounceStyle()).id(i)
                    }
                }.padding(.horizontal, 16).padding(.vertical, 10)
            }
            .onChange(of: tab) { t in withAnimation { proxy.scrollTo(t, anchor: .center) } }
            .onAppear { proxy.scrollTo(tab, anchor: .center) }
        }
    }
}

// MARK: - Tabs

struct SongsTab: View {
    @ObservedObject private var lib = LibraryStore.shared
    @ObservedObject private var prefs = Prefs.shared
    @ObservedObject private var history = History.shared

    var sorted: [Song] {
        var l = lib.songs
        switch prefs.songSort {
        case 1: l.sort { nameLess($0.artist, $1.artist) }
        case 2: l.sort { nameLess($0.album, $1.album) }
        case 3: l.sort { $0.dateAdded > $1.dateAdded }
        case 4: l.sort { $0.durationMs > $1.durationMs }
        default: break
        }
        return prefs.sortDescending ? l.reversed() : l
    }

    var body: some View {
        let songs = sorted
        if lib.songs.isEmpty {
            EmptyLibrary()
        } else {
            IndexedList(items: songs, letter: { s in
                switch prefs.songSort { case 1: return indexLetter(s.artist); case 2: return indexLetter(s.album); default: return indexLetter(s.title) }
            }, showIndex: prefs.songSort <= 2, header: {
                HStack {
                    PlayButtons(songs: songs)
                    Spacer()
                    Menu {
                        Picker("", selection: Binding(get: { prefs.songSort }, set: { prefs.songSort = $0 })) {
                            Text(T("العنوان", "Title")).tag(0); Text(T("الفنان", "Artist")).tag(1); Text(T("الألبوم", "Album")).tag(2)
                            Text(T("تاريخ الإضافة", "Date added")).tag(3); Text(T("المدة", "Duration")).tag(4)
                        }
                        Toggle(T("تنازلي", "Descending"), isOn: Binding(get: { prefs.sortDescending }, set: { prefs.sortDescending = $0 }))
                    } label: {
                        Label("\(songs.count)", systemImage: "arrow.up.arrow.down").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.sub)
                    }
                }
            }) { s, i in
                SongRow(song: s, queue: songs, index: i)
            }
        }
    }
}

struct EmptyLibrary: View {
    @ObservedObject private var lib = LibraryStore.shared
    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            Image("Logo").resizable().scaledToFit().frame(width: 110).shadow(color: Theme.accent.main.opacity(0.6), radius: 30)
            Text(lib.scanning ? T("جاري البحث عن الأغاني…", "Looking for music…") : T("مكتبتك فاضية", "Your library is empty"))
                .font(.system(size: 20, weight: .bold))
            Text(T("أضف أغانيك من زر + أو من تطبيق الملفات (مجلد فلوفاي)، أو اسمح بالوصول لمكتبة الآيفون.",
                   "Add music with +, from the Files app (Flowfy folder), or allow access to your iPhone library."))
                .font(.system(size: 14)).foregroundStyle(Theme.sub).multilineTextAlignment(.center).padding(.horizontal, 40)
            if lib.mediaAuth != .authorized {
                Button { lib.requestMusicAccess() } label: {
                    Text(T("السماح بمكتبة الموسيقى", "Allow music library")).font(.system(size: 15, weight: .bold)).foregroundStyle(.black)
                        .padding(.horizontal, 22).padding(.vertical, 12).background(Capsule().fill(Theme.gradient))
                }.buttonStyle(BounceStyle())
            }
            Spacer(); Spacer()
        }
    }
}

struct PlayButtons: View {
    var songs: [Song]
    var body: some View {
        HStack(spacing: 8) {
            Button { Player.shared.play(songs, start: 0, shuffled: false); AppState.shared.showPlayer = true } label: {
                Label(T("تشغيل", "Play"), systemImage: "play.fill").font(.system(size: 14, weight: .bold)).foregroundStyle(.black)
                    .padding(.horizontal, 16).padding(.vertical, 9).background(Capsule().fill(Theme.gradient))
                    .shadow(color: Theme.accent.main.opacity(0.45), radius: 10)
            }.buttonStyle(BounceStyle())
            Button { Player.shared.play(songs, start: 0, shuffled: true); AppState.shared.showPlayer = true } label: {
                Label(T("عشوائي", "Shuffle"), systemImage: "shuffle").font(.system(size: 14, weight: .bold)).foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 9).background(Capsule().fill(Color.white.opacity(0.08)))
            }.buttonStyle(BounceStyle())
        }.disabled(songs.isEmpty)
    }
}

/// A lazy list with the alphabet bar on the left edge.
struct IndexedList<Item: Identifiable, Header: View, Row: View>: View {
    var items: [Item]
    var letter: (Item) -> String
    var showIndex: Bool = true
    @ViewBuilder var header: () -> Header
    @ViewBuilder var row: (Item, Int) -> Row
    @State private var activeLetter: String?

    var body: some View {
        let firstIndex: [String: Item.ID] = {
            var m: [String: Item.ID] = [:]
            for it in items { let l = letter(it); if m[l] == nil { m[l] = it.id } }
            return m
        }()
        let letters = orderedLetters(Array(firstIndex.keys))
        ScrollViewReader { proxy in
            ZStack(alignment: .leading) {
                ScrollView {
                    LazyVStack(spacing: 2) {
                        header().padding(.horizontal, 16).padding(.bottom, 6)
                        ForEach(Array(items.enumerated()), id: \.element.id) { i, it in
                            row(it, i).id(it.id).staggerIn(i)
                        }
                        Color.clear.frame(height: 24)
                    }
                    .padding(isArabicUI ? .trailing : .leading, showIndex && letters.count > 3 ? 22 : 0)
                }
                if showIndex && letters.count > 3 {
                    AlphabetBar(letters: letters, active: $activeLetter) { l in
                        if let id = firstIndex[l] { proxy.scrollTo(id, anchor: .top) }
                    }
                    .frame(maxWidth: .infinity, alignment: isArabicUI ? .trailing : .leading)
                }
            }
            .overlay {
                if let a = activeLetter {
                    Text(a).font(.system(size: 44, weight: .heavy, design: .rounded)).foregroundStyle(.white)
                        .frame(width: 92, height: 92).glass(26, glow: true).transition(.scale.combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.3), value: activeLetter)
        }
    }

    private func orderedLetters(_ l: [String]) -> [String] {
        let latin = l.filter { $0.unicodeScalars.first.map { $0.value < 0x0600 } ?? false && $0 != "#" }.sorted()
        let ar = l.filter { $0.unicodeScalars.first.map { $0.value >= 0x0600 } ?? false }.sorted { $0.localizedStandardCompare($1) == .orderedAscending }
        return (l.contains("#") ? ["#"] : []) + latin + ar
    }
}

struct AlphabetBar: View {
    var letters: [String]
    @Binding var active: String?
    var onPick: (String) -> Void

    var body: some View {
        GeometryReader { g in
            let h = g.size.height / CGFloat(max(1, letters.count))
            VStack(spacing: 0) {
                ForEach(letters, id: \.self) { l in
                    Text(l).font(.system(size: min(11, h * 0.8), weight: .bold))
                        .foregroundStyle(active == l ? Theme.accent.main : Theme.sub)
                        .frame(width: 20, height: h)
                }
            }
            .background(Capsule().fill(Color.white.opacity(active == nil ? 0.03 : 0.08)))
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { v in
                    let i = min(letters.count - 1, max(0, Int(v.location.y / h)))
                    if active != letters[i] { active = letters[i]; onPick(letters[i]); UISelectionFeedbackGenerator().selectionChanged() }
                }
                .onEnded { _ in active = nil })
        }
        .frame(width: 20)
        .padding(.vertical, 12)
        .padding(.leading, 2)
    }
}

struct AlbumsTab: View {
    @ObservedObject private var lib = LibraryStore.shared
    @ObservedObject private var prefs = Prefs.shared
    var body: some View {
        if lib.albums.isEmpty { EmptyLibrary() } else if prefs.albumGrid {
            ScrollView {
                HStack { Spacer(); gridToggle }.padding(.horizontal, 16)
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)], spacing: 18) {
                    ForEach(Array(lib.albums.enumerated()), id: \.element.id) { i, a in
                        Button { AppState.shared.go(.album(a.id)) } label: { AlbumCard(album: a) }
                            .buttonStyle(BounceStyle(scale: 0.96)).staggerIn(i)
                            .contextMenu { CollectionMenu(songs: a.songs) }
                    }
                }.padding(.horizontal, 16).padding(.bottom, 24)
            }
        } else {
            IndexedList(items: lib.albums, letter: { indexLetter($0.title) }, header: { HStack { Spacer(); gridToggle } }) { a, _ in
                Button { AppState.shared.go(.album(a.id)) } label: {
                    CollectionRow(cover: a.cover, title: a.title, subtitle: "\(a.artist) • \(a.songs.count) \(T("أغنية", "songs"))")
                }.buttonStyle(BounceStyle(scale: 0.98)).contextMenu { CollectionMenu(songs: a.songs) }
            }
        }
    }
    private var gridToggle: some View {
        Button { prefs.albumGrid.toggle() } label: {
            Image(systemName: prefs.albumGrid ? "list.bullet" : "square.grid.2x2").foregroundStyle(Theme.sub).padding(6)
        }
    }
}

struct AlbumCard: View {
    var album: Album
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            GeometryReader { g in ArtworkView(song: album.cover, size: g.size.width, radius: 18) }
                .aspectRatio(1, contentMode: .fit)
                .shadow(color: .black.opacity(0.5), radius: 10, y: 6)
            Text(album.title).font(.system(size: 14.5, weight: .bold)).lineLimit(1).foregroundStyle(.white)
            Text(album.artist).font(.system(size: 12.5)).foregroundStyle(Theme.sub).lineLimit(1)
        }
    }
}

struct CollectionRow: View {
    var cover: Song?
    var title: String
    var subtitle: String
    var round = false
    var icon: String? = nil
    var body: some View {
        HStack(spacing: 12) {
            if let icon {
                Image(systemName: icon).font(.system(size: 20, weight: .semibold)).foregroundStyle(Theme.accent.main)
                    .frame(width: 52, height: 52).background(RoundedRectangle(cornerRadius: 14).fill(Theme.accent.main.opacity(0.14)))
            } else {
                ArtworkView(song: cover, size: 52, radius: round ? 26 : 12)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.system(size: 15.5, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                Text(subtitle).font(.system(size: 12.5)).foregroundStyle(Theme.sub).lineLimit(1)
            }
            Spacer()
            Image(systemName: isArabicUI ? "chevron.left" : "chevron.right").font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.faint)
        }
        .padding(.horizontal, 16).padding(.vertical, 6).contentShape(Rectangle())
    }
}

struct ArtistsTab: View {
    @ObservedObject private var lib = LibraryStore.shared
    var body: some View {
        if lib.artists.isEmpty { EmptyLibrary() } else {
            IndexedList(items: lib.artists, letter: { indexLetter($0.name) }, header: { EmptyView() }) { a, _ in
                Button { AppState.shared.go(.artist(a.name)) } label: {
                    CollectionRow(cover: a.cover, title: a.name, subtitle: "\(a.albums.count) \(T("ألبوم", "albums")) • \(a.songs.count) \(T("أغنية", "songs"))", round: true)
                }.buttonStyle(BounceStyle(scale: 0.98)).contextMenu { CollectionMenu(songs: a.songs) }
            }
        }
    }
}

struct FoldersTab: View {
    @ObservedObject private var lib = LibraryStore.shared
    var body: some View {
        if lib.folders.isEmpty { EmptyLibrary() } else {
            IndexedList(items: lib.folders, letter: { indexLetter($0.name) }, header: {
                HStack {
                    Spacer()
                    Button { AppState.shared.go(.folders) } label: {
                        Label(T("إدارة المجلدات", "Manage folders"), systemImage: "eye.slash").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.sub)
                    }
                }
            }) { f, _ in
                Button { AppState.shared.go(.folder(f.path)) } label: {
                    CollectionRow(cover: nil, title: f.name, subtitle: "\(f.songs.count) \(T("ملف", "files")) • \(f.path)", icon: "folder.fill")
                }.buttonStyle(BounceStyle(scale: 0.98)).contextMenu {
                    CollectionMenu(songs: f.songs)
                    Button(role: .destructive) {
                        Prefs.shared.excludedFolders.insert(f.path); lib.rebuild()
                    } label: { Label(T("إخفاء المجلد", "Hide folder"), systemImage: "eye.slash") }
                }
            }
        }
    }
}

struct FavoritesTab: View {
    @ObservedObject private var lib = LibraryStore.shared
    @ObservedObject private var store = UserStore.shared
    var body: some View {
        let songs = lib.songs.filter { store.favorites.contains($0.key) }
        if songs.isEmpty {
            VStack(spacing: 12) {
                Image(systemName: "heart").font(.system(size: 46)).foregroundStyle(Theme.accent.second)
                Text(T("ما فيه مفضلة للحين", "No favorites yet")).font(.system(size: 18, weight: .bold))
                Text(T("اضغط ♥ على أي أغنية", "Tap ♥ on any song")).foregroundStyle(Theme.sub)
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            IndexedList(items: songs, letter: { indexLetter($0.title) }, header: { HStack { PlayButtons(songs: songs); Spacer() } }) { s, i in
                SongRow(song: s, queue: songs, index: i)
            }
        }
    }
}

struct PlaylistsTab: View {
    @ObservedObject private var store = UserStore.shared
    @ObservedObject private var lib = LibraryStore.shared
    @State private var creating = false
    @State private var name = ""

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 4) {
                Button { creating = true } label: {
                    CollectionRow(cover: nil, title: T("قائمة جديدة", "New playlist"), subtitle: T("أنشئ قائمة تشغيل", "Create a playlist"), icon: "plus")
                }.buttonStyle(BounceStyle(scale: 0.98))
                SectionTitle(T("قوائم ذكية", "Smart playlists"))
                ForEach(SmartKind.all, id: \.id) { k in
                    Button { AppState.shared.go(.smart(k.id)) } label: {
                        CollectionRow(cover: nil, title: k.title, subtitle: "\(k.songs().count) \(T("أغنية", "songs"))", icon: k.icon)
                    }.buttonStyle(BounceStyle(scale: 0.98))
                }
                if !store.playlists.isEmpty { SectionTitle(T("قوائمك", "Your playlists")) }
                ForEach(Array(store.playlists.enumerated()), id: \.element.id) { i, p in
                    let songs = p.songKeys.compactMap { lib.byKey[$0] }
                    Button { AppState.shared.go(.playlist(p.id)) } label: {
                        CollectionRow(cover: songs.first, title: p.name, subtitle: "\(songs.count) \(T("أغنية", "songs"))", icon: songs.isEmpty ? "music.note.list" : nil)
                    }.buttonStyle(BounceStyle(scale: 0.98)).staggerIn(i)
                    .contextMenu {
                        CollectionMenu(songs: songs)
                        Button(role: .destructive) { store.deletePlaylist(p.id) } label: { Label(T("حذف القائمة", "Delete playlist"), systemImage: "trash") }
                    }
                }
                Color.clear.frame(height: 30)
            }
        }
        .alert(T("قائمة جديدة", "New playlist"), isPresented: $creating) {
            TextField(T("الاسم", "Name"), text: $name)
            Button(T("إنشاء", "Create")) { if !name.trimmed.isEmpty { store.createPlaylist(name.trimmed) }; name = "" }
            Button(T("إلغاء", "Cancel"), role: .cancel) { name = "" }
        }
    }
}

struct SectionTitle: View {
    var text: String
    init(_ t: String) { text = t }
    var body: some View {
        Text(text).font(.system(size: 13, weight: .heavy)).foregroundStyle(Theme.accent.main)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 18).padding(.top, 14).padding(.bottom, 4)
    }
}

// MARK: - Smart playlists

struct SmartKind {
    var id: String; var title: String; var icon: String; var songs: () -> [Song]

    @MainActor static var all: [SmartKind] {
        let lib = LibraryStore.shared, h = History.shared
        var counts: [String: Int] = [:], last: [String: Double] = [:]
        for e in h.events { counts[e.key, default: 0] += 1; last[e.key] = max(last[e.key] ?? 0, e.at) }
        return [
            SmartKind(id: "top", title: T("الأكثر تشغيلاً", "Most played"), icon: "flame.fill") {
                lib.songs.filter { (counts[$0.key] ?? 0) > 0 }.sorted { (counts[$0.key] ?? 0) > (counts[$1.key] ?? 0) }.prefix(50).map { $0 }
            },
            SmartKind(id: "recent", title: T("أضيفت حديثاً", "Recently added"), icon: "sparkles") {
                Array(lib.songs.sorted { $0.dateAdded > $1.dateAdded }.prefix(50))
            },
            SmartKind(id: "history", title: T("شُغّلت مؤخراً", "Recently played"), icon: "clock.arrow.circlepath") {
                lib.songs.filter { last[$0.key] != nil }.sorted { (last[$0.key] ?? 0) > (last[$1.key] ?? 0) }.prefix(50).map { $0 }
            },
            SmartKind(id: "never", title: T("لم تُسمع بعد", "Never played"), icon: "moon.zzz.fill") {
                lib.songs.filter { counts[$0.key] == nil }
            },
            SmartKind(id: "forgotten", title: T("منسية", "Forgotten"), icon: "hourglass") {
                let cutoff = Date().timeIntervalSince1970 - 60 * 86400
                return lib.songs.filter { (last[$0.key] ?? .infinity) < cutoff }
            },
            SmartKind(id: "long", title: T("طويلة (+٢٠ دقيقة)", "Long (20+ min)"), icon: "headphones") {
                lib.songs.filter { $0.durationMs > 20 * 60_000 }
            },
        ]
    }
}
