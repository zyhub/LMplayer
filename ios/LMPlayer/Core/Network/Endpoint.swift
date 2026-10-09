import Foundation

enum HTTPMethod: String, Sendable {
    case get = "GET"
    case post = "POST"
    case put = "PUT"
    case delete = "DELETE"
}

/// 对齐 Android 版 LemonMusicProtocol.kt 的 43 个服务端端点契约。
/// 契约即代码：新增接口必须在此声明，业务层不得拼接 URL 字符串。
struct Endpoint: Sendable {
    var method: HTTPMethod = .get
    var path: String
    var query: [String: String] = [:]
    var body: Data? = nil
    /// 音频流与封面接口必须绕过 URL 缓存，避免与 206 Range 冲突。
    var bypassCache: Bool = false
}

private func jsonBody(_ object: [String: Any]) -> Data? {
    try? JSONSerialization.data(withJSONObject: object)
}

// MARK: - 鉴权

extension Endpoint {
    static func login(username: String, password: String) -> Endpoint {
        Endpoint(method: .post,
                 path: "/api/auth/login",
                 body: jsonBody(["username": username, "password": password, "remember": true]))
    }

    static var me: Endpoint { Endpoint(path: "/api/auth/me") }
}

// MARK: - 曲库

extension Endpoint {
    static var allTracks: Endpoint { Endpoint(path: "/api/library/tracks", query: ["all": "1"]) }
    static var tracksCount: Endpoint { Endpoint(path: "/api/library/tracks/count") }

    static func tracksByPaths(_ paths: [String]) -> Endpoint {
        Endpoint(method: .post, path: "/api/library/tracks/by-paths", body: jsonBody(["paths": paths]))
    }

    static func albums(page: Int = 1, limit: Int = 500) -> Endpoint {
        Endpoint(path: "/api/library/albums", query: ["page": "\(page)", "limit": "\(limit)"])
    }

    static func artists(page: Int = 1, limit: Int = 500) -> Endpoint {
        Endpoint(path: "/api/library/artists", query: ["page": "\(page)", "limit": "\(limit)"])
    }

    static func genres(page: Int = 1, limit: Int = 100) -> Endpoint {
        Endpoint(path: "/api/library/genres", query: ["page": "\(page)", "limit": "\(limit)"])
    }

    static var playlists: Endpoint { Endpoint(path: "/api/library/playlists") }

    static func savePlaylists(_ payload: Data) -> Endpoint {
        Endpoint(method: .put, path: "/api/library/playlists", body: payload)
    }

    static var userData: Endpoint { Endpoint(path: "/api/library/user-data") }

    static func saveUserData(_ payload: Data) -> Endpoint {
        Endpoint(method: .put, path: "/api/library/user-data", body: payload)
    }

    static var scanStart: Endpoint {
        Endpoint(method: .post, path: "/api/library/scan-start", body: Data("{}".utf8))
    }

    static var scanStatus: Endpoint { Endpoint(path: "/api/library/scan-status") }

    static func scanBatch(_ paths: [String]) -> Endpoint {
        Endpoint(method: .post, path: "/api/library/scan-batch", body: jsonBody(["paths": paths]))
    }
}

// MARK: - 播放 / 标签（绕过缓存）

extension Endpoint {
    /// 对齐 Android 版 getStreamUrlForPath：.ape 走服务端 FFmpeg 转码端点。
    static func streamLocal(path: String, token: String, quality: String? = nil) -> Endpoint {
        let isApe = path.lowercased().hasSuffix(".ape")
        var query = ["path": path, "token": token]
        if let quality { query["quality"] = quality }
        return Endpoint(path: isApe ? "/api/play/local-ape" : "/api/play/local",
                        query: query,
                        bypassCache: true)
    }

    static func cover(path: String, token: String) -> Endpoint {
        Endpoint(path: "/api/tag/cover", query: ["path": path, "token": token], bypassCache: true)
    }

    static func playURL(_ payload: Data) -> Endpoint {
        Endpoint(method: .post, path: "/api/play/url", body: payload)
    }

    static func lyric(_ payload: Data) -> Endpoint {
        Endpoint(method: .post, path: "/api/play/lyric", body: payload)
    }

    static func tagRead(path: String) -> Endpoint {
        Endpoint(method: .post, path: "/api/tag/read", body: jsonBody(["filePath": path, "path": path]))
    }
}

// MARK: - 发现

