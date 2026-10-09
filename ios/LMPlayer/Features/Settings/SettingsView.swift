import SwiftUI

struct SettingsView: View {
    @AppStorage("server_url") private var serverURL = "http://192.168.1.2:8080"
    @AppStorage("server_username") private var username = ""
    @AppStorage("server_secret") private var secret = ""
    @AppStorage("wifi_stream_quality") private var wifiQuality = AudioQuality.q320k.rawValue
    @AppStorage("cellular_stream_quality") private var cellularQuality = AudioQuality.q128k.rawValue
    @AppStorage("selected_source") private var selectedSource = "kw"

    private let sources = ["kw", "wy", "tx", "kg", "mg"]

    var body: some View {
        NavigationStack {
            Form {
                Section("服务端") {
                    TextField("服务端地址", text: $serverURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField("用户名", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("密码或 Token", text: $secret)
                }

                Section("在线试听音质") {
                    Picker("Wi-Fi", selection: $wifiQuality) {
                        ForEach(AudioQuality.allCases, id: \.self) { quality in
                            Text(quality.label).tag(quality.rawValue)
                        }
                    }
                    Picker("蜂窝网络", selection: $cellularQuality) {
                        ForEach(AudioQuality.allCases, id: \.self) { quality in
                            Text(quality.label).tag(quality.rawValue)
                        }
                    }
                }

                Section("默认音源") {
                    Picker("在线音源", selection: $selectedSource) {
                        ForEach(sources, id: \.self) { source in
                            Text(source.uppercased()).tag(source)
                        }
                    }
                }

                Section("关于") {
                    LabeledContent("版本", value: "1.0.0 (Phase 0)")
                    LabeledContent("部署目标", value: "iOS 26.0")
                }
            }
            .navigationTitle("设置")
        }
    }
}
