import { describe, expect, it, beforeEach } from 'vitest'
import { ConfigStore } from './config-store'

// Object-backed fake of chrome.storage.local: get(keys) returns the subset,
// set(items) merges, remove(keys) deletes. Async like the real Promise API.
function fakeStorage() {
  const backing: Record<string, unknown> = {}
  return {
    backing,
    area: {
      get: (keys: string[]) => {
        const out: Record<string, unknown> = {}
        for (const k of keys) {
          if (k in backing) out[k] = backing[k]
        }
        return Promise.resolve(out)
      },
      set: (items: Record<string, unknown>) => {
        Object.assign(backing, items)
        return Promise.resolve()
      },
      remove: (keys: string[]) => {
        for (const k of keys) delete backing[k]
        return Promise.resolve()
      },
    } as unknown as chrome.storage.LocalStorageArea,
  }
}

describe('ConfigStore', () => {
  let fake: ReturnType<typeof fakeStorage>
  let store: ConfigStore

  beforeEach(() => {
    fake = fakeStorage()
    store = new ConfigStore(fake.area)
  })

  it('getDeviceId mints + persists a UUID on first call', async () => {
    expect(fake.backing['deviceId']).toBeUndefined()
    const id = await store.getDeviceId()
    expect(id).toMatch(/^[0-9a-f-]{36}$/)
    expect(fake.backing['deviceId']).toBe(id)
  })

  it('getDeviceId is stable across calls', async () => {
    const a = await store.getDeviceId()
    const b = await store.getDeviceId()
    expect(b).toBe(a)
  })

  it('getConfig returns deviceId even when nothing else is set', async () => {
    const cfg = await store.getConfig()
    expect(cfg.deviceId).toMatch(/^[0-9a-f-]{36}$/)
    expect(cfg.serverUrl).toBeUndefined()
    expect(cfg.pat).toBeUndefined()
    expect(cfg.deviceName).toBeUndefined()
  })

  it('setPairing persists serverUrl + pat + deviceName', async () => {
    await store.setPairing({
      serverUrl: 'ws://localhost:18088/api/v1/browser/edge',
      pat: 'mc_secret',
      deviceName: 'Work Laptop',
    })
    const cfg = await store.getConfig()
    expect(cfg.serverUrl).toBe('ws://localhost:18088/api/v1/browser/edge')
    expect(cfg.pat).toBe('mc_secret')
    expect(cfg.deviceName).toBe('Work Laptop')
  })

  it('setPairing without deviceName leaves deviceName unset', async () => {
    await store.setPairing({
      serverUrl: 'ws://localhost:18088/api/v1/browser/edge',
      pat: 'mc_secret',
    })
    const cfg = await store.getConfig()
    expect(cfg.deviceName).toBeUndefined()
  })

  it('clearPairing removes serverUrl + pat + deviceName but keeps deviceId', async () => {
    const id = await store.getDeviceId()
    await store.setPairing({
      serverUrl: 'ws://localhost:18088/api/v1/browser/edge',
      pat: 'mc_secret',
      deviceName: 'Work Laptop',
    })
    await store.clearPairing()
    const cfg = await store.getConfig()
    expect(cfg.serverUrl).toBeUndefined()
    expect(cfg.pat).toBeUndefined()
    expect(cfg.deviceName).toBeUndefined()
    expect(cfg.deviceId).toBe(id)
  })
})
