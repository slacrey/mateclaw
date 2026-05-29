// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest'
import {
  deriveWsUrl,
  defaultDeviceName,
  sendToExtension,
  statusFromPing,
  runConnect,
  EXTENSION_ID,
  type ConnectDeps,
  type PingResponse,
} from '../pairing'

// ---------------------------------------------------------------------------
// deriveWsUrl — origin → WS endpoint mapping (contract §1)
// ---------------------------------------------------------------------------
describe('deriveWsUrl', () => {
  it('maps https → wss and pins the edge path', () => {
    expect(deriveWsUrl('https://mateclaw.example.com')).toBe(
      'wss://mateclaw.example.com/api/v1/browser/edge',
    )
  })

  it('maps http → ws and preserves host:port', () => {
    expect(deriveWsUrl('http://localhost:18088')).toBe(
      'ws://localhost:18088/api/v1/browser/edge',
    )
  })

  it('drops any existing path / query / hash on the origin', () => {
    expect(deriveWsUrl('https://host:5173/settings/browser?x=1#y')).toBe(
      'wss://host:5173/api/v1/browser/edge',
    )
  })
})

// ---------------------------------------------------------------------------
// defaultDeviceName — never throws, reads UA when present
// ---------------------------------------------------------------------------
describe('defaultDeviceName', () => {
  it('returns a non-empty string', () => {
    expect(defaultDeviceName().length).toBeGreaterThan(0)
  })
})

// ---------------------------------------------------------------------------
// sendToExtension — graceful degradation when chrome is unavailable
// ---------------------------------------------------------------------------
describe('sendToExtension', () => {
  it('resolves null when chrome is undefined (e.g. Firefox)', async () => {
    expect(await sendToExtension({ type: 'ping' }, undefined)).toBeNull()
  })

  it('resolves null when chrome.runtime.sendMessage is missing', async () => {
    expect(await sendToExtension({ type: 'ping' }, { runtime: {} })).toBeNull()
  })

  it('resolves null when runtime.lastError is set (extension not listening)', async () => {
    const chromeLike = {
      runtime: {
        lastError: { message: 'Could not establish connection.' },
        sendMessage: (_id: string, _msg: unknown, cb: (r: unknown) => void) => cb(undefined),
      },
    }
    expect(await sendToExtension({ type: 'ping' }, chromeLike)).toBeNull()
  })

  it('resolves null (not throws) when sendMessage throws synchronously', async () => {
    const chromeLike = {
      runtime: {
        sendMessage: () => {
          throw new Error('boom')
        },
      },
    }
    expect(await sendToExtension({ type: 'ping' }, chromeLike)).toBeNull()
  })

  it('forwards EXTENSION_ID and resolves the response on success', async () => {
    const sendMessage = vi.fn(
      (_id: string, _msg: unknown, cb: (r: unknown) => void) =>
        cb({ alive: true, connected: false, deviceName: 'X', deviceId: 'd1' }),
    )
    const resp = await sendToExtension<PingResponse>({ type: 'ping' }, { runtime: { sendMessage } })
    expect(sendMessage).toHaveBeenCalledWith(EXTENSION_ID, { type: 'ping' }, expect.any(Function))
    expect(resp).toEqual({ alive: true, connected: false, deviceName: 'X', deviceId: 'd1' })
  })
})

// ---------------------------------------------------------------------------
// statusFromPing — the grey/yellow/green decision
// ---------------------------------------------------------------------------
describe('statusFromPing', () => {
  it('null → not-detected (grey)', () => {
    expect(statusFromPing(null)).toBe('not-detected')
  })
  it('alive but not connected → detected (yellow)', () => {
    expect(
      statusFromPing({ alive: true, connected: false, deviceName: null, deviceId: 'd' }),
    ).toBe('detected')
  })
  it('alive + connected → connected (green)', () => {
    expect(
      statusFromPing({ alive: true, connected: true, deviceName: 'Mac', deviceId: 'd' }),
    ).toBe('connected')
  })
})

