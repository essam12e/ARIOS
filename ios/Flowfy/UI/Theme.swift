import SwiftUI
import UIKit

struct Accent: Identifiable {
    var id: Int; var nameAr: String; var nameEn: String
    var light: Color; var main: Color; var deep: Color; var second: Color
    var name: String { T(nameAr, nameEn) }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: 1)
    }
}

enum Theme {
    static let accents: [Accent] = [
        Accent(id: 6, nameAr: "فلوفاي", nameEn: "Flowfy", light: Color(hex: 0xDDEFFF), main: Color(hex: 0x4DB2FF), deep: Color(hex: 0x2F4FE0), second: Color(hex: 0xFF8A4C)),
        Accent(id: 0, nameAr: "زمرّدي", nameEn: "Emerald", light: Color(hex: 0xB8FFE0), main: Color(hex: 0x2EE6A0), deep: Color(hex: 0x0FA968), second: Color(hex: 0x19C6A0)),
        Accent(id: 1, nameAr: "ملكي بنفسجي", nameEn: "Royal", light: Color(hex: 0xDCD3FF), main: Color(hex: 0x9B7CFF), deep: Color(hex: 0x5B3FD6), second: Color(hex: 0xC77DFF)),
        Accent(id: 2, nameAr: "محيطي", nameEn: "Ocean", light: Color(hex: 0xBFE9FF), main: Color(hex: 0x3DB6FF), deep: Color(hex: 0x1468D6), second: Color(hex: 0x2EE6E6)),
        Accent(id: 3, nameAr: "غروب", nameEn: "Sunset", light: Color(hex: 0xFFE0C2), main: Color(hex: 0xFF8A3D), deep: Color(hex: 0xD9481C), second: Color(hex: 0xFF5C7A)),
        Accent(id: 4, nameAr: "وردي", nameEn: "Rose", light: Color(hex: 0xFFD1E3), main: Color(hex: 0xFF5C9A), deep: Color(hex: 0xC21E62), second: Color(hex: 0xFF8AC7)),
        Accent(id: 5, nameAr: "ذهبي", nameEn: "Gold", light: Color(hex: 0xFFF0C2), main: Color(hex: 0xE8B84A), deep: Color(hex: 0xB07A12), second: Color(hex: 0xFFD36E)),
    ]
    static var accent: Accent { accents.first { $0.id == Prefs.shared.accent } ?? accents[0] }
    static var bg: Color { Prefs.shared.amoled ? .black : Color(hex: 0x05060C) }
    static let surface = Color.white.opacity(0.06)
    static let stroke = Color.white.opacity(0.10)
    static let text = Color.white
    static let sub = Color.white.opacity(0.62)
    static let faint = Color.white.opacity(0.38)
    static let lyricsFont = "IBMPlexSansArabic-SemiBold"

    static func lyrics(_ size: CGFloat) -> Font { .custom(lyricsFont, size: size) }
    static var gradient: LinearGradient { LinearGradient(colors: [accent.main, accent.deep], startPoint: .topLeading, endPoint: .bottomTrailing) }
}

// MARK: glass + motion

struct Glass: ViewModifier {
    var radius: CGFloat = 22
    var glow: Bool = false
    func body(content: Content) -> some View {
        content
            .background(
                ZStack {
                    RoundedRectangle(cornerRadius: radius, style: .continuous).fill(.ultraThinMaterial)
                    RoundedRectangle(cornerRadius: radius, style: .continuous).fill(Color.white.opacity(0.04))
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .stroke(LinearGradient(colors: [Color.white.opacity(0.22), Color.white.opacity(0.04)], startPoint: .top, endPoint: .bottom), lineWidth: 1)
                }
                .environment(\.colorScheme, .dark)
            )
            .shadow(color: glow ? Theme.accent.main.opacity(0.35) : .clear, radius: 18, y: 6)
    }
}

extension View {
    func glass(_ radius: CGFloat = 22, glow: Bool = false) -> some View { modifier(Glass(radius: radius, glow: glow)) }
    func softGlow(_ c: Color, radius: CGFloat = 20) -> some View { shadow(color: c.opacity(0.45), radius: radius) }
}

