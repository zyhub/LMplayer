import SwiftUI

enum LMMotion {
    static let quick   = Animation.smooth(duration: 0.18)
    static let regular = Animation.smooth(duration: 0.28)
    static let springy = Animation.spring(response: 0.38, dampingFraction: 0.78)
}
