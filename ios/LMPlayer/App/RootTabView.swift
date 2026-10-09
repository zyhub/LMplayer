import SwiftUI

/// 根导航：底栏与迷你播放器全部交给系统 Liquid Glass 接管。
/// 对应 Android 版约 850 行的自研 morphing 底栏。
struct RootTabView: View {
    @State private var selection: Screen = .home

    var body: some View {
        TabView(selection: $selection) {
            Tab(Screen.home.title, systemImage: Screen.home.systemImage, value: Screen.home) {
                DiscoverView()
            }
            Tab(Screen.library.title, systemImage: Screen.library.systemImage, value: Screen.library) {
                LibraryView()
            }
            Tab(Screen.downloads.title, systemImage: Screen.downloads.systemImage, value: Screen.downloads) {
                DownloadsView()
            }
            Tab(Screen.settings.title, systemImage: Screen.settings.systemImage, value: Screen.settings) {
                SettingsView()
            }
        }
        .tabViewBottomAccessory {
            MiniPlayerBar()
        }
        .tabBarMinimizeBehavior(.onScrollDown)
    }
}

#Preview {
    RootTabView()
        .environment(PlayerEngine.shared)
}
