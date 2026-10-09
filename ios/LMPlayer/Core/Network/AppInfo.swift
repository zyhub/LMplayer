import Foundation

enum AppInfo {
    /// 与 Android 版 NetworkClientFactory 完全一致的桌面 UA。
    /// 服务端会按 UA 分流，不能随意改动。
    static let chromeUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
