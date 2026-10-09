import SwiftUI

struct DownloadsView: View {
    var body: some View {
        NavigationStack {
            ContentUnavailableView(
                "下载",
                systemImage: "arrow.down.circle",
                description: Text("Phase 5 接入后台下载引擎（URLSession + Range 续传）与标签内嵌")
            )
            .navigationTitle("下载")
        }
    }
}
