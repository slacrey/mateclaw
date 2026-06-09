import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { TypeDmDraftParams } from '../types'
import { SessionDetachedError, type DebuggerManager } from '../../debugger-manager'

export interface TypeDmDraftHandlerDeps {
  debugger: DebuggerManager
  chrome?: typeof globalThis.chrome
}

export const typeDmDraftHandler = (
  deps: TypeDmDraftHandlerDeps = {},
): ActionHandler<TypeDmDraftParams> => {
  return async (tabId, params, _deadlineMs) => {
    const text = String(params?.text || '').trim()
    if (!text) {
      throw new ActionFailureError('HANDLER_ERROR', 'type_dm_draft text is required', false)
    }
    const send = params?.send === true
    const chromeApi = deps.chrome ?? globalThis.chrome
    if (!chromeApi?.scripting?.executeScript) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.scripting.executeScript is unavailable', true)
    }

    const results = await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: typeDouyinDmDraftInPage,
      args: [text, send],
    })
    const payload = results?.[0]?.result as
      | { ok?: boolean; reason?: string; draftTyped?: boolean; sent?: boolean; target?: string; sendTarget?: string }
      | undefined
    if (payload?.ok !== true || payload.draftTyped !== true) {
      const cdp = await typeDmDraftByCdp(deps.debugger, tabId, text)
      if (cdp.ok === true) {
        const sent = send ? await clickDmSendInPage(tabId, text) : { ok: true, sent: false, target: undefined }
        if (!sent.ok) {
          throw new ActionFailureError(
            'GROUNDING_AMBIGUOUS',
            `dm draft typed but send failed: ${sent.reason || 'send_button_not_found'}`,
            false,
          )
        }
        return {
          ok: true,
          elapsed_ms: 0,
          payload: {
            draftTyped: true,
            text,
            target: cdp.target || 'dm_cdp_insert_text',
            sent: sent.sent === true,
            sendTarget: sent.target,
          },
        }
      }
      throw new ActionFailureError(
        'GROUNDING_AMBIGUOUS',
        `${payload?.reason || 'dm draft was not observed after typing'}; cdp=${cdp.reason || 'failed'}`,
        false,
      )
    }
    return {
      ok: true,
      elapsed_ms: 0,
      payload: {
        draftTyped: true,
        text,
        target: payload.target || 'dm_editable',
        sent: payload.sent === true,
        sendTarget: payload.sendTarget,
      },
    }
  }
}

async function typeDmDraftByCdp(
  debug: DebuggerManager,
  tabId: number,
  text: string,
): Promise<{ ok: boolean; reason?: string; target?: string }> {
  try {
    const point = await findDmInputClickPoint(tabId)
    if (!point) return { ok: false, reason: 'dm_input_click_point_not_found' }
    await debug.attach(tabId)
    await debug.send(tabId, 'Input.dispatchMouseEvent', {
      type: 'mousePressed',
      x: point.x,
      y: point.y,
      button: 'left',
      clickCount: 1,
      modifiers: 0,
    })
    await debug.send(tabId, 'Input.dispatchMouseEvent', {
      type: 'mouseReleased',
      x: point.x,
      y: point.y,
      button: 'left',
      clickCount: 1,
      modifiers: 0,
    })
    await debug.send(tabId, 'Input.insertText', { text })
    await sleep(180)
    const observed = await draftVisibleInPage(tabId, text)
    return {
      ok: observed,
      reason: observed ? undefined : `draft_not_visible_after_cdp_insert@${Math.round(point.x)},${Math.round(point.y)}`,
      target: `cdp@${Math.round(point.x)},${Math.round(point.y)}`,
    }
  } catch (error) {
    if (error instanceof SessionDetachedError) {
      return { ok: false, reason: error.message }
    }
    return { ok: false, reason: error instanceof Error ? error.message : String(error) }
  }
}

