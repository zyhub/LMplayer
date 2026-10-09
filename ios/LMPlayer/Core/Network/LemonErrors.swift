import Foundation

enum LMError: Error, Sendable {
    case transport
    case auth
    case server(status: Int, body: Data)
    case decoding(String)
    case noSource
}

extension LMError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .transport: "网络传输失败"
        case .auth: "登录鉴权失败"
        case .server(let status, _): "服务端返回错误 (HTTP \(status))"
        case .decoding(let detail): "解析失败：\(detail)"
        case .noSource: "未找到可用的音源"
        }
    }
}
