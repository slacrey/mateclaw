import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'

type ChromeShim = {
  runtime: {
    sendMessage: ReturnType<typeof vi.fn>
    onMessage: { addListener: ReturnType<typeof vi.fn> }
  }
}

function chromeShim(): ChromeShim {
  return (globalThis as unknown as { chrome: ChromeShim }).chrome
}

beforeEach(() => {
  ;(globalThis as unknown as { chrome: ChromeShim }).chrome = {
    runtime: {
      sendMessage: vi.fn().mockResolvedValue({ ok: true }),
      onMessage: { addListener: vi.fn() },
    },
  }
})

describe('Sidepanel App', () => {
  it('renders the ping button', () => {
    const w = mount(App)
    expect(w.find('button[data-test=ping]').exists()).toBe(true)
  })

  it('sends an edge.outbound on click', async () => {
    const w = mount(App)
    await w.find('button[data-test=ping]').trigger('click')
    expect(chromeShim().runtime.sendMessage).toHaveBeenCalled()
    const arg = chromeShim().runtime.sendMessage.mock.calls[0]?.[0] as {
      kind: string
      message: { kind: string }
    }
    expect(arg.kind).toBe('edge.outbound')
    expect(arg.message.kind).toBe('ping')
  })

  it('session_id is always empty in the outbound ping (audit P0-1)', async () => {
    const w = mount(App)
    await w.find('button[data-test=ping]').trigger('click')
    const arg = chromeShim().runtime.sendMessage.mock.calls[0]?.[0] as {
      message: { session_id: string }
    }
    expect(arg.message.session_id).toBe('')
  })

  it('logs inbound messages', async () => {
    const w = mount(App)
    const handler = chromeShim().runtime.onMessage.addListener.mock.calls[0]?.[0] as
      | ((msg: unknown) => void)
      | undefined
    if (!handler) throw new Error('no onMessage listener registered')
    handler({
      kind: 'edge.inbound',
      message: {
        v: 1,
        msg_id: 'x',
        kind: 'pong',
        ts: 0,
        trace_id: 't',
        session_id: 's',
        payload: { echo: 'yo', server_ts: 1 },
      },
    })
    await w.vm.$nextTick()
    expect(w.text()).toContain('pong')
    expect(w.text()).toContain('yo')
  })
})