async function typeDouyinDmDraftInPage(
  text: string,
  send: boolean,
): Promise<{ ok: boolean; reason?: string; draftTyped?: boolean; sent?: boolean; target?: string; sendTarget?: string }> {
  if (!/douyin\.com$/u.test(location.hostname) && !location.hostname.endsWith('.douyin.com')) {
    return { ok: false, reason: 'not_douyin_page' }
  }
  const pageText = document.body?.innerText || document.body?.textContent || ''
  if (!/私信|发送消息|输入消息|消息/u.test(pageText)) {
    return { ok: false, reason: 'dm_context_not_visible' }
  }
  const target = findDmEditable()
  if (!target) {
    return { ok: false, reason: 'dm_editable_not_found' }
  }
  target.scrollIntoView({ block: 'center', inline: 'center' })
  target.focus()

  if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) {
    const setter = Object.getOwnPropertyDescriptor(
      target instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype,
      'value',
    )?.set
    if (setter) setter.call(target, text)
    else target.value = text
    target.dispatchEvent(new InputEvent('input', {
      bubbles: true,
      composed: true,
      data: text,
      inputType: 'insertReplacementText',
    }))
    target.dispatchEvent(new Event('change', { bubbles: true, composed: true }))
  } else {
    placeCaretAtEnd(target)
    const selected = selectEditableContent(target)
    if (selected) {
      document.execCommand?.('delete', false)
    } else {
      target.textContent = ''
      placeCaretAtEnd(target)
    }
    document.execCommand?.('insertText', false, text)
    if (!(editableText(target).includes(text))) {
      target.textContent = text
      target.dispatchEvent(new InputEvent('input', {
        bubbles: true,
        composed: true,
        data: text,
        inputType: 'insertReplacementText',
      }))
    }
  }

  const typed = editableText(target).includes(text) ||
    findDmEditableText().includes(text) ||
    clean(document.body?.innerText || document.body?.textContent || '').includes(text)
  if (!typed || !send) {
    return {
      ok: typed,
      draftTyped: typed,
      sent: false,
      reason: typed ? undefined : 'draft_text_not_visible_in_editable',
      target: targetDescription(target),
    }
  }
  await sleep(160)
  const sendResult = clickDmSendButton(text, target)
  if (!sendResult.ok) {
    return {
      ok: false,
      draftTyped: true,
      sent: false,
      reason: sendResult.reason,
      target: targetDescription(target),
      sendTarget: sendResult.target,
    }
  }
  await sleep(260)
  const sent = !draftStillVisibleInEditable(text)
  return {
    ok: sent,
    draftTyped: true,
    sent,
    reason: sent ? undefined : 'dm_send_not_confirmed_after_click',
    target: targetDescription(target),
    sendTarget: sendResult.target,
  }
}

