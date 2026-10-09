import SwiftUI

/// 7 级语义字号，数值与 Android 版 AppDimensions 一致（26 / 18 / 16 / 15 / 13.5 / 12 / 10.5）。
/// 使用 Dynamic Type 的相对字号，随系统字体缩放自动适配。
enum LMTypography {
    static let pageTitle    = Font.system(size: 26,   weight: .bold)
    static let sectionTitle = Font.system(size: 18,   weight: .bold)
    static let cardHeader   = Font.system(size: 16,   weight: .semibold)
    static let itemTitle    = Font.system(size: 15,   weight: .medium)
    static let body         = Font.system(size: 13.5, weight: .medium)
    static let caption      = Font.system(size: 12,   weight: .regular)
    static let badge        = Font.system(size: 10.5, weight: .bold)
}
