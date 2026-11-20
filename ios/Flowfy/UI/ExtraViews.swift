import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

// MARK: - Downloads

struct DownloadsView: View {
    @ObservedObject private var dl = Downloader.shared
    @State private var link = ""
    @State private var query = ""
    @State private var hits: [Downloader.ArchiveHit] = []
    @State private var searching = false
    @State private var opened: Downloader.ArchiveHit?
    @State private var files: [Downloader.ArchiveFile] = []

    var body: some View {
        List {
            Section(footer: Text(T("للتنزيل القانوني فقط: محتوى Internet Archive المجاني، أو روابط مباشرة لملفات تملكها.",
                                   "Legal downloads only: free Internet Archive content or direct links to files you own."))) {
                HStack {
                    TextField(T("رابط ملف صوتي مباشر", "Direct audio file link"), text: $link).keyboardType(.URL).textInputAutocapitalization(.never)
                    Button(T("تنزيل", "Download")) { dl.add(link); link = "" }.disabled(link.trimmed.isEmpty)
                }
            }
            Section(T("Internet Archive", "Internet Archive")) {
                HStack {
                    TextField(T("ابحث عن تسجيلات مجانية", "Search free recordings"), text: $query).onSubmit(search)
                    if searching { ProgressView() } else { Button(action: search) { Image(systemName: "magnifyingglass") } }
                }
                ForEach(hits) { h in
                    Button {
                        opened = h; files = []
                        Task { files = await Downloader.archiveFiles(h.id) }
                    } label: {
                        VStack(alignment: .leading) {
                            Text(h.title).foregroundStyle(.white).lineLimit(2)
                            if !h.creator.isEmpty { Text(h.creator).font(.caption).foregroundStyle(Theme.sub) }
                        }
                    }
                }
            }
            if !dl.items.isEmpty {
                Section {
                    ForEach(dl.items) { it in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(it.name).lineLimit(1)
                                Spacer()
                                switch it.state {
                                case 1: Button { dl.pause(it.id) } label: { Image(systemName: "pause.circle.fill") }
                                case 2, 4: Button { dl.start(it.id) } label: { Image(systemName: it.state == 4 ? "arrow.clockwise.circle.fill" : "play.circle.fill") }
                                case 3: Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.accent.main)
                                default: ProgressView()
                                }
                            }
                            if it.state != 3 {
                                ProgressView(value: it.progress).tint(Theme.accent.main)
                                Text(it.state == 4 ? T("فشل التنزيل", "Failed") : "\(ByteCountFormatter.string(fromByteCount: it.bytes, countStyle: .file)) / \(it.total > 0 ? ByteCountFormatter.string(fromByteCount: it.total, countStyle: .file) : "?")")
                                    .font(.caption).foregroundStyle(Theme.sub)
                            }
                        }
                        .swipeActions { Button(role: .destructive) { dl.remove(it.id) } label: { Label(T("حذف", "Remove"), systemImage: "trash") } }
                    }
                } header: {
                    HStack { Text(T("التنزيلات", "Downloads")); Spacer(); Button(T("مسح المكتمل", "Clear done")) { dl.clearFinished() }.font(.caption) }
                }
            }
        }
        .scrollContentBackground(.hidden).background(AppBackground())
        .navigationTitle(T("التنزيلات", "Downloads"))
        .sheet(item: $opened) { h in
            NavigationStack {
                List(files) { f in
                    Button {
                        dl.add(f.url, name: (f.name as NSString).lastPathComponent)
                        AppState.shared.show(T("بدأ التنزيل", "Download started"))
                    } label: {
                        HStack {
                            Text((f.name as NSString).lastPathComponent).foregroundStyle(.white).lineLimit(2)
                            Spacer()
                            Text(ByteCountFormatter.string(fromByteCount: f.size, countStyle: .file)).font(.caption).foregroundStyle(Theme.sub)
                            Image(systemName: "arrow.down.circle").foregroundStyle(Theme.accent.main)
                        }
                    }
                }
                .overlay { if files.isEmpty { ProgressView() } }
                .navigationTitle(h.title).navigationBarTitleDisplayMode(.inline)
            }
            .preferredColorScheme(.dark)
            .presentationDetents([.medium, .large])
        }
    }

    private func search() {
        guard !query.trimmed.isEmpty else { return }
        searching = true
        Task { hits = await Downloader.searchArchive(query.trimmed); searching = false }
    }
}

// MARK: - Statistics & Wrapped

