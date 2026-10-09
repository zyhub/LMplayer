import SwiftUI

struct LibraryView: View {
    var body: some View {
        NavigationStack {
            ContentUnavailableView(
                "资料库",
                systemImage: "square.stack",
                description: Text("Phase 2 接入 SwiftData 曲库；Phase 4 完成歌单/歌手/专辑/歌曲分类")
            )
            .navigationTitle("资料库")
        }
    }
}
