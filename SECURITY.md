# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest `main` | Yes |
| Older releases | Best effort |

## Reporting a Vulnerability

If you discover a security vulnerability in V3SP3R, **please report it responsibly**.

### Do NOT

- Open a public GitHub issue for security vulnerabilities
- Post details in Discussions or social media before a fix is available

### Do

1. **Email**: Send a detailed report to the repository maintainers via GitHub's [private vulnerability reporting](../../security/advisories/new)
2. **Include**:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if you have one)
3. **Allow time**: Give us reasonable time to investigate and release a fix before public disclosure (90 days is standard)

## Security Architecture

Vesper takes security seriously at every layer:

### Risk Classification

Every AI-initiated action is classified before execution:

| Level | Behavior | Examples |
|-------|----------|---------|
| **Low** | Auto-execute | `list_directory`, `read_file`, `get_device_info` |
| **Medium** | Show diff, require approval | `write_file`, `execute_cli`, `ir_transmit` |
| **High** | Hold-to-confirm with a visible countdown | `delete`, `subghz_transmit`, `badusb_execute` |
| **Blocked** | Requires explicit unlock in Settings | `/int`, `/ext/update`, key material |

### Input Validation

- All LLM responses are validated before execution
- File paths are sanitized to prevent directory traversal
- JSON from AI models passes through repair and strict parsing
- BLE data is bounds-checked before processing

### Data Protection

- The OpenRouter API key is encrypted with an AES-256/GCM key held by the
  Android Keystore and stored as a versioned envelope. A one-time migration
  encrypts keys saved by older versions. If the Keystore is unavailable (for
  example on a locked device with an invalidated key) the app degrades to
  plaintext storage rather than losing the configuration.
- The settings file that holds the key is excluded from cloud backup and device
  transfer.
- Chat history is stored in the app's private Room database. It is protected by
  Android's per-app sandbox and full-disk/file-based encryption, but it is not
  additionally encrypted by Vesper.
- Device- and network-sourced text is delimited as untrusted data before it is
  sent to a model, so file contents cannot silently become instructions.
- The local file server requires a per-session token, caps request size, limits
  concurrent connections, and times out stalled sockets.
- No telemetry or analytics are collected.
- No data is sent to third parties except your selected model provider (through
  OpenRouter) and, if you enable them, the TTS and smart-glasses services.

### What We Consider In-Scope

- Remote code execution
- Authentication/authorization bypass
- Data exfiltration or leakage of API keys
- Prompt injection leading to unauthorized Flipper actions
- BLE protocol vulnerabilities
- Path traversal or sandbox escape on the Flipper filesystem

### What We Consider Out-of-Scope

- Social engineering attacks
- Physical access attacks (someone with your unlocked phone)
- Denial of service against OpenRouter or other third-party APIs
- Vulnerabilities in the Flipper Zero firmware itself (report those to [Flipper Devices](https://flipperzero.one))
- Issues requiring root/jailbroken Android device

## Acknowledgments

We appreciate security researchers who help keep Vesper safe. With your permission, we'll credit you in release notes when a reported vulnerability is fixed.
