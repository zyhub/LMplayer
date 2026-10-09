import SwiftUI

/// 底栏迷你播放器：由 TabView 的 bottom accessory 承载。
/// 严格遵循玻璃纪律 —— lmGlass 是修饰符链的最后一环。
struct MiniPlayerBar: View {
    @Environment(PlayerEngine.self) private var player

    var body: some View {
        HStack(spacing: 12) {
            ArtworkImage(url: player.current?.coverURL, size: 36)

            VStack(alignment: .leading, spacing: 1) {
                Text(player.current?.title ?? "未在播放")
                    .font(LMTypography.itemTitle)
                    .lineLimit(1)
                Text(player.current?.artist ?? "LMPlayer")
                    .font(LMTypography.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            Button {
                player.togglePlayPause()
            } label: {
                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 20, weight: .semibold))
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)

            Button {
                player.next()
            } label: {
                Image(systemName: "forward.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .lmGlass(.floating, in: LMShape.card, interactive: true)
    }
}
