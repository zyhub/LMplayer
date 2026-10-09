import SwiftUI

/// 玻璃用途分级：
/// - chrome   : 工具栏 / 底栏 / 常驻浮条
/// - floating : 悬浮按钮 / 胶囊 / 播控条
/// - content  : 内容卡片 —— 明确不使用玻璃，保证文字可读性
enum GlassRole {
    case chrome
    case floating
    case content
}

extension View {
    /// 全项目唯一的玻璃入口。业务代码不得直接调用 glassEffect。
    func lmGlass(_ role: GlassRole = .chrome,
                 in shape: some Shape = .capsule,
                 tint: Color? = nil,
                 interactive: Bool = false) -> some View {
        modifier(LMGlassModifier(role: role, shape: shape, tint: tint, interactive: interactive))
    }
}

private struct LMGlassModifier<S: Shape>: ViewModifier {
    let role: GlassRole
    let shape: S
    let tint: Color?
    let interactive: Bool

    func body(content: Content) -> some View {
        switch role {
        case .content:
            content.background(.background.secondary, in: shape)
        case .chrome, .floating:
            content.glassEffect(makeGlass(), in: shape)
        }
    }

    private func makeGlass() -> Glass {
        var glass: Glass = .regular
        if let tint { glass = glass.tint(tint) }
        if interactive { glass = glass.interactive() }
        return glass
    }
}
