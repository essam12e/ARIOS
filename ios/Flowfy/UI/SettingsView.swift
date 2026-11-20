import SwiftUI
import MediaPlayer

struct SettingsView: View {
    @ObservedObject private var prefs = Prefs.shared
    @ObservedObject private var lib = LibraryStore.shared
    @State private var confirmClear = false

    private func bind(_ get: @escaping () -> Bool, _ set: @escaping (Bool) -> Void) -> Binding<Bool> { Binding(get: get, set: set) }

    var body: some View {
        Form {
            Section(T("المظهر", "Look")) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(Theme.accents) { a in
                            Button { withAnimation { prefs.accent = a.id } } label: {
                                VStack(spacing: 6) {
                                    Circle().fill(LinearGradient(colors: [a.light, a.main, a.deep], startPoint: .topLeading, endPoint: .bottomTrailing))
                                        .frame(width: 44, height: 44)
                                        .overlay(Circle().stroke(.white, lineWidth: prefs.accent == a.id ? 3 : 0))
                                        .shadow(color: a.main.opacity(0.6), radius: prefs.accent == a.id ? 10 : 0)
                                    Text(a.name).font(.system(size: 11, weight: .semibold)).foregroundStyle(.white)
                                }
                            }.buttonStyle(BounceStyle())
                        }
                    }.padding(.vertical, 6)
                }
                Toggle(T("أسود كامل (AMOLED)", "Pure black (AMOLED)"), isOn: bind({ prefs.amoled }, { prefs.amoled = $0 }))
                Toggle(T("المؤثر الصوتي المرئي", "Visualizer"), isOn: bind({ prefs.visualizer }, { prefs.visualizer = $0 }))
            }
            Section(T("الكلمات", "Lyrics")) {
                Toggle(T("جلب الكلمات تلقائياً", "Fetch lyrics automatically"), isOn: bind({ prefs.autoFetchLyrics }, { prefs.autoFetchLyrics = $0 }))
                Toggle(T("مزامنة تلقائية للكلمات بدون توقيت", "Auto-time plain lyrics"), isOn: bind({ prefs.autoSyncLyrics }, { prefs.autoSyncLyrics = $0 }))
                Toggle(T("تظليل كلمة بكلمة", "Word-by-word highlight"), isOn: bind({ prefs.wordByWord }, { prefs.wordByWord = $0 }))
                Toggle(T("إظهار الترجمة", "Show translation"), isOn: bind({ prefs.showTranslation }, { prefs.showTranslation = $0 }))
                Toggle(T("توسيط الكلمات", "Center lyrics"), isOn: bind({ prefs.lyricsCenter }, { prefs.lyricsCenter = $0 }))
                Stepper(T("حجم الخط: \(prefs.lyricsFontSize)", "Font size: \(prefs.lyricsFontSize)"), value: Binding(get: { prefs.lyricsFontSize }, set: { prefs.lyricsFontSize = $0 }), in: 16...44, step: 2)
                ForEach(allLyricsProviders, id: \.id) { p in
                    Toggle(p.displayName, isOn: bind({ prefs.sourceEnabled(p.id) }, { prefs.setSourceEnabled(p.id, $0) }))
                }
            }
            Section(T("التشغيل", "Playback")) {
                Stepper(prefs.crossfadeSec == 0 ? T("الانتقال المتداخل: متوقف", "Crossfade: off") : T("الانتقال المتداخل: \(prefs.crossfadeSec) ث", "Crossfade: \(prefs.crossfadeSec)s"),
                        value: Binding(get: { prefs.crossfadeSec }, set: { prefs.crossfadeSec = $0 }), in: 0...12)
                Toggle(T("توحيد مستوى الصوت", "Normalize volume"), isOn: bind({ prefs.normalize }, { prefs.normalize = $0 }))
                Toggle(T("استئناف المقاطع الطويلة", "Resume long audio"), isOn: bind({ prefs.resumeLong }, { prefs.resumeLong = $0 }))
                Toggle(T("خفض الصوت تدريجياً قبل النوم", "Fade out before sleep"), isOn: bind({ prefs.sleepFade }, { prefs.sleepFade = $0 }))
                NavigationLink(value: Route.sound) { Label(T("المعادل والصوت", "Equalizer & sound"), systemImage: "slider.vertical.3") }
            }
            Section(T("المكتبة", "Library")) {
                if lib.mediaAuth == .authorized {
                    Toggle(T("أغاني مكتبة الآيفون", "iPhone music library"), isOn: bind({ prefs.useMusicLibrary }, { prefs.useMusicLibrary = $0; Task { await lib.scan() } }))
                } else {
                    Button(T("السماح بالوصول لمكتبة الآيفون", "Allow iPhone music library")) { lib.requestMusicAccess() }
                }
                Toggle(T("جلب صور الألبومات تلقائياً", "Fetch artwork automatically"), isOn: bind({ prefs.autoFetchArtwork }, { prefs.autoFetchArtwork = $0 }))
                NavigationLink(value: Route.folders) { Label(T("المجلدات المستبعدة", "Excluded folders"), systemImage: "eye.slash") }
                NavigationLink(value: Route.duplicates) { Label(T("الأغاني المكررة", "Duplicates"), systemImage: "square.on.square") }
                NavigationLink(value: Route.backup) { Label(T("النسخ الاحتياطي", "Backup"), systemImage: "externaldrive") }
                Button(T("مسح ذاكرة الكلمات والصور", "Clear lyrics & artwork cache"), role: .destructive) { confirmClear = true }
            }
            Section { AboutCard() }
        }
        .scrollContentBackground(.hidden)
        .background(AppBackground())
        .navigationTitle(T("الإعدادات", "Settings"))
        .confirmationDialog(T("مسح الذاكرة؟", "Clear cache?"), isPresented: $confirmClear, titleVisibility: .visible) {
            Button(T("مسح", "Clear"), role: .destructive) { LyricsStore.shared.clearCache(); Artwork.shared.clearCache() }
        } message: { Text(T("الكلمات المعدلة يدوياً بتنحذف أيضاً.", "Manually edited lyrics will be deleted too.")) }
    }
}

