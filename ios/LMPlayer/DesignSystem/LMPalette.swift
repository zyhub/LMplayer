import SwiftUI

/// 迁移自 Android 版 Theme.kt 的调色板（hex 完全一致）。
enum LMPalette {
    static let appleRed     = Color(hex: 0xFA2D48)
    static let appleRedDark = Color(hex: 0xD81E37)
    static let lightBg      = Color(hex: 0xF2F2F7)
    static let darkBg       = Color(hex: 0x101014)
    static let cardLight    = Color(hex: 0xFFFFFF)
    static let cardDark     = Color(hex: 0x1C1C20)
    static let pill         = Color(hex: 0xE5E5EA)
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: opacity)
    }
}
