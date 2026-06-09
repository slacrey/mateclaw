import { afterEach, describe, expect, it, vi } from 'vitest'
import { typeDmDraftHandler } from './type_dm_draft'

function fakeDebugger() {
  return {
    attach: vi.fn(async () => {}),
    detach: vi.fn(async () => {}),
    send: vi.fn(async () => ({})),
    isAttached: () => true,
  } as any
}

function chromeWithDomExecution() {
  return {
    scripting: {
      executeScript: vi.fn(async ({ func, args }) => [{
        result: await func(...args),
      }]),
    },
  } as unknown as typeof chrome
}

function setRect(el: Element, rect: Partial<DOMRect>) {
  const full = {
    left: rect.left ?? rect.x ?? 0,
    top: rect.top ?? rect.y ?? 0,
    width: rect.width ?? 80,
    height: rect.height ?? 36,
    right: (rect.left ?? rect.x ?? 0) + (rect.width ?? 80),
    bottom: (rect.top ?? rect.y ?? 0) + (rect.height ?? 36),
    x: rect.x ?? rect.left ?? 0,
    y: rect.y ?? rect.top ?? 0,
    toJSON: () => ({}),
  } as DOMRect
  vi.spyOn(el, 'getBoundingClientRect').mockReturnValue(full)
}

describe('type dm draft handler', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })

  it('types the draft without sending by default', async () => {
    document.body.innerHTML = `
      <main>
        <h1>私信</h1>
        <div id="editor" role="textbox" contenteditable="true" aria-label="发送消息"></div>
        <button id="send">发送</button>
      </main>
    `
    Object.defineProperty(location, 'hostname', { value: 'www.douyin.com', configurable: true })
    Object.defineProperty(window, 'innerWidth', { value: 1280, configurable: true })
    Object.defineProperty(window, 'innerHeight', { value: 800, configurable: true })
    setRect(document.querySelector('#editor')!, { left: 760, top: 700, width: 360, height: 44 })
    setRect(document.querySelector('#send')!, { left: 1130, top: 700, width: 70, height: 44 })
    const sendClick = vi.fn()
    document.querySelector('#send')!.addEventListener('click', sendClick)
    const handler = typeDmDraftHandler({ debugger: fakeDebugger(), chrome: chromeWithDomExecution() })

    const result = await handler(42, { text: '你好' }, 5000)

    expect(result.ok).toBe(true)
    expect(result.payload).toMatchObject({ draftTyped: true, sent: false })
    expect(document.querySelector('#editor')?.textContent).toContain('你好')
    expect(sendClick).not.toHaveBeenCalled()
  })

  it('clicks send and confirms the draft is cleared when send=true', async () => {
    document.body.innerHTML = `
      <main>
        <h1>私信</h1>
        <div id="editor" role="textbox" contenteditable="true" aria-label="发送消息"></div>
        <button id="send">发送</button>
      </main>
    `
    Object.defineProperty(location, 'hostname', { value: 'www.douyin.com', configurable: true })
    Object.defineProperty(window, 'innerWidth', { value: 1280, configurable: true })
    Object.defineProperty(window, 'innerHeight', { value: 800, configurable: true })
    const editor = document.querySelector<HTMLElement>('#editor')!
    const sendButton = document.querySelector<HTMLElement>('#send')!
    setRect(editor, { left: 760, top: 700, width: 360, height: 44 })
    setRect(sendButton, { left: 1130, top: 700, width: 70, height: 44 })
    const sendClick = vi.fn(() => {
      editor.textContent = ''
    })
    sendButton.addEventListener('click', sendClick)
    const handler = typeDmDraftHandler({ debugger: fakeDebugger(), chrome: chromeWithDomExecution() })

    const result = await handler(42, { text: '你好', send: true }, 5000)

    expect(result.ok).toBe(true)
    expect(result.payload).toMatchObject({ draftTyped: true, sent: true })
    expect(sendClick).toHaveBeenCalledTimes(1)
    expect(editor.textContent).not.toContain('你好')
  })
})