struct AboutCard: View {
    var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0" }
    var body: some View {
        VStack(spacing: 10) {
            Image("Logo").resizable().scaledToFit().frame(width: 84).shadow(color: Theme.accent.main.opacity(0.6), radius: 20)
            Text("Flowfy").font(.system(size: 24, weight: .heavy, design: .rounded))
            Text(T("الإصدار \(version)", "Version \(version)")).font(.footnote).foregroundStyle(Theme.sub)
            Text(T("تطوير بابلو", "Developed by Bablo")).font(.system(size: 16, weight: .bold)).padding(.top, 4)
            Button {
                if let u = URL(string: "https://wa.me/966531098278") { UIApplication.shared.open(u) }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "phone.bubble.left.fill")
                    Text("+966 53 109 8278").environment(\.layoutDirection, .leftToRight)
                }
                .font(.system(size: 15, weight: .bold)).foregroundStyle(.white)
                .padding(.horizontal, 18).padding(.vertical, 10)
                .background(Capsule().fill(Color(hex: 0x25D366)))
                .shadow(color: Color(hex: 0x25D366).opacity(0.5), radius: 10)
            }.buttonStyle(BounceStyle())
        }
        .frame(maxWidth: .infinity).padding(.vertical, 10)
        .listRowBackground(Color.clear)
    }
}

