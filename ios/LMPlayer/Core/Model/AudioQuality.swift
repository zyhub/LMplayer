import Foundation

/// 对齐 Android 版 AudioQuality。key 必须与服务端一致。
enum AudioQuality: String, CaseIterable, Hashable, Sendable {
    case q128k = "128k"
    case q320k = "320k"
    case flac  = "flac"
    case hires = "flac24bit"

    /// 逐档降级顺序：flac24bit -> flac -> 320k -> 128k
    static let ladder: [AudioQuality] = [.hires, .flac, .q320k, .q128k]

    var label: String {
        switch self {
        case .q128k: "标准音质"
        case .q320k: "极高音质"
        case .flac:  "无损音质"
        case .hires: "Hi-Res 高解析母带"
        }
    }

    var containerFormat: String {
        switch self {
        case .q128k, .q320k: "MP3"
        case .flac, .hires:  "FLAC"
        }
    }

    var bitrate: Int {
        switch self {
        case .q128k: 128
        case .q320k: 320
        case .flac:  960
        case .hires: 1411
        }
    }

    static func from(key: String) -> AudioQuality {
        AudioQuality(rawValue: key.lowercased()) ?? .q320k
    }

    /// 与 Android 版 getFallbackQualities 一致。
    static func fallbacks(from preferred: AudioQuality) -> [AudioQuality] {
        guard let index = ladder.firstIndex(of: preferred) else {
            return [preferred, .q320k, .q128k]
        }
        return Array(ladder[index...])
    }
}
