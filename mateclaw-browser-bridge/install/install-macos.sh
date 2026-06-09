#!/usr/bin/env bash
# Installs the MateClaw Chrome Native Messaging host manifest on macOS.
# Make executable if needed: chmod +x install-macos.sh
set -euo pipefail

BRIDGE_PATH="${1:?usage: install-macos.sh /absolute/path/to/bridge EXTENSION_ID}"
EXTENSION_ID="${2:?usage: install-macos.sh /absolute/path/to/bridge EXTENSION_ID}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_PATH="${SCRIPT_DIR}/manifest/com.mateclaw.browser_bridge.json"
TARGET_DIR="${HOME}/Library/Application Support/Google/Chrome/NativeMessagingHosts"
MANIFEST_PATH="${TARGET_DIR}/com.mateclaw.browser_bridge.json"

if [[ ! -f "${TEMPLATE_PATH}" && -f "${SCRIPT_DIR}/com.mateclaw.browser_bridge.json" ]]; then
  TEMPLATE_PATH="${SCRIPT_DIR}/com.mateclaw.browser_bridge.json"
fi

if [[ ! -f "${TEMPLATE_PATH}" ]]; then
  echo "Manifest template not found: ${TEMPLATE_PATH}" >&2
  exit 1
fi

mkdir -p "${TARGET_DIR}"

sed -e "s|__BRIDGE_BINARY_PATH__|${BRIDGE_PATH}|g" -e "s|__EXTENSION_ID__|${EXTENSION_ID}|g" "${TEMPLATE_PATH}" > "${MANIFEST_PATH}"

echo "Manifest installed: ${MANIFEST_PATH}"
echo "Restart Chrome for the Native Messaging host registration to take effect."
