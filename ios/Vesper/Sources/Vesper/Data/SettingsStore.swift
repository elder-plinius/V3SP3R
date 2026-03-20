// SettingsStore.swift
// Vesper - AI-powered Flipper Zero controller
// UserDefaults-backed observable settings store

import Foundation
import Observation

/// Observable settings store backed by UserDefaults.
/// Properties are read/written synchronously and publish changes through the Observation framework.
@Observable
final class SettingsStore: SettingsStoreProtocol, @unchecked Sendable {

    // MARK: - UserDefaults Keys

    private enum Keys {
        static let selectedModel = "vesper_selected_model"
        static let autoApproveMedium = "vesper_auto_approve_medium"
        static let autoApproveHigh = "vesper_auto_approve_high"
        static let glassesEnabled = "vesper_glasses_enabled"
        static let glassesBridgeUrl = "vesper_glasses_bridge_url"
        static let protectedPathsUnlocked = "vesper_protected_paths_unlocked"
    }

    // MARK: - Defaults

    static let defaultModel = "anthropic/claude-sonnet-4-20250514"

    // MARK: - Storage

    private let defaults: UserDefaults

    // MARK: - Init

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults

        // Load initial values from UserDefaults
        _selectedModel = defaults.string(forKey: Keys.selectedModel) ?? Self.defaultModel
        _autoApproveMedium = defaults.bool(forKey: Keys.autoApproveMedium)
        _autoApproveHigh = defaults.bool(forKey: Keys.autoApproveHigh)
        _glassesEnabled = defaults.bool(forKey: Keys.glassesEnabled)
        _glassesBridgeUrl = defaults.string(forKey: Keys.glassesBridgeUrl) ?? ""

        if let saved = defaults.stringArray(forKey: Keys.protectedPathsUnlocked) {
            _protectedPathsUnlocked = Set(saved)
        } else {
            _protectedPathsUnlocked = []
        }
    }

    // MARK: - Properties

    var selectedModel: String {
        get { _selectedModel }
        set {
            _selectedModel = newValue
            defaults.set(newValue, forKey: Keys.selectedModel)
        }
    }

    var autoApproveMedium: Bool {
        get { _autoApproveMedium }
        set {
            _autoApproveMedium = newValue
            defaults.set(newValue, forKey: Keys.autoApproveMedium)
        }
    }

    var autoApproveHigh: Bool {
        get { _autoApproveHigh }
        set {
            _autoApproveHigh = newValue
            defaults.set(newValue, forKey: Keys.autoApproveHigh)
        }
    }

    var glassesEnabled: Bool {
        get { _glassesEnabled }
        set {
            _glassesEnabled = newValue
            defaults.set(newValue, forKey: Keys.glassesEnabled)
        }
    }

    var glassesBridgeUrl: String {
        get { _glassesBridgeUrl }
        set {
            _glassesBridgeUrl = newValue
            defaults.set(newValue, forKey: Keys.glassesBridgeUrl)
        }
    }

    var protectedPathsUnlocked: Set<String> {
        get { _protectedPathsUnlocked }
        set {
            _protectedPathsUnlocked = newValue
            defaults.set(Array(newValue), forKey: Keys.protectedPathsUnlocked)
        }
    }

    // MARK: - Backing Storage (tracked by @Observable)

    private var _selectedModel: String
    private var _autoApproveMedium: Bool
    private var _autoApproveHigh: Bool
    private var _glassesEnabled: Bool
    private var _glassesBridgeUrl: String
    private var _protectedPathsUnlocked: Set<String>

    // MARK: - Protected Path Helpers

    /// Returns whether a specific protected path has been unlocked by the user.
    func isProtectedPathUnlocked(_ path: String) -> Bool {
        protectedPathsUnlocked.contains(path)
    }

    /// Unlocks a protected path, allowing operations on it.
    func unlockProtectedPath(_ path: String) {
        var paths = protectedPathsUnlocked
        paths.insert(path)
        protectedPathsUnlocked = paths
    }

    /// Re-locks a previously unlocked protected path.
    func lockProtectedPath(_ path: String) {
        var paths = protectedPathsUnlocked
        paths.remove(path)
        protectedPathsUnlocked = paths
    }

    /// Returns whether a path is within the user's permitted scope.
    /// By default, all /ext/ paths are in scope.
    func isPathInScope(_ path: String) -> Bool {
        path.hasPrefix("/ext/") || path == "/ext"
    }
}
