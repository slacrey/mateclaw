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

  // onMounted now also fires a bridge.status poll, so locate the ping call by
  // predicate rather than assuming it is calls[0].
  function pingCall(): { kind: string; message: { kind: string; session_id: string } } {
    const calls = chromeShim().runtime.sendMessage.mock.calls as Array<
      [{ kind?: string; message?: { kind?: string } }]
    >
    const found = calls.find(c => c[0]?.kind === 'edge.outbound')
    if (!found) throw new Error('no edge.outbound sendMessage')
    return found[0] as { kind: string; message: { kind: string; session_id: string } }
  }

  it('sends an edge.outbound on click', async () => {
    const w = mount(App)
    await w.find('button[data-test=ping]').trigger('click')
    expect(chromeShim().runtime.sendMessage).toHaveBeenCalled()
    const arg = pingCall()
    expect(arg.kind).toBe('edge.outbound')
    expect(arg.message.kind).toBe('ping')
  })

  it('session_id is always empty in the outbound ping (audit P0-1)', async () => {
    const w = mount(App)
    await w.find('button[data-test=ping]').trigger('click')
    expect(pingCall().message.session_id).toBe('')
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