struct BounceStyle: ButtonStyle {
    var scale: CGFloat = 0.92
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.spring(response: 0.28, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

struct StaggerIn: ViewModifier {
    var index: Int
    @State private var shown: Bool
    init(index: Int) { self.index = index; _shown = State(initialValue: index >= 16) }
    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown ? 0 : 18)
            .onAppear {
                if shown { return }
                withAnimation(.spring(response: 0.55, dampingFraction: 0.82).delay(Double(min(index, 12)) * 0.035)) { shown = true }
            }
    }
}
extension View { func staggerIn(_ i: Int) -> some View { modifier(StaggerIn(index: i)) } }

// MARK: artwork

struct ArtworkView: View {
    var song: Song?
    var size: CGFloat = 52
    var radius: CGFloat = 12
    @State private var image: UIImage?
    @ObservedObject private var art = Artwork.shared

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFill().transition(.opacity)
            } else {
                Placeholder(seed: song?.title ?? "")
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        .task(id: "\(song?.id ?? "")|\(art.revision)") {
            guard let s = song else { image = nil; return }
            let img = await Artwork.shared.image(for: s, size: size)
            withAnimation(.easeOut(duration: 0.25)) { image = img }
        }
    }
}

struct Placeholder: View {
    var seed: String
    var body: some View {
        let h = abs(seed.hashValue % 360)
        ZStack {
            LinearGradient(colors: [Color(hue: Double(h) / 360, saturation: 0.55, brightness: 0.45),
                                    Color(hue: Double((h + 40) % 360) / 360, saturation: 0.65, brightness: 0.22)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            Image(systemName: "music.note").font(.system(size: 20, weight: .semibold)).foregroundStyle(.white.opacity(0.55))
        }
    }
}

/// Blurred cover backdrop behind the player.
struct Backdrop: View {
    var song: Song?
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            Theme.bg
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
                    .blur(radius: 60).saturation(1.4).opacity(0.55)
                    .transition(.opacity)
            }
            LinearGradient(colors: [.black.opacity(0.15), .black.opacity(0.55), .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)
        }
        .ignoresSafeArea()
        .task(id: song?.id) {
            guard let s = song else { return }
            let img = await Artwork.shared.image(for: s, size: 200)
            withAnimation(.easeInOut(duration: 0.8)) { image = img }
        }
    }
}

struct AppBackground: View {
    @ObservedObject var prefs = Prefs.shared
    var body: some View {
        ZStack {
            Theme.bg
            if !prefs.amoled {
                Circle().fill(Theme.accent.deep.opacity(0.35)).frame(width: 420).blur(radius: 120).offset(x: -150, y: -320)
                Circle().fill(Theme.accent.second.opacity(0.14)).frame(width: 360).blur(radius: 130).offset(x: 170, y: 380)
            }
        }
        .ignoresSafeArea()
    }
}

/// Bars driven by the output spectrum.
struct VisualizerBars: View {
    var color: Color
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30)) { _ in
            let b = Spectrum.shared.bands
            Canvas { ctx, size in
                let n = b.count
                let w = size.width / CGFloat(n)
                for i in 0..<n {
                    let h = max(2, CGFloat(b[i]) * size.height)
                    let r = CGRect(x: CGFloat(i) * w + w * 0.2, y: size.height - h, width: w * 0.6, height: h)
                    ctx.fill(Path(roundedRect: r, cornerRadius: w * 0.3), with: .color(color.opacity(0.35 + 0.5 * Double(b[i]))))
                }
            }
        }
        .allowsHitTesting(false)
    }
}

struct IconButton: View {
    var system: String
    var size: CGFloat = 20
    var tint: Color = .white
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: system).font(.system(size: size, weight: .semibold)).foregroundStyle(tint)
                .frame(width: size * 2.1, height: size * 2.1).contentShape(Rectangle())
        }.buttonStyle(BounceStyle())
    }
}

func haptic(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .light) { UIImpactFeedbackGenerator(style: style).impactOccurred() }