async function clickDmSendInPage(
  tabId: number,
  text: string,
): Promise<{ ok: boolean; sent?: boolean; target?: string; reason?: string }> {
  const [result] = await chrome.scripting.executeScript({
    target: { tabId, allFrames: false },
    func: async (draft: string) => {
      const clean = (value: string) => String(value || '').replace(/\s+/g, '')
      const elementText = (el: HTMLElement) =>
        `${el.getAttribute('placeholder') || ''} ${el.getAttribute('aria-label') || ''} ${el.getAttribute('title') || ''} ${el.innerText || el.textContent || ''}`.replace(/\s+/g, '')
      const editableText = (el: HTMLElement) => {
        if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return el.value || ''
        return el.innerText || el.textContent || ''
      }
      const isEditable = (el: HTMLElement) =>
        el instanceof HTMLInputElement ||
        el instanceof HTMLTextAreaElement ||
        el.isContentEditable ||
        (el.getAttribute('role') || '').toLowerCase() === 'textbox' ||
        el.getAttribute('data-slate-editor') === 'true' ||
        el.classList.contains('ProseMirror')
      const editableRoot = (el: HTMLElement): HTMLElement | null => {
        if (isEditable(el)) return el
        const closest = el.closest<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror')
        if (closest && isEditable(closest)) return closest
        const nested = el.querySelector<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror')
        return nested && isEditable(nested) ? nested : null
      }
      const findEditable = () => {
        const selectors = [
          'textarea',
          'input',
          '[contenteditable="true"]',
          '[contenteditable=""]',
          '[contenteditable="plaintext-only"]',
          '[role="textbox"]',
          '[data-slate-editor="true"]',
          '.ProseMirror',
          '[placeholder*="消息"]',
          '[placeholder*="私信"]',
          '[placeholder*="发送"]',
          '[aria-label*="消息"]',
          '[aria-label*="私信"]',
        ].join(',')
        const viewportH = window.innerHeight || document.documentElement.clientHeight || 1
        const viewportW = window.innerWidth || document.documentElement.clientWidth || 1
        return Array.from(document.querySelectorAll<HTMLElement>(selectors))
          .map((el, index) => {
            const root = editableRoot(el)
            const rect = (root ?? el).getBoundingClientRect()
            return { el: root, index, rect, text: root ? elementText(root) : '' }
          })
          .filter((item): item is { el: HTMLElement; index: number; rect: DOMRect; text: string } => item.el !== null)
          .filter(item => item.rect.width > 0 && item.rect.height > 0)
          .filter(item => item.rect.top >= Math.max(80, viewportH * 0.22))
          .filter(item => item.rect.left >= viewportW * 0.52)
          .filter(item => !item.text.includes('搜索'))
          .sort((a, b) => b.rect.top - a.rect.top || a.index - b.index)[0]?.el ?? null
      }
      const allEditableText = () => Array.from(document.querySelectorAll<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror'))
        .map(editableText)
        .join('\n')
      const isDisabled = (el: HTMLElement) =>
        el.getAttribute('aria-disabled') === 'true' ||
        el.getAttribute('disabled') === 'true' ||
        (el instanceof HTMLButtonElement && el.disabled)
      const likelySendText = (text: string) => {
        const normalized = clean(text)
        return normalized === '发送' ||
          normalized === 'Send' ||
          (/发送/u.test(normalized) && !/发送消息|输入消息|发送一条文字消息|对方回复|关闭会话|消息/u.test(normalized))
      }
      const nearEditable = (rect: DOMRect, editableRect: DOMRect) => {
        const verticalOverlap = rect.top <= editableRect.bottom + 44 && rect.bottom >= editableRect.top - 44
        const rightOfEditable = rect.left >= editableRect.right - 120 || rect.right >= editableRect.right - 24
        const plausibleSize = rect.width >= 24 && rect.width <= 140 && rect.height >= 24 && rect.height <= 80
        return verticalOverlap && rightOfEditable && plausibleSize
      }
      const score = (item: { el: HTMLElement; rect: DOMRect; text: string }, editableRect: DOMRect) => {
        let value = 0
        const role = (item.el.getAttribute('role') || item.el.tagName || '').toLowerCase()
        const normalized = clean(item.text)
        if (role.includes('button')) value += 120
        if (normalized === '发送' || normalized === 'Send') value += 140
        if (/发送/u.test(normalized)) value += 80
        if (nearEditable(item.rect, editableRect)) value += 100
        if (item.rect.left >= editableRect.right - 120) value += 40
        if (/搜索|关闭会话|回关|发送消息|输入消息|对方回复/u.test(normalized)) value -= 240
        return value
      }
      const click = (el: HTMLElement) => {
        el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, composed: true }))
        el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, composed: true }))
        el.click()
      }
      await new Promise<void>(resolve => setTimeout(resolve, 160))
      const editable = findEditable()
      if (!editable) return { ok: false, reason: 'dm_editable_not_found_before_send' }
      const wanted = clean(draft)
      if (!clean(allEditableText()).includes(wanted) && !clean(editableText(editable)).includes(wanted)) {
        return { ok: false, reason: 'draft_not_visible_before_send' }
      }
      const editableRect = editable.getBoundingClientRect()
      const viewportW = window.innerWidth || document.documentElement.clientWidth || 1
      const viewportH = window.innerHeight || document.documentElement.clientHeight || 1
      const selectors = 'button,[role="button"],[aria-label*="发送"],[title*="发送"],div[tabindex],span[tabindex]'
      const button = Array.from(document.querySelectorAll<HTMLElement>(selectors))
        .map((el, index) => ({ el, index, rect: el.getBoundingClientRect(), text: elementText(el) }))
        .filter(item => item.rect.width > 0 && item.rect.height > 0)
        .filter(item => item.rect.left >= viewportW * 0.45)
        .filter(item => item.rect.top >= Math.max(120, viewportH * 0.32))
        .filter(item => !isDisabled(item.el))
        .filter(item => likelySendText(item.text) || nearEditable(item.rect, editableRect))
        .sort((a, b) => score(b, editableRect) - score(a, editableRect) || a.index - b.index)[0]?.el ?? null
      if (!button) return { ok: false, reason: 'dm_send_button_not_found' }
      button.scrollIntoView({ block: 'center', inline: 'center' })
      click(button)
      await new Promise<void>(resolve => setTimeout(resolve, 260))
      const sent = !clean(allEditableText()).includes(wanted)
      return {
        ok: sent,
        sent,
        target: button.tagName.toLowerCase(),
        reason: sent ? undefined : 'dm_send_not_confirmed_after_click',
      }
    },
    args: [text],
  })
  const payload = result?.result as { ok?: boolean; sent?: boolean; target?: string; reason?: string } | undefined
  return {
    ok: payload?.ok === true,
    sent: payload?.sent === true,
    target: payload?.target,
    reason: payload?.reason,
  }
}

