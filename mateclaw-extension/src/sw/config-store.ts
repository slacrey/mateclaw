// Typed wrapper over chrome.storage.local for the direct-WSS pairing bootstrap.
//
// Holds the pairing credentials pushed by the admin UI (or entered manually in
// the sidepanel) plus a stable per-install deviceId. Mirrors the official
// extension's `bridgeDeviceId`: minted once via crypto.randomUUID() and kept
// across re-pairings so the backend can recognise the same device.
//
// Phase 3.1 Track B — see docs/specs/phase-3.1-contract.md §1/§2.

const KEYS = {
  serverUrl: 'serverUrl',
  pat: 'pat',
  deviceName: 'deviceName',
  deviceId: 'deviceId',
} as const

export interface BridgeConfig {
  /** Full WS URL: ws(s)://host:port/api/v1/browser/edge. Absent until paired. */
  serverUrl?: string
  /** Personal Access Token used as the bearer.* subprotocol. Absent until paired. */
  pat?: string
  /** Human label for this device, shown in the admin UI. Absent until paired. */
  deviceName?: string
  /** Stable per-install id, minted on first read and never cleared. */
  deviceId: string
}

/** Pairing credentials, as pushed via the `pair` external message. */
export interface PairingInput {
  serverUrl: string
  pat: string
  deviceName?: string
}

export class ConfigStore {
  // Allow injecting a fake chrome in tests; defaults to the ambient global.
  constructor(
    private readonly storage: chrome.storage.LocalStorageArea = chrome.storage.local,
  ) {}

  private get(keys: string[]): Promise<Record<string, unknown>> {
    return this.storage.get(keys) as Promise<Record<string, unknown>>
  }

  private set(items: Record<string, unknown>): Promise<void> {
    return this.storage.set(items) as Promise<void>
  }

  private remove(keys: string[]): Promise<void> {
    return this.storage.remove(keys) as Promise<void>
  }

  /**
   * Read the stable device id, minting + persisting a fresh UUID on first call.
   * Mirrors the official `bridgeDeviceId` behaviour.
   */
  async getDeviceId(): Promise<string> {
    const got = await this.get([KEYS.deviceId])
    const existing = got[KEYS.deviceId]
    if (typeof existing === 'string' && existing.length > 0) {
      return existing
    }
    const fresh = crypto.randomUUID()
    await this.set({ [KEYS.deviceId]: fresh })
    return fresh
  }

  /** Full config snapshot; deviceId is always present (minted if missing). */
  async getConfig(): Promise<BridgeConfig> {
    const got = await this.get([
      KEYS.serverUrl,
      KEYS.pat,
      KEYS.deviceName,
      KEYS.deviceId,
    ])
    const deviceId =
      typeof got[KEYS.deviceId] === 'string' && (got[KEYS.deviceId] as string).length > 0
        ? (got[KEYS.deviceId] as string)
        : await this.getDeviceId()
    const cfg: BridgeConfig = { deviceId }
    if (typeof got[KEYS.serverUrl] === 'string') cfg.serverUrl = got[KEYS.serverUrl] as string
    if (typeof got[KEYS.pat] === 'string') cfg.pat = got[KEYS.pat] as string
    if (typeof got[KEYS.deviceName] === 'string') cfg.deviceName = got[KEYS.deviceName] as string
    return cfg
  }

  /** Persist pairing credentials. deviceName is optional. */
  async setPairing(input: PairingInput): Promise<void> {
    const items: Record<string, unknown> = {
      [KEYS.serverUrl]: input.serverUrl,
      [KEYS.pat]: input.pat,
    }
    if (input.deviceName !== undefined) {
      items[KEYS.deviceName] = input.deviceName
    }
    await this.set(items)
  }

  /** Forget pairing credentials but keep the stable deviceId. */
  async clearPairing(): Promise<void> {
    await this.remove([KEYS.serverUrl, KEYS.pat, KEYS.deviceName])
  }
}
