import SwiftUI

struct DiscoverView: View {
    var body: some View {
        NavigationStack {
            ContentUnavailableView(
                "发现",
                systemImage: "sparkles",
                description: Text("Phase 1 接入服务端推荐/榜单/新歌/新碟，Phase 4 完成液态光影卡片")
            )
            .navigationTitle("发现")
        }
    }
}
