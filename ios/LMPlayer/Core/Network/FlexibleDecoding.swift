import Foundation

/// 容错解码：字段缺失或类型不符时返回 nil，而不是让整个响应解码失败。
/// 对应 Android 版满屏的 optString / optInt / optDouble。
@propertyWrapper
struct Flexible<T: Decodable & Sendable>: Decodable, Sendable {
    var wrappedValue: T?

    init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        wrappedValue = try? container.decode(T.self)
    }
}
