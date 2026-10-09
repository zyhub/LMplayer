import SwiftUI

/// 圆角系统：16 / 12 / 16 / 20 / 28（与 Android 版 AppShapes 一致）。
enum LMShape {
    static let card   = RoundedRectangle(cornerRadius: 16, style: .continuous)
    static let medium = RoundedRectangle(cornerRadius: 12, style: .continuous)
    static let large  = RoundedRectangle(cornerRadius: 20, style: .continuous)
    static let xlarge = RoundedRectangle(cornerRadius: 28, style: .continuous)
    static let pill   = Capsule(style: .continuous)
}
