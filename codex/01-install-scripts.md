# Codex Task 01 — Chrome Native Messaging Install Scripts

> 你（Codex）正在为 MateClaw Browser Agent 项目交付 D1 任务：Chrome Native
> Messaging 主机的安装清单模板 + 三个 OS 的 install 脚本。你的产出会被项目
> 维护者粘贴到仓库后立即使用。**不需要修改任何已有代码** —— 这是一个纯新文件
> 创建任务。

## 项目背景（让你理解契约，不需要其他文件）

MateClaw 的 Browser Agent 是一套三进程系统：
- **Control Plane**（Java Spring Boot，本机或远程）暴露
  `ws://HOST:18088/api/v1/browser/edge` 给 Native Host 长连。
- **Native Host**（`mateclaw-browser-bridge`，TypeScript 编译成 `dist/cmd/bridge.js`，
  或者通过 `bun build --compile` 编译成 `bridge.exe`）跑在用户机器，通过 Chrome
  Native Messaging stdio 协议与浏览器扩展通信。
- **Chrome Extension** 通过 `chrome.runtime.connectNative("com.mateclaw.browser_bridge")`
  连上 Native Host。

Chrome 要找到 Native Host，需要一个 **manifest 文件** 放在 Chrome 找得到的路径里。
manifest 内含 `name`（必须等于扩展 `connectNative` 传的字符串）、`path`（可执行
绝对路径）、`type: "stdio"`、`allowed_origins`（数组，含
`chrome-extension://<EXTENSION_ID>/`）。

不同 OS 的 manifest 寻找路径：
- **Windows**：注册表项
  `HKCU\Software\Google\Chrome\NativeMessagingHosts\com.mateclaw.browser_bridge`
  的默认值 = manifest 文件绝对路径。manifest 自身可放任意位置（约定 `%LOCALAPPDATA%\MateClaw\`）。
- **macOS**：文件直接放在
  `$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts/com.mateclaw.browser_bridge.json`。
- **Linux**：文件直接放在
  `$HOME/.config/google-chrome/NativeMessagingHosts/com.mateclaw.browser_bridge.json`
  （Chromium 用 `$HOME/.config/chromium/...`）。

## 你要交付的 4 个文件

### 1. `mateclaw-browser-bridge/install/manifest/com.mateclaw.browser_bridge.json`

一个 **模板** manifest，内含两个占位符 `__BRIDGE_BINARY_PATH__` 和
`__EXTENSION_ID__`，由后面的 install 脚本替换成真实值。结构：

```json
{
  "name": "com.mateclaw.browser_bridge",
  "description": "MateClaw Browser Agent Native Host",
  "path": "__BRIDGE_BINARY_PATH__",
  "type": "stdio",
  "allowed_origins": [
    "chrome-extension://__EXTENSION_ID__/"
  ]
}
```

### 2. `mateclaw-browser-bridge/install/install-windows.ps1`

PowerShell 脚本。约束：
- 必填参数 `-BridgePath` 和 `-ExtensionId`，类型 `[Parameter(Mandatory=$true)][string]`
- `$ErrorActionPreference = 'Stop'`
- 把 manifest 模板拷贝到 `$env:LOCALAPPDATA\MateClaw\`，替换占位符（用 `-replace` + 双反斜杠转义路径）
- 写注册表：`HKCU:\Software\Google\Chrome\NativeMessagingHosts\com.mateclaw.browser_bridge` 的默认值
- 友好输出（manifest 路径、binary 路径、extension id）
- **重要**：bridge 路径里的反斜杠在 JSON 里要转义为 `\\`。PowerShell 的 `-replace` 用正则，要写 `\\\\`（两个 `\\` 经过 PS 字符串转义 + 正则转义）
- 不需要管理员权限（HKCU + LOCALAPPDATA 是用户级）

### 3. `mateclaw-browser-bridge/install/install-macos.sh`

Bash 脚本。约束：
- shebang `#!/usr/bin/env bash`
- `set -euo pipefail`
- 位置参数 1 = `$BRIDGE_PATH`，参数 2 = `$EXTENSION_ID`；缺失时 `${1:?usage: ...}`
- 目标目录 `$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts`
- `mkdir -p`
- `sed -e "s|__BRIDGE_BINARY_PATH__|${BRIDGE_PATH}|g" -e "s|__EXTENSION_ID__|${EXTENSION_ID}|g"` 替换占位符
- 输出最终 manifest 路径
- 文件结尾留一行 echo 提醒用户：刚生效要重启 Chrome

