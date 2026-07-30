# OC Remote 1.8.2

This patch updates Pi Stack Control compatibility and connection diagnostics.

## Pi Stack Compatibility

- Remove the client dependency on the retired `ensemble` capability.
- Continue accepting additional server capabilities so older and newer Control deployments remain compatible during upgrades.

## Connection Diagnostics

- Report authentication failures as token errors instead of the generic server-unavailable message.
- Show protocol mismatch details when the server response cannot satisfy the Control v1 contract.

## Verification

- Passed the full debug unit test suite.
- Built the debug APK successfully.