function clickDmSendButton(
  text: string,
  editable?: HTMLElement | null,
): { ok: boolean; sent?: boolean; target?: string; reason?: string } {
  if (!/douyin\.com$/u.test(location.hostname) && !location.hostname.endsWith('.douyin.com')) {
    return { ok: false, reason: 'not_douyin_page' }
  }
  const wanted = clean(text)
  if (!wanted) return { ok: false, reason: 'empty_draft' }
  const currentEditable = editable ?? findDmEditable()
  if (!currentEditable) return { ok: false, reason: 'dm_editable_not_found_before_send' }
  if (!clean(findDmEditableText()).includes(wanted) && !clean(editableText(currentEditable)).includes(wanted)) {
    return { ok: false, reason: 'draft_not_visible_before_send' }
  }
  const button = findDmSendButton(currentEditable)
  if (!button) return { ok: false, reason: 'dm_send_button_not_found' }
  button.scrollIntoView({ block: 'center', inline: 'center' })
  clickElement(button)
  const sendTarget = targetDescription(button)
  return {
    ok: true,
    sent: false,
    target: sendTarget,
  }
}

function draftStillVisibleInEditable(text: string): boolean {
  const wanted = clean(text)
  return !!wanted && clean(findDmEditableText()).includes(wanted)
}

function findDmSendButton(editable: HTMLElement): HTMLElement | null {
  const editableRect = editable.getBoundingClientRect()
  const viewportW = window.innerWidth || document.documentElement.clientWidth || 1
  const viewportH = window.innerHeight || document.documentElement.clientHeight || 1
  const selectors = [
    'button',
    '[role="button"]',
    '[aria-label*="发送"]',
    '[title*="发送"]',
    'div[tabindex]',
    'span[tabindex]',
  ].join(',')
  return Array.from(document.querySelectorAll<HTMLElement>(selectors))
    .map((el, index) => ({ el, index, rect: el.getBoundingClientRect(), text: elementText(el) }))
    .filter(item => item.rect.width > 0 && item.rect.height > 0)
    .filter(item => item.rect.left >= viewportW * 0.45)
    .filter(item => item.rect.top >= Math.max(120, viewportH * 0.32))
    .filter(item => !isDisabled(item.el))
    .filter(item => isLikelySendButtonText(item.text) || isNearEditableSendControl(item.rect, editableRect))
    .sort((a, b) => scoreSendButton(b, editableRect) - scoreSendButton(a, editableRect) || a.index - b.index)[0]?.el ?? null
}

function isLikelySendButtonText(text: string): boolean {
  const normalized = clean(text)
  return normalized === '发送'
    || normalized === 'Send'
    || (/发送/u.test(normalized)
      && !/发送消息|输入消息|发送一条文字消息|对方回复|关闭会话|消息/u.test(normalized))
}

function isNearEditableSendControl(rect: DOMRect, editableRect: DOMRect): boolean {
  const verticalOverlap = rect.top <= editableRect.bottom + 44 && rect.bottom >= editableRect.top - 44
  const rightOfEditable = rect.left >= editableRect.right - 120 || rect.right >= editableRect.right - 24
  const plausibleSize = rect.width >= 24 && rect.width <= 140 && rect.height >= 24 && rect.height <= 80
  return verticalOverlap && rightOfEditable && plausibleSize
}

function scoreSendButton(item: { el: HTMLElement; rect: DOMRect; text: string }, editableRect: DOMRect): number {
  let score = 0
  const role = (item.el.getAttribute('role') || item.el.tagName || '').toLowerCase()
  const text = clean(item.text)
  if (role.includes('button')) score += 120
  if (text === '发送' || text === 'Send') score += 140
  if (/发送/u.test(text)) score += 80
  if (isNearEditableSendControl(item.rect, editableRect)) score += 100
  if (item.rect.left >= editableRect.right - 120) score += 40
  if (/搜索|关闭会话|回关|发送消息|输入消息|对方回复/u.test(text)) score -= 240
  return score
}

