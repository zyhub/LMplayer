import SwiftUI

@main
struct LMPlayerApp: App {
    @State private var player = PlayerEngine.shared

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environment(player)
        }
    }
}
