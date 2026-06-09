import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { ClickProfileActionParams } from '../types'

export interface ClickProfileActionHandlerDeps {
  chrome?: typeof globalThis.chrome
}

export const clickProfileActionHandler = (
  deps: ClickProfileActionHandlerDeps = {},
): ActionHandler<ClickProfileActionParams> => {
  return async (tabId, params, _deadlineMs) => {
    const labels = Array.isArray(params?.labels)
      ? params.labels.map(label => String(label || '').trim()).filter(Boolean)
      : []
    if (labels.length === 0) {
      throw new ActionFailureError('HANDLER_ERROR', 'click_profile_action labels are required', false)
    }
    const chromeApi = deps.chrome ?? globalThis.chrome
    if (!chromeApi?.scripting?.executeScript) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.scripting.executeScript is unavailable', true)
    }

    const results = await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: clickProfileActionInPage,
      args: [labels],
    })
    const payload = results?.[0]?.result as
      | { ok?: boolean; label?: string; reason?: string }
      | undefined
    if (payload?.ok !== true) {
      throw new ActionFailureError(
        'GROUNDING_AMBIGUOUS',
        payload?.reason || `profile action not found: ${labels.join('/')}`,
        false,
      )
    }
    return {
      ok: true,
      elapsed_ms: 0,
      payload: {
        label: payload.label || labels[0],
      },
    }
  }
}

function clickProfileActionInPage(labels: string[]): { ok: boolean; label?: string; reason?: string } {
  const wanted = labels.map(normalize).filter(Boolean)
  if (wanted.length === 0) return { ok: false, reason: 'empty_labels' }
  const viewportW = Math.max(1, window.innerWidth || document.documentElement.clientWidth || 1)
  const viewportH = Math.max(1, window.innerHeight || document.documentElement.clientHeight || 1)
  const minContentX = Math.max(180, viewportW * 0.16)
  const maxY = Math.max(360, viewportH * 0.72)
  const selectors = [
    'button',
    '[role="button"]',
    'a',
    'div[tabindex]',
    'span[tabindex]',
  ].join(',')
  const candidates = Array.from(document.querySelectorAll<HTMLElement>(selectors))
    .map((el, index) => {
      const rect = el.getBoundingClientRect()
      const text = normalize(el.innerText || el.textContent || el.getAttribute('aria-label') || el.title || '')
      return { el, index, rect, text }
    })
    .filter(item => item.text && wanted.some(label => item.text.includes(label)))
    .filter(item => item.rect.width > 0 && item.rect.height > 0)
    .filter(item => item.rect.left >= minContentX)
    .filter(item => item.rect.top >= 70 && item.rect.top <= maxY)
    .sort((a, b) => score(b) - score(a) || a.index - b.index)

  const best = candidates[0]
  if (!best) return { ok: false, reason: `no_profile_action:${wanted.join('/')}` }
  best.el.scrollIntoView({ block: 'center', inline: 'center' })
  best.el.click()
  return { ok: true, label: best.text }

  function score(item: { rect: DOMRect; text: string; el: HTMLElement }): number {
    let value = 0
    const role = (item.el.getAttribute('role') || item.el.tagName || '').toLowerCase()
    if (role.includes('button')) value += 100
    if (wanted.some(label => normalize(item.text) === label)) value += 80
    if (item.rect.width >= 42 && item.rect.width <= 180 && item.rect.height >= 22 && item.rect.height <= 64) {
      value += 50
    }
    if (item.text.includes('已关注') || item.text.includes('互相关注')) value += 40
    return value
  }

  function normalize(text: string): string {
    return String(text || '').replace(/\s+/g, '').trim()
  }
}
