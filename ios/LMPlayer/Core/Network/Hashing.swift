import CryptoKit
import Foundation

extension String {
    /// 与 Android 版 LemonMusicProtocol.md5 一致，用于生成 songId。
    var md5Hex: String {
        Insecure.MD5.hash(data: Data(utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }
}