struct Insights {
    var totalMs: Int64 = 0
    var plays = 0
    var topSongs: [(String, String, Int)] = []
    var topArtists: [(String, Int64)] = []
    var byHour = [Int64](repeating: 0, count: 24)
    var streak = 0

    @MainActor static func compute(days: Int?) -> Insights {
        let cutoff = days.map { Date().timeIntervalSince1970 - Double($0) * 86400 } ?? 0
        let ev = History.shared.events.filter { $0.at >= cutoff }
        var i = Insights()
        i.plays = ev.count
        i.totalMs = ev.reduce(0) { $0 + $1.ms }
        var songs: [String: (String, String, Int)] = [:], artists: [String: Int64] = [:]
        let cal = Calendar.current
        var daysSet = Set<Date>()
        for e in ev {
            let d = Date(timeIntervalSince1970: e.at)
            songs[e.key, default: (e.title, e.artist, 0)].2 += 1
            artists[e.artist, default: 0] += e.ms
            i.byHour[cal.component(.hour, from: d)] += e.ms
            daysSet.insert(cal.startOfDay(for: d))
        }
        i.topSongs = songs.values.sorted { $0.2 > $1.2 }.prefix(10).map { $0 }
        i.topArtists = artists.sorted { $0.value > $1.value }.prefix(10).map { ($0.key, $0.value) }
        var day = cal.startOfDay(for: Date())
        if !daysSet.contains(day) { day = cal.date(byAdding: .day, value: -1, to: day)! }
        while daysSet.contains(day) { i.streak += 1; day = cal.date(byAdding: .day, value: -1, to: day)! }
        return i
    }
}

struct StatsView: View {
    @ObservedObject private var history = History.shared
    @State private var range = 30
    var body: some View {
        let s = Insights.compute(days: range == 0 ? nil : range)
        ScrollView {
            VStack(spacing: 16) {
                Picker("", selection: $range) {
                    Text(T("أسبوع", "Week")).tag(7); Text(T("شهر", "Month")).tag(30); Text(T("سنة", "Year")).tag(365); Text(T("الكل", "All")).tag(0)
                }.pickerStyle(.segmented)
                HStack(spacing: 12) {
                    stat(T("دقيقة استماع", "minutes"), "\(s.totalMs / 60000)", "headphones")
                    stat(T("تشغيلة", "plays"), "\(s.plays)", "play.circle")
                    stat(T("أيام متتالية", "day streak"), "\(s.streak)", "flame")
                }
                Button { AppState.shared.go(.wrapped) } label: {
                    HStack { Image(systemName: "sparkles"); Text(T("ملخّصك السنوي", "Your Wrapped")).bold(); Spacer(); Image(systemName: isArabicUI ? "chevron.left" : "chevron.right") }
                        .foregroundStyle(.black).padding(16).background(RoundedRectangle(cornerRadius: 20).fill(Theme.gradient))
                }.buttonStyle(BounceStyle())
                card(T("أكثر الأغاني", "Top songs")) {
                    ForEach(Array(s.topSongs.enumerated()), id: \.offset) { i, t in
                        HStack { Text("\(i + 1)").font(.headline).foregroundStyle(Theme.accent.main).frame(width: 26)
                            VStack(alignment: .leading) { Text(t.0).lineLimit(1); Text(t.1).font(.caption).foregroundStyle(Theme.sub) }
                            Spacer(); Text("\(t.2)×").foregroundStyle(Theme.sub) }
                    }
                }
                card(T("أكثر الفنانين", "Top artists")) {
                    ForEach(Array(s.topArtists.enumerated()), id: \.offset) { i, a in
                        HStack { Text("\(i + 1)").font(.headline).foregroundStyle(Theme.accent.main).frame(width: 26); Text(a.0).lineLimit(1); Spacer()
                            Text("\(a.1 / 60000) \(T("د", "min"))").foregroundStyle(Theme.sub) }
                    }
                }
                card(T("متى تسمع", "When you listen")) {
                    let mx = max(1, s.byHour.max() ?? 1)
                    HStack(alignment: .bottom, spacing: 3) {
                        ForEach(0..<24, id: \.self) { h in
                            Capsule().fill(Theme.gradient).frame(height: max(3, 90 * CGFloat(s.byHour[h]) / CGFloat(mx)))
                        }
                    }.frame(height: 90).environment(\.layoutDirection, .leftToRight)
                    HStack { Text("0"); Spacer(); Text("12"); Spacer(); Text("23") }.font(.caption2).foregroundStyle(Theme.sub).environment(\.layoutDirection, .leftToRight)
                }
            }.padding(16)
        }
        .background(AppBackground())
        .navigationTitle(T("الإحصائيات", "Statistics"))
    }

