import { afterEach, describe, expect, it, vi } from 'vitest'
import { ActionFailureError } from '../ActionExecutor'
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

  it('does not click the upload control when sending a dm', async () => {
    document.body.innerHTML = `
      <main>
        <h1>私信</h1>
        <div id="editor" role="textbox" contenteditable="true" aria-label="发送消息"></div>
        <button id="upload" aria-label="上传图片"><input type="file" /></button>
        <button id="send" style="background-color: rgb(254, 44, 85)"><svg aria-hidden="true"></svg></button>
      </main>
    `
    Object.defineProperty(location, 'hostname', { value: 'www.douyin.com', configurable: true })
    Object.defineProperty(window, 'innerWidth', { value: 1280, configurable: true })
    Object.defineProperty(window, 'innerHeight', { value: 800, configurable: true })
    const editor = document.querySelector<HTMLElement>('#editor')!
    const uploadButton = document.querySelector<HTMLElement>('#upload')!
    const sendButton = document.querySelector<HTMLElement>('#send')!
    setRect(document.querySelector('main')!, { left: 720, top: 680, width: 540, height: 80 })
    setRect(editor, { left: 760, top: 700, width: 360, height: 44 })
    setRect(uploadButton, { left: 1130, top: 700, width: 44, height: 44 })
    setRect(sendButton, { left: 1190, top: 700, width: 44, height: 44 })
    const uploadClick = vi.fn()
    const sendClick = vi.fn(() => {
      editor.textContent = ''
    })
    uploadButton.addEventListener('click', uploadClick)
    sendButton.addEventListener('click', sendClick)
    const handler = typeDmDraftHandler({ debugger: fakeDebugger(), chrome: chromeWithDomExecution() })

    const result = await handler(42, { text: '你好', send: true }, 5000)

    expect(result.ok).toBe(true)
    expect(result.payload).toMatchObject({ draftTyped: true, sent: true })
    expect(uploadClick).not.toHaveBeenCalled()
    expect(sendClick).toHaveBeenCalledTimes(1)
  })

  it('does not fall back to cdp when the draft is typed but send is not confirmed', async () => {
    const debug = fakeDebugger()
    const chrome = {
      scripting: {
        executeScript: vi.fn(async () => [{
          result: {
            ok: false,
            draftTyped: true,
            sent: false,
            reason: 'dm_send_button_not_found',
            target: 'dm_editable',
          },
        }]),
      },
    } as unknown as typeof chrome
    const handler = typeDmDraftHandler({ debugger: debug, chrome })

    await expect(handler(42, { text: '你好', send: true }, 5000)).rejects.toMatchObject({
      code: 'GROUNDING_AMBIGUOUS',
      message: 'dm draft typed but send failed: dm_send_button_not_found',
    } satisfies Partial<ActionFailureError>)
    expect(debug.attach).not.toHaveBeenCalled()
    expect(debug.send).not.toHaveBeenCalled()
  })
})
