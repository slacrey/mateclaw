import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { OpenAuthorFromCommentParams } from '../types'

export interface OpenAuthorFromCommentHandlerDeps {
  chrome?: typeof globalThis.chrome
}

export const openAuthorFromCommentHandler = (
  deps: OpenAuthorFromCommentHandlerDeps = {},
): ActionHandler<OpenAuthorFromCommentParams> => {
  return async (tabId, params, _deadlineMs) => {
    if (!params || typeof params.commentText !== 'string' || params.commentText.trim().length === 0) {
      throw new ActionFailureError('HANDLER_ERROR', 'open_author_from_comment params were malformed', false)
    }
    const chromeApi = deps.chrome ?? globalThis.chrome
    if (!chromeApi?.scripting?.executeScript) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.scripting.executeScript is unavailable', true)
    }

    const directHref = normalizeProfileHref(params.authorProfileUrl ?? '')
    let payload: { ok?: boolean; href?: string; author?: string; reason?: string } | undefined
    if (directHref) {
      payload = { ok: true, href: directHref, author: params.authorName ?? '' }
    } else {
      const results = await chromeApi.scripting.executeScript({
        target: { tabId, allFrames: false },
        func: openAuthorFromCommentInPage,
        args: [params.commentText, params.authorName ?? ''],
      })
      payload = results?.[0]?.result as
        | { ok?: boolean; href?: string; author?: string; reason?: string }
        | undefined
    }
    if (payload?.ok !== true || !payload.href) {
      throw new ActionFailureError(
        'GROUNDING_AMBIGUOUS',
        payload?.reason || 'author profile link not found for matched comment',
        false,
      )
    }
    if (!chromeApi.tabs?.create) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.tabs.create is unavailable', true)
    }
    const created = await chromeApi.tabs.create({
      url: payload.href,
      active: true,
      openerTabId: tabId,
    })
    return {
      ok: true,
      elapsed_ms: 0,
      payload: {
        href: payload.href,
        author: payload.author || params.authorName || '',
        tabId: created?.id ?? null,
      },
    }
  }
}

function normalizeProfileHref(value: string): string {
  const raw = String(value || '').trim()
  if (!raw) return ''
  try {
    const href = new URL(raw, 'https://www.douyin.com').href
    return looksLikeProfileHref(href) ? href : ''
  } catch {
    return ''
  }
}

function looksLikeProfileHref(href: string): boolean {
  const lower = String(href || '').toLowerCase()
  return lower.includes('douyin.com') && lower.includes('/user')
}

function openAuthorFromCommentInPage(
  commentText: string,
  authorName: string,
): { ok: boolean; href?: string; author?: string; reason?: string } {
  const target = normalize(commentText).replace(/[。.!！]+$/u, '')
  const authorHint = normalize(authorName)
  if (!target) return { ok: false, reason: 'empty_comment_text' }

  const candidates = Array.from(document.querySelectorAll<HTMLElement>('div, article, li, section, p, span'))
    .map((el, index) => ({
      el,
      index,
      text: normalize(el.innerText || el.textContent || ''),
      rect: el.getBoundingClientRect(),
    }))
    .filter(item => item.text.includes(target) || item.text.replace(/[。.!！]/gu, '').includes(target))
    .filter(item => item.rect.width > 0 && item.rect.height > 0)
    .sort((a, b) => {
      const area = a.rect.width * a.rect.height - b.rect.width * b.rect.height
      if (Math.abs(area) > 1) return area
      return a.index - b.index
    })

  for (const item of candidates.slice(0, 20)) {
    const container = nearestContainerWithUserLink(item.el, target)
    if (!container) continue
    const links = Array.from(container.querySelectorAll<HTMLAnchorElement>('a[href]'))
      .map(a => ({
        a,
        href: absoluteHref(a.getAttribute('href') || a.href),
        text: normalize(a.innerText || a.textContent || ''),
      }))
      .filter(link => looksLikeProfileHref(link.href))
    const preferred = links.find(link => authorHint && link.text.includes(authorHint)) ?? links[0]
    if (!preferred) continue
    return { ok: true, href: preferred.href, author: preferred.text || authorHint }
  }

  return { ok: false, reason: `no_profile_link_for_comment:${target.slice(0, 40)}` }

  function nearestContainerWithUserLink(seed: HTMLElement, needle: string): HTMLElement | null {
    let current: HTMLElement | null = seed
    let depth = 0
    while (current && depth++ < 10) {
      const text = normalize(current.innerText || current.textContent || '')
      const hasTarget = text.includes(needle) || text.replace(/[。.!！]/gu, '').includes(needle)
      const hasProfile = Array.from(current.querySelectorAll<HTMLAnchorElement>('a[href]'))
        .some(a => looksLikeProfileHref(absoluteHref(a.getAttribute('href') || a.href)))
      if (hasTarget && hasProfile) return current
      current = current.parentElement
    }
    return null
  }

  function normalize(text: string): string {
    return String(text || '').replace(/\s+/g, ' ').trim()
  }

  function absoluteHref(href: string): string {
    try {
      return new URL(href, location.href).href
    } catch {
      return href
    }
  }

}