// ---------------------------------------------------------------------------
// runConnect — the mint → pair → poll state machine
// ---------------------------------------------------------------------------
function baseDeps(overrides: Partial<ConnectDeps> = {}): ConnectDeps {
  return {
    mintToken: vi.fn(async () => ({ token: 'mc_secret', tokenId: 'tok-1', expiresAt: '2026-06-29T00:00:00Z' })),
    revokeToken: vi.fn(async () => ({ ok: true })),
    pair: vi.fn(async () => ({ ok: true as const })),
    ping: vi.fn(async () => ({ alive: true as const, connected: true, deviceName: 'X', deviceId: 'd' })),
    wsUrl: () => 'ws://localhost:18088/api/v1/browser/edge',
    sleep: async () => {}, // no real delay under test
    ...overrides,
  }
}

describe('runConnect — success path (mint → pair → poll → connected)', () => {
  it('mints, pairs with the derived URL, polls once, and returns tokenId', async () => {
    const deps = baseDeps()
    const result = await runConnect(deps, { deviceName: 'Work Laptop', pollIntervalMs: 0 })

    expect(result).toEqual({ ok: true, tokenId: 'tok-1', expiresAt: '2026-06-29T00:00:00Z' })
    expect(deps.mintToken).toHaveBeenCalledWith('Work Laptop')
    expect(deps.pair).toHaveBeenCalledWith(
      'mc_secret',
      'ws://localhost:18088/api/v1/browser/edge',
      'Work Laptop',
    )
    expect(deps.revokeToken).not.toHaveBeenCalled()
  })

  it('keeps polling until connected:true flips', async () => {
    let calls = 0
    const ping = vi.fn(async (): Promise<PingResponse> => {
      calls += 1
      return { alive: true, connected: calls >= 3, deviceName: null, deviceId: 'd' }
    })
    const deps = baseDeps({ ping })
    const result = await runConnect(deps, { deviceName: 'L', maxPolls: 5, pollIntervalMs: 0 })
    expect(result.ok).toBe(true)
    expect(ping).toHaveBeenCalledTimes(3)
  })
})

describe('runConnect — failure paths', () => {
  it('mint failure → reason "mint", never pairs, never revokes', async () => {
    const deps = baseDeps({
      mintToken: vi.fn(async () => {
        throw new Error('403 forbidden')
      }),
    })
    const result = await runConnect(deps, { deviceName: 'L' })
    expect(result).toEqual({ ok: false, reason: 'mint', error: '403 forbidden' })
    expect(deps.pair).not.toHaveBeenCalled()
    expect(deps.revokeToken).not.toHaveBeenCalled()
  })

  it('pair rejected → reason "pair", revokes the minted PAT', async () => {
    const deps = baseDeps({
      pair: vi.fn(async () => ({ ok: false as const, error: 'origin-not-whitelisted' })),
    })
    const result = await runConnect(deps, { deviceName: 'L' })
    expect(result).toEqual({ ok: false, reason: 'pair', error: 'origin-not-whitelisted' })
    expect(deps.revokeToken).toHaveBeenCalledWith('tok-1')
  })

  it('pair returns null (extension unreachable) → reason "pair", revokes', async () => {
    const deps = baseDeps({ pair: vi.fn(async () => null) })
    const result = await runConnect(deps, { deviceName: 'L' })
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.reason).toBe('pair')
    expect(deps.revokeToken).toHaveBeenCalledWith('tok-1')
  })

  it('never connects within maxPolls → reason "timeout", revokes', async () => {
    const deps = baseDeps({
      ping: vi.fn(async () => ({ alive: true as const, connected: false, deviceName: null, deviceId: 'd' })),
    })
    const result = await runConnect(deps, { deviceName: 'L', maxPolls: 3, pollIntervalMs: 0 })
    expect(result).toEqual({ ok: false, reason: 'timeout', error: 'never-connected' })
    expect(deps.ping).toHaveBeenCalledTimes(3)
    expect(deps.revokeToken).toHaveBeenCalledWith('tok-1')
  })

  it('revoke throwing during cleanup does not surface (best-effort)', async () => {
    const deps = baseDeps({
      pair: vi.fn(async () => ({ ok: false as const, error: 'nope' })),
      revokeToken: vi.fn(async () => {
        throw new Error('revoke endpoint is a stub')
      }),
    })
    const result = await runConnect(deps, { deviceName: 'L' })
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.reason).toBe('pair')
  })
})
