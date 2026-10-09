import SwiftUI

/// 全屏播放器（Phase 0 结构占位）。
/// Phase 4 将补齐：matchedTransitionSource 封面放大过渡、双列歌词、scrollTransition CoverFlow。
struct FullScreenPlayerView: View {
    @Environment(PlayerEngine.self) private var player

    var body: some View {
        VStack(spacing: 24) {
            Spacer(minLength: 24)

            ArtworkImage(url: player.current?.coverURL, size: 300)

            VStack(spacing: 6) {
                Text(player.current?.title ?? "未在播放")
                    .font(LMTypography.pageTitle)
                    .multilineTextAlignment(.center)
                Text(player.current?.artist ?? "LMPlayer")
                    .font(LMTypography.caption)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 28) {
                Button { player.togglePlayPause() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 34, weight: .semibold))
                }
                .buttonStyle(.plain)
                Button { player.next() } label: {
                    Image(systemName: "forward.fill")
                        .font(.system(size: 26, weight: .semibold))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 16)
            .lmGlass(.floating, in: LMShape.pill, interactive: true)

            Spacer(minLength: 24)
        }
        .padding(.horizontal, 24)
        .background(LMPalette.darkBg.ignoresSafeArea())
    }
}
