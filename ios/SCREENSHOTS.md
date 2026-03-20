# V3SP3R iOS — Screen Inventory

## Tab Bar (5 tabs)
1. **Chat** — Main AI conversation interface
2. **Device** — BLE scan, connect, device info
3. **Ops** — Operations center with runbooks
4. **Tools** — Tool menu (sub-screens below)
5. **Settings** — API key, model, approvals, glasses

## Chat Screen
- `ChatView.swift` — Message list + input bar
- `MessageBubble.swift` — User/assistant message bubbles with tool call display
- `InputBar.swift` — Text field + voice + camera buttons

## Device Screen
- `DeviceView.swift` — Connection status, device info, storage info, scan results
- `FileBrowserView.swift` — Hierarchical file browser with breadcrumbs, file preview, delete

## Ops Center
- `OpsCenterView.swift` — System status, runbook launcher, recent actions feed

## Tools (sub-screens)
- `AlchemyLabView.swift` — Signal type picker, parameters, waveform editor, file preview + save
- `PayloadLabView.swift` — AI payload generation with type grid, prompt, preview, validation
- `FapHubView.swift` — App search, category filter, app list with install buttons
- `ResourceBrowserView.swift` — Known repo list, GitHub search, file tree browser, download
- `AuditLogView.swift` — Filterable audit log with action type and risk level filters

## Settings
- `SettingsView.swift` — API key (Keychain), model picker, approval tier toggles, glasses config

## Components
- `ApprovalDialog.swift` — Risk-aware approval dialog with single/double tap confirmation
- `DiffViewer.swift` — Unified diff display with syntax coloring

## Widget
- `VesperWidget.swift` — WidgetKit small/medium widget showing connection status + battery