### 4. `mateclaw-browser-bridge/install/install-linux.sh`

Bash 脚本。**和 macOS 几乎一致**，差别只在目标目录：`$HOME/.config/google-chrome/NativeMessagingHosts`。

## 测试方法（让 Codex 自己跑过再交）

把 4 个文件写出来后，**在 Linux 或 macOS 环境（Codex 大概率是 Linux）**做一次干跑：

```bash
mkdir -p /tmp/mateclaw-test/install
cp mateclaw-browser-bridge/install/manifest/com.mateclaw.browser_bridge.json /tmp/mateclaw-test/install/
cp mateclaw-browser-bridge/install/install-linux.sh /tmp/mateclaw-test/install/
chmod +x /tmp/mateclaw-test/install/install-linux.sh
HOME=/tmp/mateclaw-fakehome bash /tmp/mateclaw-test/install/install-linux.sh /usr/local/bin/bridge AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
# 然后 cat 产出的 manifest，肉眼检查 path/allowed_origins 替换正确
cat /tmp/mateclaw-fakehome/.config/google-chrome/NativeMessagingHosts/com.mateclaw.browser_bridge.json
```

PowerShell 脚本你做不到端到端测试，但至少要**手动跑 PowerShell 语法检查**：
```bash
pwsh -NoProfile -Command "Invoke-Expression \"& { \$null = Get-Command Get-Content; 'syntax ok' }\""
```
或者用 `pwsh -File install-windows.ps1 -BridgePath C:\test -ExtensionId AAA` 在
Linux pwsh 上空跑（注册表 cmdlet 会报错，但能验证语法）。

## 验收

- [ ] 4 个文件齐全
- [ ] JSON manifest 模板内含 `name` `description` `path` `type: "stdio"` `allowed_origins`
- [ ] 三个 install 脚本都校验入参非空，否则报清晰错误
- [ ] Linux 干跑后，产出的 manifest 中两个占位符都被替换，没有残留 `__...__` 字面量
- [ ] Windows PS1 不需要管理员权限（HKCU 而非 HKLM）
- [ ] 所有脚本末尾或开头有简短注释说明用途和 chmod / Set-ExecutionPolicy 提示

## 交付格式

请按以下格式输出，让维护者直接复制粘贴：

```
========== FILE: mateclaw-browser-bridge/install/manifest/com.mateclaw.browser_bridge.json ==========
<内容>
========== FILE: mateclaw-browser-bridge/install/install-windows.ps1 ==========
<内容>
========== FILE: mateclaw-browser-bridge/install/install-macos.sh ==========
<内容>
========== FILE: mateclaw-browser-bridge/install/install-linux.sh ==========
<内容>
========== TEST RUN ==========
<你在沙盒里 Linux 干跑的输出 / 产出的 manifest cat 结果>
========== COMMIT MSG ==========
feat(browser-bridge): add Chrome Native Messaging install manifest + per-OS scripts

Drops com.mateclaw.browser_bridge.json template into Chrome's
NativeMessagingHosts directory (HKCU registry on Windows, file path on
macOS/Linux). Bridge binary path and extension ID are template
placeholders replaced by per-OS install scripts.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

不要在 commit msg 之外添加任何"我已完成"之类的总结文本。维护者会直接拿这个产出粘贴落地。
