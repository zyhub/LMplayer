import Foundation

/// 对齐 Android 版 core/model/Models.kt 的 UnifiedSong。
struct UnifiedSong: Identifiable, Hashable, Sendable {
    let id: String
    var title: String
    var artist: String
    var artistId: String = ""
    var album: String = ""
    var albumId: String = ""
    var durationMs: Int64 = 0
    var coverURL: URL?
    var streamURL: String = ""
    var serverId: String = "default"
    var localFilePath: String?
    var bitRate: Int = 320
    var format: String = "flac"
    var isFavorite: Bool = false
    var relativeFolderPath: String?
    var addedTimestamp: Int64 = 0
    var rawMetaJSON: String?
}

struct UnifiedAlbum: Identifiable, Hashable, Sendable {
    let id: String
    var title: String
    var artist: String
    var coverURL: URL?
    var songCount: Int = 0
    var year: Int?
}

struct UnifiedArtist: Identifiable, Hashable, Sendable {
    let id: String
    var name: String
    var avatarURL: URL?
    var songCount: Int = 0
}

struct UnifiedPlaylist: Identifiable, Hashable, Sendable {
    let id: String
    var name: String
    var coverURL: URL?
    var songCount: Int = 0
    var isOnline: Bool = false
    var serverId: String = "local_storage"
    var previewCovers: [URL] = []
    var isDiscover: Bool = false
}

struct LemonToplist: Identifiable, Hashable, Sendable {
    let id: String
    var name: String
    var coverURL: URL?
    var updateFrequency: String = ""
    var source: String = "kw"
}