struct ExcludedFoldersView: View {
    @ObservedObject private var prefs = Prefs.shared
    @ObservedObject private var lib = LibraryStore.shared
    var body: some View {
        List {
            Section(footer: Text(T("المجلدات المخفية ما تظهر أغانيها في المكتبة.", "Songs in hidden folders don't show in the library."))) {
                ForEach(lib.allFolders, id: \.self) { f in
                    Toggle(isOn: Binding(get: { !prefs.excludedFolders.contains(f) }, set: { on in
                        var s = prefs.excludedFolders
                        if on { s.remove(f) } else { s.insert(f) }
                        prefs.excludedFolders = s; lib.rebuild()
                    })) {
                        VStack(alignment: .leading) {
                            Text((f as NSString).lastPathComponent)
                            Text(f).font(.caption).foregroundStyle(Theme.sub)
                        }
                    }
                }
            }
        }
        .scrollContentBackground(.hidden).background(AppBackground())
        .navigationTitle(T("المجلدات", "Folders"))
    }
}

struct SoundView: View {
    @ObservedObject private var prefs = Prefs.shared
    @ObservedObject private var player = Player.shared

    static let presets: [(String, String, [Float])] = [
        ("flat", T("مسطّح", "Flat"), [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
        ("bass", T("باس", "Bass"), [6, 5, 4, 2, 0, 0, 0, 0, 0, 0]),
        ("vocal", T("صوت", "Vocal"), [-2, -1, 0, 2, 4, 4, 3, 1, 0, -1]),
        ("tarab", T("طرب", "Tarab"), [2, 2, 1, 0, 2, 3, 3, 2, 1, 0]),
        ("rock", T("روك", "Rock"), [4, 3, 1, -1, -2, 0, 2, 3, 4, 4]),
        ("pop", T("بوب", "Pop"), [-1, 1, 3, 4, 3, 0, -1, -1, 1, 2]),
        ("electronic", T("إلكتروني", "Electronic"), [5, 4, 1, 0, -2, 1, 0, 2, 4, 5]),
        ("classical", T("كلاسيكي", "Classical"), [3, 2, 1, 0, 0, 0, -1, 1, 2, 3]),
        ("treble", T("حدّة", "Treble"), [0, 0, 0, 0, 0, 1, 3, 5, 6, 6]),
        ("night", T("ليلي", "Night"), [-3, -2, -1, 0, 1, 2, 2, 1, -1, -3]),
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                VStack(alignment: .leading, spacing: 14) {
                    Toggle(T("المعادل", "Equalizer"), isOn: Binding(get: { prefs.eqEnabled }, set: { prefs.eqEnabled = $0; player.applyEQ() }))
                        .font(.system(size: 17, weight: .bold)).tint(Theme.accent.main)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(Self.presets, id: \.0) { p in
                                Button {
                                    prefs.eqPreset = p.0; prefs.eqGains = p.2; prefs.eqEnabled = true; player.applyEQ(); haptic()
                                } label: {
                                    Text(p.1).font(.system(size: 13, weight: .bold)).foregroundStyle(prefs.eqPreset == p.0 ? .black : .white)
                                        .padding(.horizontal, 14).padding(.vertical, 8)
                                        .background(Capsule().fill(prefs.eqPreset == p.0 ? AnyShapeStyle(Theme.gradient) : AnyShapeStyle(Color.white.opacity(0.08))))
                                }.buttonStyle(BounceStyle())
                            }
                        }
                    }
                    EQCurve(gains: prefs.eqGains).frame(height: 70).opacity(prefs.eqEnabled ? 1 : 0.4)
                    HStack(alignment: .bottom, spacing: 0) {
                        ForEach(0..<10, id: \.self) { i in
                            VStack(spacing: 6) {
                                Text(String(format: "%+.0f", prefs.eqGains[i])).font(.system(size: 10, weight: .semibold).monospacedDigit()).foregroundStyle(Theme.sub)
                                VerticalSlider(value: Binding(get: { Double(prefs.eqGains[i]) }, set: { v in
                                    var g = prefs.eqGains; g[i] = Float(v); prefs.eqGains = g; prefs.eqPreset = "custom"; prefs.eqEnabled = true; player.applyEQ()
                                }), range: -12...12).frame(height: 170)
                                Text(freqLabel(Player.eqFreqs[i])).font(.system(size: 9.5, weight: .semibold)).foregroundStyle(Theme.sub)
                            }.frame(maxWidth: .infinity)
                        }
                    }
                    .environment(\.layoutDirection, .leftToRight)
                }
                .padding(16).glass(24, glow: true)

                VStack(alignment: .leading, spacing: 10) {
                    Text(T("تعزيز الباس", "Bass boost")).font(.system(size: 15, weight: .bold))
                    Slider(value: Binding(get: { prefs.bassBoost }, set: { prefs.bassBoost = $0; player.applyEQ() }), in: 0...10).tint(Theme.accent.main)
                    Text(T("السرعة: ", "Speed: ") + String(format: "%.2fx", player.speed)).font(.system(size: 15, weight: .bold))
                    Slider(value: Binding(get: { player.speed }, set: { player.setSpeed(($0 * 20).rounded() / 20) }), in: 0.5...2).tint(Theme.accent.main)
                    Text(T("طبقة الصوت: ", "Pitch: ") + String(format: "%+.0f", player.pitch / 100)).font(.system(size: 15, weight: .bold))
                    Slider(value: Binding(get: { player.pitch }, set: { player.setPitch(($0 / 100).rounded() * 100) }), in: -600...600).tint(Theme.accent.main)
                    Button(T("إعادة الضبط", "Reset")) {
                        prefs.eqGains = Array(repeating: 0, count: 10); prefs.eqPreset = "flat"; prefs.bassBoost = 0
                        player.setSpeed(1); player.setPitch(0); player.applyEQ()
                    }.foregroundStyle(Theme.accent.light)
                }
                .padding(16).glass(24)
            }
            .padding(16)
        }
        .background(AppBackground())
        .navigationTitle(T("الصوت", "Sound"))
    }

    private func freqLabel(_ f: Float) -> String { f >= 1000 ? "\(Int(f / 1000))k" : "\(Int(f))" }
}