function clickElement(el: HTMLElement): void {
  if (typeof PointerEvent === 'function') {
    el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true }))
  }
  el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, composed: true }))
  if (typeof PointerEvent === 'function') {
    el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, composed: true }))
  }
  el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, composed: true }))
  el.click()
}

function isDisabled(el: HTMLElement): boolean {
  return el.getAttribute('aria-disabled') === 'true'
    || el.getAttribute('disabled') === 'true'
    || (el instanceof HTMLButtonElement && el.disabled)
}

function findDmEditable(): HTMLElement | null {
  const selectors = [
    'textarea',
    'input',
    '[contenteditable="true"]',
    '[contenteditable=""]',
    '[contenteditable="plaintext-only"]',
    '[role="textbox"]',
    '[data-slate-editor="true"]',
    '.ProseMirror',
    '.DraftEditor-editorContainer [contenteditable]',
    '[placeholder*="消息"]',
    '[placeholder*="私信"]',
    '[placeholder*="发送"]',
    '[aria-label*="消息"]',
    '[aria-label*="私信"]',
  ].join(',')
  const viewportH = window.innerHeight || document.documentElement.clientHeight || 1
  const viewportW = window.innerWidth || document.documentElement.clientWidth || 1
  return Array.from(document.querySelectorAll<HTMLElement>(selectors))
    .map((el, index) => ({ el, index, rect: el.getBoundingClientRect(), text: elementText(el) }))
    .map(item => {
      const editable = editableRoot(item.el)
      return editable
        ? { ...item, el: editable, rect: editable.getBoundingClientRect(), text: elementText(editable) }
        : { ...item, el: null }
    })
    .filter((item): item is { el: HTMLElement; index: number; rect: DOMRect; text: string } => item.el !== null)
    .filter(item => isEditable(item.el))
    .filter(item => item.rect.width > 0 && item.rect.height > 0)
    .filter(item => item.rect.top >= Math.max(80, viewportH * 0.22))
    .filter(item => item.rect.left >= viewportW * 0.52)
    .filter(item => !item.text.includes('搜索'))
    .sort((a, b) => scoreEditable(b) - scoreEditable(a) || a.index - b.index)[0]?.el ?? null
}

function editableRoot(el: HTMLElement): HTMLElement | null {
  if (isEditable(el)) return el
  const closest = el.closest<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror')
  if (closest && isEditable(closest)) return closest
  const nested = el.querySelector<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror')
  return nested && isEditable(nested) ? nested : null
}

function isEditable(el: HTMLElement): boolean {
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return true
  if (el.isContentEditable) return true
  if ((el.getAttribute('role') || '').toLowerCase() === 'textbox') return true
  if (el.getAttribute('data-slate-editor') === 'true') return true
  return el.classList.contains('ProseMirror')
}

function scoreEditable(item: { el: HTMLElement; rect: DOMRect; text: string }): number {
  let score = 0
  const marker = elementText(item.el)
  if (item.el instanceof HTMLTextAreaElement) score += 120
  if (item.el.isContentEditable) score += 110
  if ((item.el.getAttribute('role') || '').toLowerCase() === 'textbox') score += 80
  if (/消息|私信|发送/u.test(marker)) score += 100
  if (item.rect.top > (window.innerHeight || 1) * 0.55) score += 60
  if (item.rect.width > 160) score += 30
  return score
}

function findDmEditableText(): string {
  return Array.from(document.querySelectorAll<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror'))
    .map(editableText)
    .join('\n')
}

function editableText(el: HTMLElement): string {
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    return el.value || ''
  }
  return el.innerText || el.textContent || ''
}

function elementText(el: HTMLElement): string {
  return `${el.getAttribute('placeholder') || ''} ${el.getAttribute('aria-label') || ''} ${el.innerText || el.textContent || ''}`.replace(/\s+/g, '')
}

function selectEditableContent(el: HTMLElement): boolean {
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    el.select()
    return true
  }
  const selection = window.getSelection()
  if (!selection) return false
  const range = document.createRange()
  range.selectNodeContents(el)
  selection.removeAllRanges()
  selection.addRange(range)
  return true
}

