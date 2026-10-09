import AVFoundation
import Foundation
import Observation

/// 播放内核（Phase 0 骨架）。
/// Phase 3 将补齐：PlaybackRouter 解析、队列/乱序/循环、Now Playing、Live Activity。
@MainActor
@Observable
final class PlayerEngine {
    static let shared = PlayerEngine()

    private let player = AVQueuePlayer()

    private(set) var current: UnifiedSong?
    private(set) var isPlaying = false
    private(set) var queue: [UnifiedSong] = []

    private init() {}

    func play(_ song: UnifiedSong, in songs: [UnifiedSong] = []) {
        current = song
        queue = songs.isEmpty ? [song] : songs
        guard let url = URL(string: song.streamURL), !song.streamURL.isEmpty else {
            isPlaying = false
            return
        }
        player.removeAllItems()
        player.insert(AVPlayerItem(url: url), after: nil)
        player.play()
        isPlaying = true
    }

    func togglePlayPause() {
        if player.rate > 0 {
            player.pause()
            isPlaying = false
        } else {
            player.play()
            isPlaying = true
        }
    }

    func next() {
        player.advanceToNextItem()
        isPlaying = player.rate > 0
    }
}
