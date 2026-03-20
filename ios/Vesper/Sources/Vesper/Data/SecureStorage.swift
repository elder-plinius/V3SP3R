// SecureStorage.swift
// Vesper - AI-powered Flipper Zero controller
// Keychain wrapper for secure API key storage

import Foundation
import Security

/// Errors thrown by SecureStorage operations.
enum SecureStorageError: LocalizedError {
    case encodingFailed
    case saveFailed(OSStatus)
    case deleteFailed(OSStatus)
    case unexpectedData

    var errorDescription: String? {
        switch self {
        case .encodingFailed:
            return "Failed to encode the API key as UTF-8 data."
        case .saveFailed(let status):
            return "Keychain save failed with status \(status)."
        case .deleteFailed(let status):
            return "Keychain delete failed with status \(status)."
        case .unexpectedData:
            return "Unexpected data format returned from Keychain."
        }
    }
}

/// Thread-safe Keychain wrapper for storing the OpenRouter API key.
/// Uses the Security framework directly; never stores secrets in UserDefaults.
final class SecureStorage: Sendable {

    private static let service = "com.vesper.flipper"
    private static let account = "openrouter_api_key"

    // MARK: - Public API

    /// Saves an API key to the Keychain, replacing any existing value.
    func saveAPIKey(_ key: String) throws {
        guard let data = key.data(using: .utf8) else {
            throw SecureStorageError.encodingFailed
        }

        // Delete any existing item first so SecItemAdd doesn't return errSecDuplicateItem.
        try? deleteAPIKey()

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: Self.account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]

        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw SecureStorageError.saveFailed(status)
        }
    }

    /// Loads the API key from the Keychain. Returns `nil` when no key is stored.
    func loadAPIKey() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: Self.account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess, let data = result as? Data else {
            return nil
        }

        return String(data: data, encoding: .utf8)
    }

    /// Deletes the stored API key from the Keychain.
    func deleteAPIKey() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: Self.account
        ]

        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw SecureStorageError.deleteFailed(status)
        }
    }
}