function placeCaretAtEnd(el: HTMLElement): void {
  el.focus()
  const selection = window.getSelection()
  if (!selection || el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return
  const range = document.createRange()
  range.selectNodeContents(el)
  range.collapse(false)
  selection.removeAllRanges()
  selection.addRange(range)
}

function clean(text: string): string {
  return String(text || '').replace(/\s+/g, '')
}

function targetDescription(el: HTMLElement): string {
  return `${el.tagName.toLowerCase()}${el.getAttribute('role') ? `[role=${el.getAttribute('role')}]` : ''}`
}

async function findDmInputClickPoint(tabId: number): Promise<{ x: number; y: number } | null> {
  const [result] = await chrome.scripting.executeScript({
    target: { tabId, allFrames: false },
    func: () => {
      if (!/douyin\.com$/u.test(location.hostname) && !location.hostname.endsWith('.douyin.com')) return null
      const viewportW = window.innerWidth || document.documentElement.clientWidth || 1
      const viewportH = window.innerHeight || document.documentElement.clientHeight || 1
      const selectors = [
        'textarea',
        'input',
        '[contenteditable="true"]',
        '[contenteditable=""]',
        '[contenteditable="plaintext-only"]',
        '[role="textbox"]',
        '[data-slate-editor="true"]',
        '.ProseMirror',
        '[placeholder*="消息"]',
        '[placeholder*="私信"]',
        '[placeholder*="发送"]',
        '[aria-label*="消息"]',
        '[aria-label*="私信"]',
        'div',
      ].join(',')
      const candidates = Array.from(document.querySelectorAll<HTMLElement>(selectors))
        .map((el, index) => {
          const rect = el.getBoundingClientRect()
          const text = `${el.getAttribute('placeholder') || ''} ${el.getAttribute('aria-label') || ''} ${el.innerText || el.textContent || ''}`.replace(/\s+/g, '')
          const editable = el instanceof HTMLInputElement ||
            el instanceof HTMLTextAreaElement ||
            el.isContentEditable ||
            (el.getAttribute('role') || '').toLowerCase() === 'textbox' ||
            el.getAttribute('data-slate-editor') === 'true' ||
            el.classList.contains('ProseMirror')
          let score = 0
          if (editable) score += 200
          if (/消息|私信|发送|请输入|聊/u.test(text)) score += 160
          if (rect.left >= viewportW * 0.52) score += 90
          if (rect.top >= viewportH * 0.70) score += 90
          if (rect.width >= 240 && rect.height >= 28 && rect.height <= 160) score += 70
          if (text.includes('搜索') || text.includes('关闭会话') || text.includes('回关')) score -= 300
          return { rect, index, score }
        })
        .filter(item => item.rect.width > 0 && item.rect.height > 0)
        .filter(item => item.rect.left >= viewportW * 0.52)
        .filter(item => item.rect.top >= viewportH * 0.55)
        .filter(item => item.score > 0)
        .sort((a, b) => b.score - a.score || b.rect.top - a.rect.top || a.index - b.index)
      const best = candidates[0]
      if (best) {
        return {
          x: best.rect.left + Math.min(best.rect.width * 0.5, best.rect.width - 24),
          y: best.rect.top + Math.min(best.rect.height * 0.5, best.rect.height - 16),
        }
      }
      return { x: viewportW * 0.84, y: viewportH - 42 }
    },
  })
  const point = result?.result as { x?: number; y?: number } | null | undefined
  return typeof point?.x === 'number' && typeof point?.y === 'number' ? { x: point.x, y: point.y } : null
}

async function draftVisibleInPage(tabId: number, text: string): Promise<boolean> {
  const [result] = await chrome.scripting.executeScript({
    target: { tabId, allFrames: false },
    func: (draft: string) => {
      const clean = (value: string) => String(value || '').replace(/\s+/g, '')
      const wanted = clean(draft)
      const editableText = Array.from(document.querySelectorAll<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror'))
        .map(el => {
          if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return el.value || ''
          return el.innerText || el.textContent || ''
        })
        .join('\n')
      return clean(editableText).includes(wanted) || clean(document.body?.innerText || document.body?.textContent || '').includes(wanted)
    },
    args: [text],
  })
  return result?.result === true
}

async function sleep(ms: number): Promise<void> {
  await new Promise<void>(resolve => setTimeout(resolve, Math.max(0, ms)))
}
