import SwiftUI

@Observable
class AlchemyLabViewModel {
    private let fileSystem: FlipperFileSystem

    var signalType: SignalType = .subGhz
    var frequency: String = "433920000"
    var protocol_: String = "RAW"
    var signalData: String = ""
    var fileName: String = "custom_signal"
    var isGenerating: Bool = false
    var generatedPreview: String?
    var error: String?

    enum SignalType: String, CaseIterable {
        case subGhz = "Sub-GHz"
        case ir = "IR"
        case nfc = "NFC"
        case rfid = "RFID"
        case iButton = "iButton"
    }

    init(fileSystem: FlipperFileSystem) {
        self.fileSystem = fileSystem
    }

    func generateSignalFile() {
        isGenerating = true
        error = nil

        let content: String
        switch signalType {
        case .subGhz:
            content = """
            Filetype: Flipper SubGhz RAW File
            Version: 1
            Frequency: \(frequency)
            Preset: FuriHalSubGhzPresetOok650Async
            Protocol: \(protocol_)
            RAW_Data: \(signalData.isEmpty ? "100 -100 200 -200 100 -100" : signalData)
            """
        case .ir:
            content = """
            Filetype: IR signals file
            Version: 1
            #
            name: \(fileName)
            type: raw
            frequency: 38000
            duty_cycle: 0.330000
            data: \(signalData.isEmpty ? "2700 900 400 450 400 450 400 1350 400 450 400" : signalData)
            """
        case .nfc:
            content = """
            Filetype: Flipper NFC device
            Version: 4
            Device type: NTAG215
            UID: 04 01 02 03 04 05 06
            ATQA: 44 00
            SAK: 00
            """
        case .rfid:
            content = """
            Filetype: Flipper RFID key
            Version: 1
            Key type: EM4100
            Data: \(signalData.isEmpty ? "01 02 03 04 05" : signalData)
            """
        case .iButton:
            content = """
            Filetype: Flipper iButton key
            Version: 1
            Protocol: Dallas
            Rom Data: \(signalData.isEmpty ? "01 02 03 04 05 06 07 08" : signalData)
            """
        }

        generatedPreview = content
        isGenerating = false
    }

    func saveToFlipper() {
        guard let content = generatedPreview else { return }
        isGenerating = true
        error = nil

        let ext = fileExtension(for: signalType)
        let path = "/ext/\(signalType.rawValue.lowercased())/\(fileName).\(ext)"

        Task {
            do {
                _ = try await fileSystem.writeFile(path, content: content)
            } catch {
                self.error = error.localizedDescription
            }
            isGenerating = false
        }
    }

    private func fileExtension(for type: SignalType) -> String {
        switch type {
        case .subGhz: "sub"
        case .ir: "ir"
        case .nfc: "nfc"
        case .rfid: "rfid"
        case .iButton: "ibtn"
        }
    }
}

struct AlchemyLabView: View {
    @Bindable var viewModel: AlchemyLabViewModel

    var body: some View {
        Form {
            Section("Signal Type") {
                Picker("Type", selection: $viewModel.signalType) {
                    ForEach(AlchemyLabViewModel.SignalType.allCases, id: \.self) { type in
                        Text(type.rawValue).tag(type)
                    }
                }
                .pickerStyle(.segmented)
            }

            Section("Parameters") {
                TextField("File Name", text: $viewModel.fileName)
                    .autocorrectionDisabled()

                if viewModel.signalType == .subGhz {
                    TextField("Frequency (Hz)", text: $viewModel.frequency)
                        .keyboardType(.numberPad)
                    TextField("Protocol", text: $viewModel.protocol_)
                }

                TextField("Signal Data", text: $viewModel.signalData, axis: .vertical)
                    .font(.system(.body, design: .monospaced))
                    .lineLimit(3...8)
            }

            Section {
                Button {
                    viewModel.generateSignalFile()
                } label: {
                    Label("Generate Preview", systemImage: "wand.and.stars")
                }
                .disabled(viewModel.isGenerating)
            }

            if let preview = viewModel.generatedPreview {
                Section("Preview") {
                    Text(preview)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.secondary)

                    Button {
                        viewModel.saveToFlipper()
                    } label: {
                        Label("Save to Flipper", systemImage: "square.and.arrow.down")
                    }
                    .disabled(viewModel.isGenerating)
                }
            }

            if let error = viewModel.error {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("Alchemy Lab")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct SignalEditorView: View {
    @Binding var signalData: String

    var body: some View {
        VStack(spacing: 0) {
            Text("Signal Waveform Editor")
                .font(.headline)
                .padding()

            // Visual waveform representation
            GeometryReader { geometry in
                Canvas { context, size in
                    let values = signalData.split(separator: " ").compactMap { Int($0) }
                    guard !values.isEmpty else { return }

                    let maxAbs = Double(values.map { abs($0) }.max() ?? 1)
                    let midY = size.height / 2
                    let stepWidth = size.width / Double(values.count)

                    var path = Path()
                    path.move(to: CGPoint(x: 0, y: midY))

                    for (index, value) in values.enumerated() {
                        let x = Double(index) * stepWidth
                        let y = midY - (Double(value) / maxAbs) * (midY * 0.8)
                        path.addLine(to: CGPoint(x: x, y: y))
                        path.addLine(to: CGPoint(x: x + stepWidth, y: y))
                    }

                    context.stroke(path, with: .color(.green), lineWidth: 2)

                    // Center line
                    var centerLine = Path()
                    centerLine.move(to: CGPoint(x: 0, y: midY))
                    centerLine.addLine(to: CGPoint(x: size.width, y: midY))
                    context.stroke(centerLine, with: .color(.gray.opacity(0.3)), lineWidth: 1)
                }
            }
            .frame(height: 150)
            .background(Color.black)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .padding()

            TextEditor(text: $signalData)
                .font(.system(.body, design: .monospaced))
                .frame(height: 100)
                .padding(.horizontal)
        }
    }
}