extension Endpoint {
    static func recommend(source: String, page: Int = 1, limit: Int = 30) -> Endpoint {
        Endpoint(path: "/api/playlist/recommend",
                 query: ["source": source, "sort": "hot", "page": "\(page)", "limit": "\(limit)"])
    }

    static func playlist(source: String, url: String) -> Endpoint {
        Endpoint(path: "/api/playlist", query: ["source": source, "url": url])
    }

    static var playlistSources: Endpoint { Endpoint(path: "/api/playlist/sources") }

    static func toplists(source: String) -> Endpoint {
        Endpoint(path: "/api/discover/toplists", query: ["source": source])
    }

    static func toplist(source: String, id: String, limit: Int = 100) -> Endpoint {
        Endpoint(path: "/api/discover/toplist", query: ["source": source, "id": id, "limit": "\(limit)"])
    }

    static func newSongs(source: String, region: String = "", limit: Int = 30) -> Endpoint {
        Endpoint(path: "/api/discover/new-songs",
                 query: ["source": source, "region": region, "limit": "\(limit)"])
    }

    static func newAlbums(source: String, region: String = "", page: Int = 1, limit: Int = 20) -> Endpoint {
        Endpoint(path: "/api/discover/new-albums",
                 query: ["source": source, "region": region, "page": "\(page)", "limit": "\(limit)"])
    }
}

// MARK: - 搜索

extension Endpoint {
    static func search(keyword: String, source: String, page: Int = 1, limit: Int = 30) -> Endpoint {
        Endpoint(path: "/api/search",
                 query: ["keyword": keyword, "source": source, "page": "\(page)", "limit": "\(limit)"])
    }

    static func searchAlbums(keyword: String, source: String, page: Int = 1, limit: Int = 30) -> Endpoint {
        Endpoint(path: "/api/search/album",
                 query: ["keyword": keyword, "source": source, "page": "\(page)", "limit": "\(limit)"])
    }

    static func searchAlbumsFallback(keyword: String, source: String, page: Int = 1, limit: Int = 30) -> Endpoint {
        Endpoint(path: "/api/album/search",
                 query: ["keyword": keyword, "source": source, "page": "\(page)", "limit": "\(limit)"])
    }

    static func searchPlaylists(keyword: String, source: String, page: Int = 1, limit: Int = 30) -> Endpoint {
        Endpoint(path: "/api/search/playlist",
                 query: ["keyword": keyword, "source": source, "page": "\(page)", "limit": "\(limit)"])
    }

    static func albumDetail(source: String, id: String) -> Endpoint {
        Endpoint(path: "/api/album", query: ["source": source, "id": id])
    }

    static func albumDetailFallback(source: String, id: String) -> Endpoint {
        Endpoint(path: "/api/search/album/detail", query: ["source": source, "id": id])
    }
}

// MARK: - 下载

extension Endpoint {
    static var downloadList: Endpoint { Endpoint(path: "/api/download/list") }

    static func downloadAdd(_ payload: Data) -> Endpoint {
        Endpoint(method: .post, path: "/api/download/add", body: payload)
    }
}

// MARK: - 音源脚本

extension Endpoint {
    static var sourceList: Endpoint { Endpoint(path: "/api/source/list") }

    static func sourceImportURL(_ url: String) -> Endpoint {
        Endpoint(method: .post, path: "/api/source/import-url", body: jsonBody(["url": url]))
    }

    static func sourceImport(content: String, fileName: String) -> Endpoint {
        Endpoint(method: .post, path: "/api/source/import",
                 body: jsonBody(["content": content, "fileName": fileName]))
    }

    static func sourceActivate(_ id: String) -> Endpoint {
        Endpoint(method: .post, path: "/api/source/activate/\(id)")
    }

    static func sourceDeactivate(_ id: String) -> Endpoint {
        Endpoint(method: .post, path: "/api/source/deactivate/\(id)")
    }

    static func sourceDelete(_ id: String) -> Endpoint {
        Endpoint(method: .delete, path: "/api/source/\(id)")
    }
}

// MARK: - 配置

extension Endpoint {
    static var paths: Endpoint { Endpoint(path: "/api/paths") }

    static func updateDownloadPath(_ dir: String) -> Endpoint {
        Endpoint(method: .put, path: "/api/paths/download", body: jsonBody(["dirPath": dir, "path": dir]))
    }

    static var settings: Endpoint { Endpoint(path: "/api/settings") }

    static func updateSettings(_ entries: [String: String]) -> Endpoint {
        Endpoint(method: .put, path: "/api/settings", body: jsonBody(entries))
    }
}
