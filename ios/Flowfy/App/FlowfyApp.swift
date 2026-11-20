import SwiftUI
import UIKit

@main
struct FlowfyApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(\.layoutDirection, isArabicUI ? .rightToLeft : .leftToRight)
                .environment(\.locale, Locale(identifier: isArabicUI ? "ar" : "en"))
                .preferredColorScheme(.dark)
                .onOpenURL { url in Task { await AppState.shared.openExternal(url) } }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UIApplication.shared.beginReceivingRemoteControlEvents()
        return true
    }
}

enum Route: Hashable {
    case album(String), artist(String), folder(String), playlist(String), smart(String)
    case settings, sound, stats, wrapped, downloads, duplicates, search, folders, backup
}

/// Cross-screen UI state: player overlay, sheets, toasts, navigation.
@MainActor
final class AppState: ObservableObject {
    static let shared = AppState()
    @Published var showPlayer = false
    @Published var showFullLyrics = false
    @Published var path = NavigationPath()
    @Published var toast: String?
    @Published var addToPlaylist: [Song]?
    @Published var tagEdit: Song?
    @Published var lyricsSearch: Song?
    @Published var lyricsEdit: Song?
    @Published var shareLyrics: Song?
    @Published var showQueue = false
    @Published var carMode = false
    private var toastTask: Task<Void, Never>?

    func show(_ msg: String) {
        toastTask?.cancel()
        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) { toast = msg }
        toastTask = Task {
            try? await Task.sleep(nanoseconds: 2_200_000_000)
            if !Task.isCancelled { withAnimation(.easeOut(duration: 0.3)) { toast = nil } }
        }
    }

    func go(_ r: Route) { path.append(r) }

    func openExternal(_ url: URL) async {
        guard url.isFileURL else { return }
        if LibraryStore.audioExt.contains(url.pathExtension.lowercased()) {
            let n = await LibraryStore.shared.importFiles([url])
            show(T("تم استيراد \(n) ملف", "Imported \(n) file(s)"))
        } else if url.pathExtension.lowercased() == "json" || url.pathExtension.lowercased() == "flowfy" {
            let ok = await Backup.restore(from: url)
            show(ok ? T("تمت الاستعادة", "Restored") : T("ملف غير صالح", "Invalid file"))
        }
    }
}
