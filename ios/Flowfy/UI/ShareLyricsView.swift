import SwiftUI

struct ShareLyricsView: View {
    var song: Song
    @Environment(\.dismiss) private var dismiss
    @State private var lyrics: Lyrics?
    @State private var selected: Set<Int> = []
    @State private var style = 0
    @State private var cover: UIImage?
    @State private var rendered: Image?
    @State private var renderedUI: UIImage?

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                card.frame(width: 300, height: 420).clipShape(RoundedRectangle(cornerRadius: 28)).shadow(color: Theme.accent.main.opacity(0.4), radius: 24)
                Picker("", selection: $style) {
                    Text(T("زجاجي", "Glass")).tag(0); Text(T("متدرّج", "Gradient")).tag(1); Text(T("بسيط", "Minimal")).tag(2)
                }.pickerStyle(.segmented).padding(.horizontal)
                Text(T("اختر حتى ٥ أسطر", "Pick up to 5 lines")).font(.caption).foregroundStyle(Theme.sub)
                List {
                    ForEach(Array((lyrics?.lines ?? []).enumerated()), id: \.offset) { i, l in
                        if !l.text.trimmed.isEmpty {
                            HStack {
                                Image(systemName: selected.contains(i) ? "checkmark.circle.fill" : "circle").foregroundStyle(Theme.accent.main)
                                Text(l.text).font(Theme.lyrics(15))
                            }
                            .contentShape(Rectangle())
                            .onTapGesture {
                                if selected.contains(i) { selected.remove(i) } else if selected.count < 5 { selected.insert(i) }
                            }
                            .listRowBackground(Color.white.opacity(0.04))
                        }
                    }
                }.scrollContentBackground(.hidden)
            }
            .background(AppBackground())
            .navigationTitle(T("مشاركة الكلمات", "Share lyrics")).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(T("إغلاق", "Close")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if let img = renderedUI {
                        ShareLink(item: Image(uiImage: img), preview: SharePreview(song.title, image: Image(uiImage: img))) { Image(systemName: "square.and.arrow.up") }
                    }
                }
            }
        }
        .preferredColorScheme(.dark)
        .task {
            lyrics = await LyricsStore.shared.getLocal(song)
            if let l = lyrics {
                let idx = l.synced ? max(0, l.index(at: Player.shared.positionMs())) : 0
                selected = Set((idx..<min(l.lines.count, idx + 3)).filter { !l.lines[$0].text.trimmed.isEmpty })
            }
            cover = await Artwork.shared.image(for: song, size: 400)
            render()
        }
        .onChange(of: selected) { _ in render() }
        .onChange(of: style) { _ in render() }
    }

    private var lines: [String] { selected.sorted().compactMap { lyrics?.lines[$0].text } }

    private var card: some View {
        ShareCard(song: song, lines: lines, style: style, cover: cover)
    }

    @MainActor private func render() {
        let r = ImageRenderer(content: ShareCard(song: song, lines: lines, style: style, cover: cover).frame(width: 1080 / 3, height: 1500 / 3))
        r.scale = 3
        renderedUI = r.uiImage
    }
}

struct ShareCard: View {
    var song: Song
    var lines: [String]
    var style: Int
    var cover: UIImage?

    var body: some View {
        ZStack {
            switch style {
            case 1: LinearGradient(colors: [Theme.accent.deep, Theme.accent.main, Theme.accent.second], startPoint: .topLeading, endPoint: .bottomTrailing)
            case 2: Color(hex: 0x0B0D16)
            default:
                ZStack {
                    Color(hex: 0x05060C)
                    if let cover { Image(uiImage: cover).resizable().scaledToFill().blur(radius: 30).opacity(0.7) }
                    Color.black.opacity(0.25)
                }
            }
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 10) {
                    if let cover { Image(uiImage: cover).resizable().scaledToFill().frame(width: 46, height: 46).clipShape(RoundedRectangle(cornerRadius: 10)) }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(song.title).font(.system(size: 15, weight: .bold)).lineLimit(1)
                        Text(song.artist).font(.system(size: 12)).opacity(0.75).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                VStack(alignment: Lyrics.isRTL(lines.joined()) ? .trailing : .leading, spacing: 8) {
                    ForEach(lines, id: \.self) { l in
                        Text(l).font(Theme.lyrics(22)).multilineTextAlignment(Lyrics.isRTL(l) ? .trailing : .leading)
                            .frame(maxWidth: .infinity, alignment: Lyrics.isRTL(l) ? .trailing : .leading)
                    }
                }
                .padding(16)
                .background(RoundedRectangle(cornerRadius: 20).fill(.white.opacity(style == 2 ? 0.05 : 0.14)))
                Spacer(minLength: 0)
                HStack(spacing: 6) {
                    Image("Logo").resizable().scaledToFit().frame(width: 20)
                    Text("Flowfy").font(.system(size: 13, weight: .heavy, design: .rounded))
                }.opacity(0.9)
            }
            .foregroundStyle(.white)
            .environment(\.layoutDirection, .leftToRight)
            .padding(22)
        }
    }
}