    private func stat(_ label: String, _ value: String, _ icon: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: icon).foregroundStyle(Theme.accent.main)
            Text(value).font(.system(size: 22, weight: .heavy, design: .rounded))
            Text(label).font(.caption).foregroundStyle(Theme.sub)
        }.frame(maxWidth: .infinity).padding(.vertical, 14).glass(18)
    }

    private func card<C: View>(_ title: String, @ViewBuilder _ c: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 10) { Text(title).font(.headline); c() }.padding(16).frame(maxWidth: .infinity, alignment: .leading).glass(22)
    }
}

struct WrappedView: View {
    @State private var page = 0
    var body: some View {
        let s = Insights.compute(days: 365)
        TabView(selection: $page) {
            slide(0, T("سنتك مع فلوفاي", "Your year on Flowfy"), "\(s.totalMs / 60000)", T("دقيقة من الموسيقى", "minutes of music"))
            slide(1, T("فنانك الأول", "Your top artist"), s.topArtists.first?.0 ?? "—", T("\((s.topArtists.first?.1 ?? 0) / 60000) دقيقة معه", "\((s.topArtists.first?.1 ?? 0) / 60000) minutes together"))
            slide(2, T("أغنيتك", "Your song"), s.topSongs.first?.0 ?? "—", T("شغّلتها \(s.topSongs.first?.2 ?? 0) مرة", "played \(s.topSongs.first?.2 ?? 0) times"))
            slide(3, T("ساعتك المفضلة", "Your hour"), "\(s.byHour.firstIndex(of: s.byHour.max() ?? 0) ?? 0):00", T("أكثر وقت تسمع فيه", "when you listen most"))
            slide(4, T("سلسلة الاستماع", "Listening streak"), "\(s.streak)", T("يوم متتالي", "days in a row"))
        }
        .tabViewStyle(.page)
        .background(LinearGradient(colors: [Theme.accent.deep, Theme.bg, Theme.accent.second.opacity(0.5)], startPoint: .topLeading, endPoint: .bottomTrailing).ignoresSafeArea())
        .navigationTitle("Wrapped")
    }

    private func slide(_ i: Int, _ title: String, _ big: String, _ sub: String) -> some View {
        VStack(spacing: 18) {
            Image("Logo").resizable().scaledToFit().frame(width: 70).shadow(color: Theme.accent.main, radius: 20)
            Text(title).font(.system(size: 22, weight: .bold)).foregroundStyle(Theme.accent.light)
            Text(big).font(.system(size: 54, weight: .heavy, design: .rounded)).multilineTextAlignment(.center).minimumScaleFactor(0.4).lineLimit(2)
                .shadow(color: Theme.accent.main.opacity(0.7), radius: 24)
            Text(sub).font(.system(size: 18, weight: .semibold)).foregroundStyle(Theme.sub)
        }
        .padding(30).tag(i)
    }
}

// MARK: - Duplicates

struct DuplicatesView: View {
    @ObservedObject private var lib = LibraryStore.shared
    var body: some View {
        let groups = Dictionary(grouping: lib.songs, by: { "\(LyricsMatcher.normalise(LyricsMatcher.clean($0.title)))|\(LyricsMatcher.normalise($0.artist))|\($0.durationMs / 3000)" })
            .values.filter { $0.count > 1 }.sorted { nameLess($0[0].title, $1[0].title) }
        List {
            if groups.isEmpty { Text(T("ما فيه أغاني مكررة 🎉", "No duplicates 🎉")).foregroundStyle(Theme.sub) }
            ForEach(groups, id: \.first!.id) { g in
                Section(g[0].title) {
                    ForEach(g) { s in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(s.folder).lineLimit(1)
                                Text("\(s.ext) • \(ByteCountFormatter.string(fromByteCount: s.size, countStyle: .file)) • \(fmtTime(s.durationMs))").font(.caption).foregroundStyle(Theme.sub)
                            }
                            Spacer()
                            if s.isFile {
                                Button(role: .destructive) { Task { await lib.delete(s) } } label: { Image(systemName: "trash") }.buttonStyle(.borderless)
                            }
                        }
                    }
                }
            }
        }
        .scrollContentBackground(.hidden).background(AppBackground())
        .navigationTitle(T("المكررات", "Duplicates"))
    }
}

// MARK: - Backup

