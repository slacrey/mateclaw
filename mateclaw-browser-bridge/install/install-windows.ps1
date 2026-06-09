# Installs the MateClaw Chrome Native Messaging host for the current user.
# Run from PowerShell; if scripts are blocked, use: Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
param(
    [Parameter(Mandatory=$true)]
    [string]$BridgePath,

    [Parameter(Mandatory=$true)]
    [string]$ExtensionId
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($BridgePath)) {
    throw "BridgePath is required. Usage: .\install-windows.ps1 -BridgePath C:\path\to\bridge.exe -ExtensionId EXTENSION_ID"
}

if ([string]::IsNullOrWhiteSpace($ExtensionId)) {
    throw "ExtensionId is required. Usage: .\install-windows.ps1 -BridgePath C:\path\to\bridge.exe -ExtensionId EXTENSION_ID"
}

$ManifestName = 'com.mateclaw.browser_bridge.json'
$TemplatePath = Join-Path $PSScriptRoot "manifest\$ManifestName"
$InstallDir = Join-Path $env:LOCALAPPDATA 'MateClaw'
$ManifestPath = Join-Path $InstallDir $ManifestName
$RegistryPath = 'HKCU:\Software\Google\Chrome\NativeMessagingHosts\com.mateclaw.browser_bridge'

if (-not (Test-Path -LiteralPath $TemplatePath)) {
    throw "Manifest template not found: $TemplatePath"
}

New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null

# PowerShell regex replacements treat backslash as literal here; this writes JSON-safe \\ path separators.
$EscapedBridgePath = $BridgePath -replace '\\', '\\'
$Content = Get-Content -LiteralPath $TemplatePath -Raw
$Content = $Content -replace '__BRIDGE_BINARY_PATH__', $EscapedBridgePath
$Content = $Content -replace '__EXTENSION_ID__', $ExtensionId
Set-Content -LiteralPath $ManifestPath -Value $Content -Encoding UTF8

New-Item -Path $RegistryPath -Force | Out-Null
Set-Item -Path $RegistryPath -Value $ManifestPath

Write-Host "MateClaw Chrome Native Messaging host installed for current user."
Write-Host "Manifest:     $ManifestPath"
Write-Host "Bridge binary: $BridgePath"
Write-Host "Extension ID:  $ExtensionId"
Write-Host "Restart Chrome for the Native Messaging host registration to take effect."
