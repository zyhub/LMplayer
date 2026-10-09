import Foundation

/// 柠檬音乐服务端客户端。
/// 连接参数、缓存策略、UA 与鉴权流程完全对齐 Android 版 NetworkClientFactory + LemonMusicProtocol。
actor LemonMusicClient {
    static let shared = LemonMusicClient()

    private var baseURL: URL
    private var token: String?
    private let session: URLSession

    private init() {
        baseURL = URL(string: "http://127.0.0.1:8080")!
        let configuration = URLSessionConfiguration.default
        configuration.httpMaximumConnectionsPerHost = 32
        configuration.timeoutIntervalForRequest = 12
        configuration.timeoutIntervalForResource = 60
        configuration.urlCache = URLCache(memoryCapacity: 16 << 20, diskCapacity: 64 << 20)
        configuration.requestCachePolicy = .useProtocolCachePolicy
        session = URLSession(configuration: configuration)
    }

    func configure(baseURL: URL, token: String? = nil) {
        self.baseURL = baseURL
        if let token, !token.isEmpty { self.token = token }
    }

    func currentToken() -> String? { token }

    func resolvedBaseURL() -> URL { baseURL }

    // MARK: 传输

    func send(_ endpoint: Endpoint) async throws -> Data {
        var components = URLComponents(url: baseURL.appending(path: endpoint.path),
                                       resolvingAgainstBaseURL: false)
        if !endpoint.query.isEmpty {
            components?.queryItems = endpoint.query
                .sorted { $0.key < $1.key }
                .map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = components?.url else { throw LMError.transport }

        var request = URLRequest(url: url)
        request.httpMethod = endpoint.method.rawValue
        request.httpBody = endpoint.body
        request.setValue(AppInfo.chromeUserAgent, forHTTPHeaderField: "User-Agent")
        if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if endpoint.bypassCache {
            request.cachePolicy = .reloadIgnoringLocalCacheData
        }

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw LMError.transport }
        guard (200..<300).contains(http.statusCode) else {
            throw LMError.server(status: http.statusCode, body: data)
        }
        return data
    }

    func send<T: Decodable>(_ endpoint: Endpoint, as type: T.Type) async throws -> T {
        let data = try await send(endpoint)
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw LMError.decoding(String(describing: error))
        }
    }

    // MARK: 鉴权

    /// 先按 token 直用（长度 >= 32 或 lemon- 前缀），否则走 /api/auth/login。
    func authenticate(username: String, secret: String) async throws {
        if let token, !token.isEmpty { return }
        if secret.hasPrefix("lemon-") || secret.count >= 32 {
            token = secret
            return
        }
        let data = try await send(.login(username: username, password: secret))
        let dto = try JSONDecoder().decode(AuthDTO.self, from: data)
        guard let value = dto.token, !value.isEmpty else { throw LMError.auth }
        token = value
    }

    // MARK: 曲库

    func fetchAllTracks() async throws -> [UnifiedSong] {
        let response = try await send(.allTracks, as: TrackListResponse.self)
        return (response.data ?? []).compactMap { $0.toUnifiedSong() }
    }

    func fetchUserData() async throws -> UserDataDTO {
        let envelope = try await send(.userData, as: UserDataEnvelope.self)
        return envelope.resolved
    }
}

extension TrackDTO {
    /// 对齐 Android 版 getSongList 的映射与 ID 规则。
    func toUnifiedSong(serverId: String = "lemon_music", baseURL: URL? = nil) -> UnifiedSong? {
        guard let songID, let filePath, !filePath.isEmpty else { return nil }
        return UnifiedSong(
            id: songID,
            title: displayTitle,
            artist: displayArtist,
            artistId: "lemon_artist_" + displayArtist.md5Hex,
            album: displayAlbum,
            albumId: "lemon_album_" + (displayArtist + "/" + displayAlbum).md5Hex,
            durationMs: durationMs,
            coverURL: nil,
            streamURL: "",
            serverId: serverId,
            bitRate: bitRate,
            format: (format ?? "flac").lowercased(),
            addedTimestamp: addedTimestamp
        )
    }
}
