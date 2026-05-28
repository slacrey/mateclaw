import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'

beforeEach(() => {
  ;(globalThis as unknown as Record<string, unknown>).chrome = {
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
    expect(
      (globalThis as Record<string, unknown>).chrome.runtime.sendMessage,
    ).toHaveBeenCalled()
    const arg = (
      (globalThis as Record<string, unknown>).chrome.runtime
        .sendMessage as ReturnType<typeof vi.fn>
    ).mock.calls[0]?.[0]
    expect(arg.kind).toBe('edge.outbound')
    expect(arg.message.kind).toBe('ping')
  })

  it('session_id is always empty in the outbound ping (audit P0-1)', async () => {
    const w = mount(App)
    await w.find('button[data-test=ping]').trigger('click')
    const arg = (
      (globalThis as Record<string, unknown>).chrome.runtime
        .sendMessage as ReturnType<typeof vi.fn>
    ).mock.calls[0]?.[0]
    expect(arg.message.session_id).toBe('')
  })

  it('logs inbound messages', async () => {
    const w = mount(App)
    const handler = (
      (globalThis as Record<string, unknown>).chrome.runtime.onMessage
        .addListener as ReturnType<typeof vi.fn>
    ).mock.calls[0]?.[0]
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