struct BackupView: View {
    @State private var file: URL?
    @State private var importing = false
    var body: some View {
        List {
            Section(footer: Text(T("يشمل الإعدادات والقوائم والمفضلة والكلمات المحفوظة وسجل الاستماع. متوافق مع نسخة الأندرويد للقوائم والمفضلة.",
                                   "Includes settings, playlists, favorites, saved lyrics and history."))) {
                Button(T("إنشاء نسخة احتياطية", "Create backup")) { file = Backup.make() }
                if let file { ShareLink(item: file) { Label(T("حفظ/مشاركة الملف", "Save / share file"), systemImage: "square.and.arrow.up") } }
                Button(T("استعادة من ملف", "Restore from file")) { importing = true }
            }
        }
        .scrollContentBackground(.hidden).background(AppBackground())
        .navigationTitle(T("النسخ الاحتياطي", "Backup"))
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .data]) { r in
            if case .success(let u) = r { Task { let ok = await Backup.restore(from: u); AppState.shared.show(ok ? T("تمت الاستعادة", "Restored") : T("ملف غير صالح", "Invalid file")) } }
        }
    }
}

// MARK: - Tag editor

struct TagEditorView: View {
    var song: Song
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var artist = ""
    @State private var album = ""
    @State private var year = ""
    @State private var photo: PhotosPickerItem?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Spacer()
                        VStack(spacing: 10) {
                            ArtworkView(song: song, size: 150, radius: 22)
                            PhotosPicker(selection: $photo, matching: .images) { Text(T("تغيير الصورة", "Change artwork")) }
                            HStack {
                                Button(T("بحث أونلاين", "Find online")) { Artwork.shared.refetch(song) }
                                Button(T("إزالة المخصصة", "Remove custom"), role: .destructive) { Artwork.shared.removeCustom(song) }
                            }.font(.caption).buttonStyle(.borderless)
                        }
                        Spacer()
                    }
                }.listRowBackground(Color.clear)
                Section {
                    TextField(T("العنوان", "Title"), text: $title)
                    TextField(T("الفنان", "Artist"), text: $artist)
                    TextField(T("الألبوم", "Album"), text: $album)
                    TextField(T("السنة", "Year"), text: $year).keyboardType(.numberPad)
                } footer: { Text(T("التعديل يُحفظ داخل فلوفاي ولا يغيّر الملف الأصلي.", "Edits are stored in Flowfy; the original file is unchanged.")) }
            }
            .scrollContentBackground(.hidden).background(Theme.bg)
            .navigationTitle(T("تعديل المعلومات", "Edit info")).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(T("إلغاء", "Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(T("حفظ", "Save")) {
                        LibraryStore.shared.setOverride(song, title: title.trimmed, artist: artist.trimmed, album: album.trimmed, year: Int(year) ?? 0)
                        Player.shared.refreshSongs()
                        dismiss()
                    }.bold()
                }
            }
            .onAppear { title = song.title; artist = song.artist; album = song.album; year = song.year > 0 ? "\(song.year)" : "" }
            .onChange(of: photo) { p in
                Task {
                    if let d = try? await p?.loadTransferable(type: Data.self), let img = UIImage(data: d) { Artwork.shared.setCustom(song, img) }
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

// MARK: - Car mode

struct CarModeView: View {
    @ObservedObject private var player = Player.shared
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ZStack {
            Backdrop(song: player.current)
            VStack(spacing: 26) {
                HStack { Spacer(); IconButton(system: "xmark", size: 24) { dismiss() } }
                Spacer()
                Text(player.current?.title ?? "—").font(.system(size: 36, weight: .heavy)).multilineTextAlignment(.center).lineLimit(2)
                Text(player.current?.artist ?? "").font(.system(size: 22, weight: .semibold)).foregroundStyle(Theme.sub)
                ProgressBar().padding(.horizontal, 10)
                HStack(spacing: 20) {
                    big("backward.fill") { player.previous() }
                    big(player.isPlaying ? "pause.fill" : "play.fill", primary: true) { player.toggle() }
                    big("forward.fill") { player.next() }
                }
                .environment(\.layoutDirection, .leftToRight)
                Spacer()
            }
            .padding(24)
        }
        .preferredColorScheme(.dark)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private func big(_ icon: String, primary: Bool = false, _ a: @escaping () -> Void) -> some View {
        Button { a(); haptic(.medium) } label: {
            Image(systemName: icon).font(.system(size: 44, weight: .bold)).foregroundStyle(primary ? .black : .white)
                .frame(maxWidth: .infinity).frame(height: 130)
                .background(RoundedRectangle(cornerRadius: 32).fill(primary ? AnyShapeStyle(Theme.gradient) : AnyShapeStyle(Color.white.opacity(0.1))))
        }.buttonStyle(BounceStyle())
    }
}
