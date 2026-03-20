import SwiftUI
#if canImport(WidgetKit)
import WidgetKit

struct VesperWidgetEntry: TimelineEntry {
    let date: Date
    let deviceName: String
    let isConnected: Bool
    let batteryLevel: Int?
    let lastAction: String?
}

struct VesperWidgetProvider: TimelineProvider {
    func placeholder(in context: Context) -> VesperWidgetEntry {
        VesperWidgetEntry(
            date: Date(),
            deviceName: "Flipper",
            isConnected: false,
            batteryLevel: nil,
            lastAction: nil
        )
    }

    func getSnapshot(in context: Context, completion: @escaping (VesperWidgetEntry) -> Void) {
        let entry = VesperWidgetEntry(
            date: Date(),
            deviceName: "Flipper Zero",
            isConnected: false,
            batteryLevel: 85,
            lastAction: "Ready"
        )
        completion(entry)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<VesperWidgetEntry>) -> Void) {
        let entry = VesperWidgetEntry(
            date: Date(),
            deviceName: UserDefaults.standard.string(forKey: "widget_device_name") ?? "Flipper",
            isConnected: UserDefaults.standard.bool(forKey: "widget_is_connected"),
            batteryLevel: UserDefaults.standard.object(forKey: "widget_battery") as? Int,
            lastAction: UserDefaults.standard.string(forKey: "widget_last_action")
        )
        let nextUpdate = Calendar.current.date(byAdding: .minute, value: 15, to: Date())!
        let timeline = Timeline(entries: [entry], policy: .after(nextUpdate))
        completion(timeline)
    }
}

struct VesperWidgetView: View {
    let entry: VesperWidgetEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: "cpu")
                    .foregroundStyle(.purple)
                Text("Vesper")
                    .font(.headline)
                    .foregroundStyle(.purple)
            }

            Spacer()

            HStack(spacing: 4) {
                Circle()
                    .fill(entry.isConnected ? Color.green : Color.red)
                    .frame(width: 8, height: 8)
                Text(entry.isConnected ? entry.deviceName : "Disconnected")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let battery = entry.batteryLevel {
                HStack(spacing: 4) {
                    Image(systemName: "battery.75")
                        .font(.caption2)
                    Text("\(battery)%")
                        .font(.caption.monospacedDigit())
                }
                .foregroundStyle(battery > 20 ? .green : .red)
            }

            if let action = entry.lastAction {
                Text(action)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
            }
        }
        .padding()
    }
}

struct VesperWidget: Widget {
    let kind: String = "VesperWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: VesperWidgetProvider()) { entry in
            VesperWidgetView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Vesper Status")
        .description("Quick view of Flipper Zero connection status.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
#endif
