import SwiftUI

struct EditLine: Identifiable, Equatable {
    var id = UUID()
    var text: String
    var time: Int64?
    var translation = false
}

/// Edit lyrics text and timing: tap-to-sync while playing, nudge, reset, automatic timing.
struct LyricsEditorView: View {
    var song: Song
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var player = Player.shared
    @State private var lines: [EditLine] = []
    @State private var cursor = 0
    @State private var textMode = false
    @State private var text = ""
    @State private var confirmReset = false
    @State private var syncing = false
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if textMode {
                    TextEditor(text: $text)
                        .font(Theme.lyrics(18)).scrollContentBackground(.hidden)
                        .padding(12).glass(18).padding(14)
                    Text(T("سطر لكل جملة. يمكنك لصق ملف LRC كامل.", "One line per phrase. You can paste a full LRC file."))
                        .font(.footnote).foregroundStyle(Theme.sub).padding(.bottom, 8)
                } else {
                    syncList
                    syncControls
                }
            }
            .background(AppBackground())
            .navigationTitle(T("تعديل ومزامنة", "Edit & sync")).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(T("إلغاء", "Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button(T("حفظ", "Save")) { save() }.bold() }
                ToolbarItem(placement: .principal) {
                    Picker("", selection: Binding(get: { textMode }, set: { switchMode($0) })) {
                        Text(T("التوقيت", "Timing")).tag(false); Text(T("النص", "Text")).tag(true)
                    }.pickerStyle(.segmented).frame(width: 180)
                }
            }
            .confirmationDialog(T("إعادة ضبط التوقيت؟", "Reset timing?"), isPresented: $confirmReset, titleVisibility: .visible) {
                Button(T("إعادة ضبط", "Reset"), role: .destructive) { resetTiming() }
            } message: { Text(T("سيتم حذف كل التوقيتات وترجع الكلمات كأنها ما تزامنت.", "All timings are removed, as if never synced.")) }
        }
        .preferredColorScheme(.dark)
        .task { if !loaded { await load() } }
    }

    private var syncList: some View {
        ScrollViewReader { proxy in
            List {
                ForEach(Array(lines.enumerated()), id: \.element.id) { i, l in
                    HStack(spacing: 10) {
                        Text(l.time.map { LrcWriter.stamp($0) } ?? "[--:--.--]")
                            .font(.system(size: 13, weight: .semibold).monospacedDigit())
                            .foregroundStyle(l.time == nil ? Theme.faint : Theme.accent.main)
                            .environment(\.layoutDirection, .leftToRight)
                        Text(l.text.isEmpty ? "♪" : l.text).font(Theme.lyrics(l.translation ? 14 : 17))
                            .foregroundStyle(l.translation ? Theme.sub : .white)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.vertical, 4)
                    .listRowBackground(i == cursor ? Theme.accent.main.opacity(0.18) : Color.white.opacity(0.03))
                    .contentShape(Rectangle())
                    .onTapGesture { cursor = i }
                    .swipeActions {
                        Button { lines[i].time = nil } label: { Label(T("مسح", "Clear"), systemImage: "xmark") }.tint(.gray)
                        if let t = l.time { Button { player.seek(to: t); player.play() } label: { Label(T("تشغيل", "Play"), systemImage: "play") }.tint(Theme.accent.deep) }
                    }
                    .id(l.id)
                }
            }
            .scrollContentBackground(.hidden)
            .onChange(of: cursor) { c in if lines.indices.contains(c) { withAnimation { proxy.scrollTo(lines[c].id, anchor: .center) } } }
        }
    }

    private var syncControls: some View {
        VStack(spacing: 12) {
            ProgressBar().padding(.horizontal, 6)
            HStack(spacing: 10) {
                small("gobackward.5") { player.seek(to: max(0, player.positionMs() - 5000)) }
                small(player.isPlaying ? "pause.fill" : "play.fill") { if player.current?.id != song.id { Player.shared.play([song]) } else { player.toggle() } }
                small("goforward.5") { player.seek(to: player.positionMs() + 5000) }
                small("minus") { nudge(-100) }
                small("plus") { nudge(100) }
            }
            HStack(spacing: 10) {
                Button { stamp() } label: {
                    Label(T("ثبّت التوقيت", "Stamp time"), systemImage: "hand.tap.fill").font(.system(size: 16, weight: .bold)).foregroundStyle(.black)
                        .frame(maxWidth: .infinity).padding(.vertical, 14).background(Capsule().fill(Theme.gradient))
                }.buttonStyle(BounceStyle())
            }
            HStack(spacing: 10) {
                Button { autoSync() } label: {
                    HStack { if syncing { ProgressView().tint(.white) } else { Image(systemName: "waveform") }; Text(T("مزامنة تلقائية", "Auto-sync")) }
                        .font(.system(size: 14, weight: .bold)).frame(maxWidth: .infinity).padding(.vertical, 11).glass(16)
                }.disabled(syncing)
                Button { confirmReset = true } label: {
                    Label(T("إعادة ضبط", "Reset"), systemImage: "arrow.counterclockwise").font(.system(size: 14, weight: .bold))
                        .frame(maxWidth: .infinity).padding(.vertical, 11).glass(16)
                }
            }.foregroundStyle(.white)
        }
        .padding(16)
        .background(.ultraThinMaterial.opacity(0.6))
    }

    private func small(_ icon: String, _ a: @escaping () -> Void) -> some View {
        Button(action: a) { Image(systemName: icon).font(.system(size: 17, weight: .bold)).foregroundStyle(.white).frame(maxWidth: .infinity).frame(height: 42).glass(14) }
            .buttonStyle(BounceStyle())
    }

    private func load() async {
        loaded = true
        if let l = await LyricsStore.shared.getLocal(song) {
            var out: [EditLine] = []
            for line in l.lines {
                out.append(EditLine(text: line.text, time: l.synced ? line.start : nil))
                if let t = line.translation, !t.isEmpty { out.append(EditLine(text: t, time: l.synced ? line.start : nil, translation: true)) }
            }
            lines = out
        }
        text = lines.filter { !$0.translation }.map(\.text).joined(separator: "\n")
        if lines.isEmpty { textMode = true }
    }

    private func switchMode(_ toText: Bool) {
        if toText {
            text = lines.filter { !$0.translation }.map(\.text).joined(separator: "\n")
        } else {
            if LrcParser.looksSynced(text), let l = LrcParser.parse(text, source: "Manual", durationMs: song.durationMs) {
                lines = l.lines.map { EditLine(text: $0.text, time: $0.start) }
            } else {
                let old = lines.filter { !$0.translation }
                let newTexts = text.components(separatedBy: "\n").map(\.trimmed).filter { !$0.isEmpty }
                lines = newTexts.enumerated().map { i, t in EditLine(text: t, time: i < old.count && old[i].text == t ? old[i].time : nil) }
            }
            cursor = 0
        }
        textMode = toText
    }

    private func stamp() {
        guard lines.indices.contains(cursor) else { return }
        if player.current?.id != song.id { AppState.shared.show(T("شغّل الأغنية أولاً", "Play the song first")); return }
        let t = player.positionMs()
        lines[cursor].time = t
        // a translation line under it shares the time
        if cursor + 1 < lines.count && lines[cursor + 1].translation { lines[cursor + 1].time = t; cursor += 1 }
        haptic()
        cursor = min(lines.count - 1, cursor + 1)
    }

    private func nudge(_ ms: Int64) {
        guard lines.indices.contains(cursor), let t = lines[cursor].time else { return }
        lines[cursor].time = max(0, t + ms)
    }

    private func resetTiming() {
        lines = lines.filter { !$0.translation }.map { EditLine(text: $0.text, time: nil) }
        let plain = lines.map(\.text).joined(separator: "\n")
        LyricsStore.shared.saveRaw(song, plain, source: LyricsStore.manual)
        LyricsStore.shared.markNoAutoSync(song)
        UserStore.shared.setOffset(song.key, 0)
        cursor = 0
        AppState.shared.show(T("رجعت الكلمات بدون توقيت", "Timing reset"))
    }

    private func autoSync() {
        let base = lines.filter { !$0.translation }.map(\.text).filter { !$0.trimmed.isEmpty }
        guard base.count >= 2 else { AppState.shared.show(T("أضف الكلمات أولاً", "Add the lyrics first")); return }
        syncing = true
        Task {
            let plain = Lyrics(lines: base.map { LyricLine(start: 0, end: 0, text: $0, words: []) }, synced: false, source: "Manual")
            let ok = await LyricsStore.shared.autoSync(song, plain, allowNetwork: true)
            syncing = false
            if ok, let l = await LyricsStore.shared.getLocal(song), l.synced {
                lines = l.lines.map { EditLine(text: $0.text, time: $0.start) }
                AppState.shared.show(T("تم تطبيق المزامنة التلقائية", "Auto-sync applied"))
            } else {
                AppState.shared.show(T("تعذّرت المزامنة التلقائية", "Couldn't auto-sync"))
            }
        }
    }

    private func save() {
        if textMode { switchMode(false) }
        let timed = lines.contains { $0.time != nil }
        var out = ""
        if timed {
            var last: Int64 = 0
            for l in lines {
                let t = l.time ?? last
                last = t
                out += LrcWriter.stamp(t) + l.text + "\n"
            }
        } else {
            out = lines.filter { !$0.translation }.map(\.text).joined(separator: "\n")
        }
        if out.trimmed.isEmpty { LyricsStore.shared.delete(song) } else { LyricsStore.shared.saveRaw(song, out, source: LyricsStore.manual) }
        if !timed { LyricsStore.shared.markNoAutoSync(song) }
        AppState.shared.show(T("تم الحفظ", "Saved"))
        dismiss()
    }
}

