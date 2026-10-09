import SwiftUI

/// 封面视图。Phase 0 先用 AsyncImage 占位，
/// Phase 4 替换为 Nuke（磁盘缓存 + 主色提取）。
struct ArtworkImage: View {
    let url: URL?
    var size: CGFloat = 300

    var body: some View {
        AsyncImage(url: url) { phase in
            switch phase {
            case .success(let image):
                image.resizable().scaledToFill()
            case .failure:
                placeholder
            case .empty:
                placeholder
            @unknown default:
                placeholder
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.08, style: .continuous))
    }

    private var placeholder: some View {
        ZStack {
            Rectangle().fill(.quaternary)
            Image(systemName: "music.note")
                .font(.system(size: size * 0.28, weight: .medium))
                .foregroundStyle(.secondary)
        }
    }
}
