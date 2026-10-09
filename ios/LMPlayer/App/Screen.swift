import Foundation

/// 与 Android 版 Screen enum 对齐的四个一级页面。
enum Screen: String, Hashable, CaseIterable, Identifiable {
    case home
    case library
    case downloads
    case settings

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: "发现"
        case .library: "资料库"
        case .downloads: "下载"
        case .settings: "设置"
        }
    }

    var systemImage: String {
        switch self {
        case .home: "sparkles"
        case .library: "square.stack"
        case .downloads: "arrow.down.circle"
        case .settings: "gearshape"
        }
    }
}
