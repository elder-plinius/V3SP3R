import SwiftUI

struct PayloadLabView: View {
    @Bindable var viewModel: PayloadLabViewModel

    var body: some View {
        Form {
            typeSection
            promptSection
            generateSection

            if let payload = viewModel.generatedPayload {
                previewSection(payload)
            }

            if let validation = viewModel.validation {
                validationSection(validation)
            }

            if let error = viewModel.error {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("Payload Lab")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var typeSection: some View {
        Section("Payload Type") {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 90))], spacing: 8) {
                ForEach(PayloadLabViewModel.payloadTypes, id: \.0) { type in
                    Button {
                        viewModel.payloadType = type.0
                    } label: {
                        VStack(spacing: 4) {
                            Image(systemName: type.2)
                                .font(.title3)
                            Text(type.1)
                                .font(.caption2)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(viewModel.payloadType == type.0 ? Color.accentColor.opacity(0.2) : Color(.systemGray6))
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(viewModel.payloadType == type.0 ? Color.accentColor : .clear, lineWidth: 2)
                        )
                    }
                    .tint(.primary)
                }
            }
        }
    }

    private var promptSection: some View {
        Section("Describe What You Need") {
            TextField("e.g., Samsung TV power toggle, garage door 315MHz OOK...", text: $viewModel.prompt, axis: .vertical)
                .lineLimit(3...6)
        }
    }

    private var generateSection: some View {
        Section {
            Button {
                viewModel.generate()
            } label: {
                HStack {
                    if viewModel.isGenerating {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        Image(systemName: "wand.and.stars")
                    }
                    Text(viewModel.isGenerating ? "Generating..." : "Generate Payload")
                }
                .frame(maxWidth: .infinity)
            }
            .disabled(viewModel.isGenerating || viewModel.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    private func previewSection(_ payload: GeneratedPayload) -> some View {
        Section("Generated Payload") {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Label(payload.filename, systemImage: "doc")
                        .font(.caption.monospaced())
                    Spacer()
                    Text(payload.type.uppercased())
                        .font(.caption2.bold())
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.purple.opacity(0.15))
                        .foregroundStyle(.purple)
                        .clipShape(Capsule())
                }

                Text(payload.description)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                ScrollView {
                    Text(payload.content)
                        .font(.system(.caption2, design: .monospaced))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 200)
                .padding(8)
                .background(Color(.systemGray6))
                .clipShape(RoundedRectangle(cornerRadius: 8))

                Button {
                    viewModel.saveToFlipper()
                } label: {
                    HStack {
                        if viewModel.isSaving {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Image(systemName: "square.and.arrow.down")
                        }
                        Text(viewModel.isSaving ? "Saving..." : "Save to Flipper")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(viewModel.isSaving)
            }
        }
    }

    private func validationSection(_ validation: PayloadValidation) -> some View {
        Section("Validation") {
            HStack {
                Image(systemName: validation.isValid ? "checkmark.circle.fill" : "xmark.circle.fill")
                    .foregroundStyle(validation.isValid ? .green : .red)
                Text(validation.isValid ? "Payload is valid" : "Validation errors found")
            }

            ForEach(validation.errors, id: \.self) { error in
                Label(error, systemImage: "xmark")
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            ForEach(validation.warnings, id: \.self) { warning in
                Label(warning, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
    }
}
