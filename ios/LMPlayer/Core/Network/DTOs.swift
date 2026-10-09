import Foundation

struct AuthDTO: Decodable, Sendable {
    let token: String?
    let error: String?
}

struct TrackListResponse: Decodable, Sendable {
    let data: [TrackDTO]?
}

/// 对齐 /api/library/tracks 与 /api/download/list 的字段集合。
/// 字段回退链见 docs/ios-native-plan.md 第 1.4(E) 节。
struct TrackDTO: Decodable, Sendable {
    @Flexible var filePath: String?
    @Flexible var fileName: String?
    @Flexible var title: String?
    @Flexible var parsedTitle: String?
    @Flexible var artist: String?
    @Flexible var parsedArtist: String?
    @Flexible var album: String?
    @Flexible var duration: Double?
    @Flexible var interval: String?
    @Flexible var format: String?
    @Flexible var size: Int64?
    @Flexible var mtime: Int64?
    @Flexible var trackId: String?
    @Flexible var id: String?
}

extension TrackDTO {
    var displayTitle: String {
        if let title, !title.isEmpty { return title }
        if let parsedTitle, !parsedTitle.isEmpty { return parsedTitle }
        if let fileName, !fileName.isEmpty {
            return (fileName as NSString).deletingPathExtension
        }
        return "未知曲目"
    }

    var displayArtist: String {
        if let artist, !artist.isEmpty { return artist }
        if let parsedArtist, !parsedArtist.isEmpty { return parsedArtist }
        return "未知歌手"
    }

    var displayAlbum: String {
        if let album, !album.isEmpty { return album }
        return "未知专辑"
    }

    var durationMs: Int64 {
        DurationParser.millis(interval: interval, duration: duration)
    }

    var bitRate: Int {
        let seconds = Double(durationMs) / 1000.0
        if seconds > 0, let size, size > 0 {
            let rate = Int((Double(size) * 8.0) / seconds / 1000.0)
            return min(max(rate, 128), 1411)
        }
        let lower = (format ?? "flac").lowercased()
        return ["flac", "ape", "wav"].contains(lower) ? 960 : 320
    }

    var addedTimestamp: Int64 {
        guard let mtime, mtime > 0 else { return 0 }
        return mtime > 10_000_000_000 ? mtime : mtime * 1000
    }

    var songID: String? {
        guard let filePath, !filePath.isEmpty else { return nil }
        return "lemon_" + filePath.md5Hex
    }
}

enum DurationParser {
    /// 对齐 Android 版 parseDurationMs：先解析 "mm:ss" / "hh:mm:ss"，
    /// 再取 duration，最后回落到 interval 数值；大于 10000 视为毫秒。
    static func millis(interval: String?, duration: Double?) -> Int64 {
        if let interval, interval.contains(":") {
            let parts = interval
                .split(separator: ":")
                .compactMap { Int64($0.trimmingCharacters(in: .whitespaces)) }
            if parts.count == 2 { return (parts[0] * 60 + parts[1]) * 1000 }
            if parts.count == 3 { return (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000 }
        }
        var numeric: Double = 0
        if let duration, duration > 0 {
            numeric = duration
        } else if let interval, let value = Double(interval), value > 0 {
            numeric = value
        }
        guard numeric > 0 else { return 0 }
        return numeric > 10_000 ? Int64(numeric) : Int64(numeric * 1000)
    }
}

struct PlaylistDTO: Decodable, Sendable {
    @Flexible var id: String?
    @Flexible var name: String?
    @Flexible var coverUrl: String?
    @Flexible var trackKeys: [String]?
    @Flexible var paths: [String]?
}

struct FavoriteEntry: Decodable, Sendable {
    @Flexible var key: String?
    @Flexible var localPath: String?
    @Flexible var filePath: String?
    @Flexible var name: String?
    @Flexible var title: String?
    @Flexible var singer: String?
    @Flexible var artist: String?
    @Flexible var album: String?
    @Flexible var source: String?
    @Flexible var songId: String?
    @Flexible var picUrl: String?
    @Flexible var img: String?
}

struct UserDataDTO: Decodable, Sendable {
    let favorites: [FavoriteEntry]?
    let playlists: [PlaylistDTO]?
    let recentPlays: [FavoriteEntry]?
}

/// 服务端可能把 user-data 包在 data 里，也可能直接平铺。
struct UserDataEnvelope: Decodable, Sendable {
    let data: UserDataDTO?
    let favorites: [FavoriteEntry]?
    let playlists: [PlaylistDTO]?
    let recentPlays: [FavoriteEntry]?

    var resolved: UserDataDTO {
        if let data { return data }
        return UserDataDTO(favorites: favorites, playlists: playlists, recentPlays: recentPlays)
    }
}