/// Manual lyrics search across all sources.
struct LyricsSearchView: View {
    var song: Song
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var artist = ""
    @State private var results: [ScoredCandidate] = []
    @State private var searching = false
    @State private var preview: Lyrics?
    @State private var previewCand: ScoredCandidate?
    @State private var loadingID: UUID?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField(T("العنوان", "Title"), text: $title)
                    TextField(T("الفنان", "Artist"), text: $artist)
                    Button { search() } label: {
                        HStack { if searching { ProgressView() }; Text(T("بحث", "Search")).bold() }
                    }.disabled(searching || title.trimmed.isEmpty)
                }
                Section {
                    ForEach(results) { r in
                        Button { open(r) } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(r.candidate.title).foregroundStyle(.white).lineLimit(1)
                                    Text("\(r.candidate.artist) • \(r.candidate.sourceName)\(r.candidate.durationSec.map { " • " + fmtTime(Int64($0) * 1000) } ?? "")")
                                        .font(.caption).foregroundStyle(Theme.sub).lineLimit(1)
                                }
                                Spacer()
                                if loadingID == r.id { ProgressView() }
                                else if r.confident { Image(systemName: "checkmark.seal.fill").foregroundStyle(Theme.accent.main) }
                                if r.candidate.syncedHint == true { Image(systemName: "clock").foregroundStyle(Theme.sub) }
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden).background(Theme.bg)
            .navigationTitle(T("بحث عن الكلمات", "Find lyrics")).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(T("إغلاق", "Close")) { dismiss() } } }
            .sheet(item: Binding(get: { previewCand }, set: { previewCand = $0 })) { c in
                NavigationStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 10) {
                            ForEach(Array((preview?.lines ?? []).enumerated()), id: \.offset) { _, l in
                                Text(l.text).font(Theme.lyrics(17)).frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }.padding()
                    }
                    .background(Theme.bg)
                    .navigationTitle(c.candidate.sourceName).navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button(T("رجوع", "Back")) { previewCand = nil } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button(T("اعتماد", "Use")) {
                                if let p = preview { LyricsStore.shared.save(song, p, manual: false); UserStore.shared.setOffset(song.key, 0) }
                                previewCand = nil
                                AppState.shared.show(T("تم اعتماد الكلمات", "Lyrics applied"))
                                dismiss()
                            }.bold()
                        }
                    }
                }.preferredColorScheme(.dark)
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            let q = LyricsStore.shared.queries(for: song).first
            title = q?.title ?? song.title; artist = q?.artist ?? ""
            search()
        }
    }

    private func search() {
        searching = true
        Task {
            var qs = [LyricsQuery(title: title.trimmed, artist: artist.trimmed, album: nil, durationMs: song.durationMs)]
            if !artist.trimmed.isEmpty { qs.append(LyricsQuery(title: title.trimmed, artist: "", album: nil, durationMs: song.durationMs)) }
            results = await LyricsStore.shared.searchAll(qs)
            searching = false
        }
    }

    private func open(_ r: ScoredCandidate) {
        loadingID = r.id
        Task {
            let l = await r.candidate.load()
            loadingID = nil
            if let l { preview = l; previewCand = r } else { AppState.shared.show(T("تعذّر تحميل الكلمات", "Couldn't load lyrics")) }
        }
    }
}