struct VerticalSlider: View {
    @Binding var value: Double
    var range: ClosedRange<Double>
    var body: some View {
        GeometryReader { g in
            let f = (value - range.lowerBound) / (range.upperBound - range.lowerBound)
            ZStack(alignment: .bottom) {
                Capsule().fill(Color.white.opacity(0.1)).frame(width: 5)
                Capsule().fill(LinearGradient(colors: [Theme.accent.deep, Theme.accent.main], startPoint: .bottom, endPoint: .top))
                    .frame(width: 5, height: g.size.height * f)
                Circle().fill(Theme.accent.light).frame(width: 18, height: 18).shadow(color: Theme.accent.main, radius: 6)
                    .offset(y: -g.size.height * f + 9)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { v in
                let p = 1 - min(1, max(0, v.location.y / g.size.height))
                value = ((range.lowerBound + p * (range.upperBound - range.lowerBound)) * 2).rounded() / 2
            })
        }
    }
}

struct EQCurve: View {
    var gains: [Float]
    var body: some View {
        Canvas { ctx, size in
            var p = Path()
            let n = gains.count
            for i in 0..<n {
                let x = size.width * CGFloat(i) / CGFloat(n - 1)
                let y = size.height / 2 - CGFloat(gains[i]) / 12 * size.height / 2.2
                if i == 0 { p.move(to: CGPoint(x: x, y: y)) } else {
                    let px = size.width * CGFloat(i - 1) / CGFloat(n - 1)
                    let py = size.height / 2 - CGFloat(gains[i - 1]) / 12 * size.height / 2.2
                    p.addCurve(to: CGPoint(x: x, y: y), control1: CGPoint(x: (px + x) / 2, y: py), control2: CGPoint(x: (px + x) / 2, y: y))
                }
            }
            ctx.stroke(p, with: .linearGradient(Gradient(colors: [Theme.accent.main, Theme.accent.second]), startPoint: .zero, endPoint: CGPoint(x: size.width, y: 0)), lineWidth: 3)
        }
        .environment(\.layoutDirection, .leftToRight)
    }
}
