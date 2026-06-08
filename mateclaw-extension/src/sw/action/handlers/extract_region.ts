import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { ExtractRegionParams } from '../types'
import type { RegionRegistry, RuntimeRegion } from '../../../runtime/region-registry'

export interface ExtractRegionHandlerDeps {
  regions: RegionRegistry
  chrome?: typeof globalThis.chrome
}

interface ExtractedRegionItem {
  text: string
  role?: string
  tag: string
  href?: string
  bbox: { x: number; y: number; width: number; height: number }
  itemType?: string
  author?: string
  hrefs?: string[]
}

export const extractRegionHandler = (deps: ExtractRegionHandlerDeps): ActionHandler<ExtractRegionParams> => {
  return async (tabId, params, _deadlineMs) => {
    const parsed = parseExtractRegionParams(params)
    if (!parsed) {
      throw new ActionFailureError(
        'HANDLER_ERROR',
        'extract_region params were malformed',
        false,
      )
    }

    const region = deps.regions.get(tabId, parsed.regionKey)
    if (!region) {
      throw new ActionFailureError(
        'GROUNDING_AMBIGUOUS',
        `no runtime region registered for key '${parsed.regionKey}' in tab ${tabId}`,
        true,
      )
    }

    const chromeApi = deps.chrome ?? globalThis.chrome
    if (!chromeApi?.scripting?.executeScript) {
      throw new ActionFailureError(
        'HANDLER_ERROR',
        'chrome.scripting.executeScript is unavailable',
        true,
      )
    }

    const results = await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: extractRegionInPage,
      args: [region, parsed.maxItems],
    })
    const items = Array.isArray(results?.[0]?.result) ? results[0].result : []

    return {
      ok: true,
      elapsed_ms: 0,
      payload: {
        regionKey: parsed.regionKey,
        items,
      },
    }
  }
}

function parseExtractRegionParams(params: ExtractRegionParams): { regionKey: string; maxItems: number } | null {
  if (!params || typeof params.regionKey !== 'string' || params.regionKey.trim().length === 0) return null
  if (params.maxItems !== undefined && (!Number.isInteger(params.maxItems) || params.maxItems < 1)) return null
  return {
    regionKey: params.regionKey,
    maxItems: Math.min(params.maxItems ?? 80, 500),
  }
}

function extractRegionInPage(region: RuntimeRegion, maxItems: number): ExtractedRegionItem[] {
  const regionRect = {
    left: region.x,
    top: region.y,
    right: region.x + region.width,
    bottom: region.y + region.height,
  }
  const max = Math.min(Math.max(Math.floor(maxItems || 80), 1), 500)

  if (region.key === 'douyin.search_results') {
    return extractDouyinSearchResults(regionRect, max)
  }
  if (region.key === 'douyin.comments') {
    return extractDouyinComments(regionRect, max)
  }
  if (region.key === 'douyin.dm') {
    return extractDouyinDm(regionRect, max)
  }

  const selectors = [
    '[data-e2e*="comment" i]',
    '[class*="comment" i]',
    '[class*="reply" i]',
    'article',
    'li',
    'a',
    'button',
    '[role]',
    'p',
    'span',
    'div',
  ].join(',')

  const all = Array.from(document.querySelectorAll<HTMLElement>(selectors))
  const visible = all
    .map(el => ({ el, rect: el.getBoundingClientRect(), text: cleanText(el.innerText || el.textContent || '') }))
    .filter(entry => entry.text && intersects(entry.rect, regionRect) && isUsefulSize(entry.rect))

  const commentContainers = visible
    .filter(entry => isLikelyDouyinComment(entry.el, entry.text))
    .filter((entry, index, arr) => !arr.some((other, otherIndex) => (
      otherIndex !== index &&
      other.el !== entry.el &&
      other.el.contains(entry.el) &&
      similarText(other.text, entry.text)
    )))

  const items: ExtractedRegionItem[] = []
  const used = new Set<Element>()
  for (const entry of commentContainers) {
    if (items.length >= max) break
    used.add(entry.el)
    const links = Array.from(entry.el.querySelectorAll<HTMLAnchorElement>('a[href]'))
      .map(a => a.href)
      .filter(Boolean)
    items.push({
      text: entry.text,
      role: roleOf(entry.el),
      tag: entry.el.tagName.toLowerCase(),
      href: nearestHref(entry.el),
      bbox: bboxOf(entry.rect),
      itemType: 'douyin_comment',
      author: guessAuthor(entry.el),
      hrefs: Array.from(new Set(links)).slice(0, 8),
    })
  }

  for (const entry of visible) {
    if (items.length >= max) break
    if (used.has(entry.el)) continue
    if (Array.from(used).some(parent => parent.contains(entry.el))) continue
    if (entry.text.length < 2) continue
    if (visible.some(other => other.el !== entry.el && entry.el.contains(other.el) && similarText(entry.text, other.text))) {
      continue
    }
    items.push({
      text: entry.text,
      role: roleOf(entry.el),
      tag: entry.el.tagName.toLowerCase(),
      href: nearestHref(entry.el),
      bbox: bboxOf(entry.rect),
    })
  }

  return items

  function cleanText(text: string): string {
    return text.replace(/\s+/g, ' ').trim()
  }

  function intersects(rect: DOMRect, clip: { left: number; top: number; right: number; bottom: number }): boolean {
    return rect.width > 0 &&
      rect.height > 0 &&
      rect.right >= clip.left &&
      rect.left <= clip.right &&
      rect.bottom >= clip.top &&
      rect.top <= clip.bottom
  }

  function isUsefulSize(rect: DOMRect): boolean {
    return rect.width >= 4 && rect.height >= 4
  }

  function similarText(a: string, b: string): boolean {
    return a === b || a.includes(b) || b.includes(a)
  }

  function roleOf(el: HTMLElement): string | undefined {
    return el.getAttribute('role') || undefined
  }

  function nearestHref(el: HTMLElement): string | undefined {
    if (el instanceof HTMLAnchorElement && el.href) return el.href
    return el.closest<HTMLAnchorElement>('a[href]')?.href
  }

  function bboxOf(rect: DOMRect): ExtractedRegionItem['bbox'] {
    return {
      x: Math.round(rect.x),
      y: Math.round(rect.y),
      width: Math.round(rect.width),
      height: Math.round(rect.height),
    }
  }

  function isLikelyDouyinComment(el: HTMLElement, text: string): boolean {
    const marker = `${el.getAttribute('data-e2e') || ''} ${el.className || ''} ${el.getAttribute('aria-label') || ''}`.toLowerCase()
    if (marker.includes('comment')) return true
    if (marker.includes('reply') && text.length > 8) return true
    return /回复|展开|点赞|小时前|分钟前|昨天/.test(text) && text.length > 12
  }

  function guessAuthor(el: HTMLElement): string | undefined {
    const authorEl = el.querySelector<HTMLElement>(
      '[data-e2e*="user" i], [class*="author" i], [class*="name" i], a[href*="user"], a[href*="douyin.com/user"]',
    )
    const text = cleanText(authorEl?.innerText || authorEl?.textContent || '')
    if (!text) return undefined
    return text.slice(0, 80)
  }
}

function extractDouyinDm(
  _regionRect: { left: number; top: number; right: number; bottom: number },
  max: number,
): ExtractedRegionItem[] {
  const selectors = [
    'textarea',
    'input',
    '[contenteditable="true"]',
    '[contenteditable=""]',
    '[role="textbox"]',
    '[placeholder*="消息"]',
    '[placeholder*="私信"]',
    '[placeholder*="发送"]',
  ].join(',')
  const items: ExtractedRegionItem[] = []
  const seen = new Set<Element>()
  const add = (el: Element | null | undefined, itemType: string) => {
    if (!(el instanceof HTMLElement) || seen.has(el)) return
    seen.add(el)
    const rect = el.getBoundingClientRect()
    const text = editableText(el)
    if (!text && itemType !== 'active_editable') return
    items.push({
      text,
      role: el.getAttribute('role') || undefined,
      tag: el.tagName.toLowerCase(),
      bbox: {
        x: Math.round(rect.x),
        y: Math.round(rect.y),
        width: Math.round(rect.width),
        height: Math.round(rect.height),
      },
      itemType,
    })
  }
  add(document.activeElement, 'active_editable')
  for (const el of Array.from(document.querySelectorAll<HTMLElement>(selectors)).slice(0, Math.max(1, max))) {
    add(el, 'dm_editable')
  }
  return items.slice(0, max)
}

function editableText(el: HTMLElement): string {
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    return cleanText(el.value || el.getAttribute('value') || '')
  }
  return cleanText(el.innerText || el.textContent || '')
}

function extractDouyinComments(
  regionRect: { left: number; top: number; right: number; bottom: number },
  max: number,
): ExtractedRegionItem[] {
  const commentListCandidates = commentListItemCandidates()
  const selectors = [
    '[data-e2e*="comment" i]',
    '[class*="comment" i]',
    '[class*="reply" i]',
    'article',
    'li',
  ].join(',')

  const unique: CommentCandidate[] = []
  const count = declaredCommentCountCandidate(regionRect)
  for (const candidate of commentListCandidates) {
    if (unique.some(existing => sameCommentCandidate(existing, candidate))) continue
    unique.push(candidate)
  }
  if (commentListCandidates.length === 0) {
    const candidates = Array.from(document.querySelectorAll<HTMLElement>(selectors))
      .map((el, domIndex) => toCommentCandidate(el, domIndex))
      .filter((candidate): candidate is CommentCandidate => candidate !== null)
    for (const candidate of candidates) {
      if (unique.some(existing => sameCommentCandidate(existing, candidate))) continue
      unique.push(candidate)
    }
    for (const candidate of looseVisibleTextCommentCandidates(unique.length)) {
      if (unique.some(existing => sameCommentCandidate(existing, candidate))) continue
      unique.push(candidate)
    }
  }

  const comments = unique
    .sort((a, b) => {
      const top = a.rect.top - b.rect.top
      if (Math.abs(top) > 6) return top
      const left = a.rect.left - b.rect.left
      if (Math.abs(left) > 6) return left
      return a.domIndex - b.domIndex
    })
    .slice(0, max)
    .map(candidate => ({
      text: candidate.text,
      role: candidate.role,
      tag: candidate.tag,
      href: candidate.profileHref,
      bbox: bboxOf(candidate.rect),
      itemType: 'douyin_comment',
      author: candidate.author,
      hrefs: candidate.hrefs,
    }))
  return count ? [count, ...comments] : comments

  interface CommentCandidate {
    el: HTMLElement
    rect: DOMRect
    text: string
    author: string
    profileHref?: string
    hrefs: string[]
    role?: string
    tag: string
    domIndex: number
  }

  function commentListItemCandidates(): CommentCandidate[] {
    const listSet = new Set<HTMLElement>()
    for (const selector of [
      '#merge-all-comment-container [data-e2e="comment-list"]',
      '[data-e2e="comment-list"]',
    ]) {
      for (const el of Array.from(document.querySelectorAll<HTMLElement>(selector))) {
        listSet.add(el)
      }
    }
    const lists = Array.from(listSet)
      .map(el => ({ el, rect: el.getBoundingClientRect() }))
      .filter(entry => isUsefulTextRect(entry.rect) && intersects(entry.rect, regionRect))
      .sort((a, b) => {
        const itemDiff = b.el.querySelectorAll('[data-e2e="comment-item"]').length - a.el.querySelectorAll('[data-e2e="comment-item"]').length
        if (itemDiff !== 0) return itemDiff
        return overlapAreaWithClip(b.rect, regionRect) - overlapAreaWithClip(a.rect, regionRect)
      })
    const list = lists[0]?.el
    if (!list) return []

    return Array.from(list.querySelectorAll<HTMLElement>('[data-e2e="comment-item"]'))
      .map((item, domIndex) => toCommentListItemCandidate(item, domIndex))
      .filter((candidate): candidate is CommentCandidate => candidate !== null)
  }

  function toCommentListItemCandidate(item: HTMLElement, domIndex: number): CommentCandidate | null {
    if (item.closest('[data-e2e="video-comment-more"], .comment-reply-expand-btn')) return null
    const rect = item.getBoundingClientRect()
    if (!isUsefulCommentListItemRect(rect) || !intersects(rect, regionRect)) return null

    const authorEl = findCommentItemAuthorElement(item)
    const author = normalizeAuthor(cleanText(authorEl?.innerText || authorEl?.textContent || ''))
    const hrefs = Array.from(item.querySelectorAll<HTMLAnchorElement>('a[href]'))
      .map(a => absoluteHref(a.getAttribute('href') || a.href))
      .filter((href): href is string => Boolean(href))
    const profileHref = Array.from(new Set(hrefs)).find(looksLikeDouyinProfileHref)
    const textEl = findCommentItemBodyElement(item)
    const text = cleanCommentBody(textFromElementWithoutControls(textEl ?? item), author)
    if (!isStructuredCommentBody(text, author)) return null

    return {
      el: item,
      rect,
      text,
      author,
      profileHref,
      hrefs: Array.from(new Set(hrefs)).slice(0, 8),
      role: item.getAttribute('role') || undefined,
      tag: item.tagName.toLowerCase(),
      domIndex,
    }
  }

  function findCommentItemAuthorElement(item: HTMLElement): HTMLElement | null {
    const links = Array.from(item.querySelectorAll<HTMLAnchorElement>('a[href*="/user/"], a[href*="douyin.com/user"]'))
    return links
      .filter(link => link.closest('[data-e2e="comment-item"]') === item)
      .filter(link => !link.closest('[data-e2e="video-comment-more"]'))
      .map(link => ({ link, text: normalizeAuthor(cleanText(link.innerText || link.textContent || '')), rect: link.getBoundingClientRect() }))
      .filter(entry => isLikelyAuthorText(entry.text) || looksLikeDouyinProfileHref(entry.link.href))
      .sort((a, b) => {
        const titleA = a.link.querySelector('[data-click-from="title"]') || a.link.getAttribute('data-click-from') === 'title'
        const titleB = b.link.querySelector('[data-click-from="title"]') || b.link.getAttribute('data-click-from') === 'title'
        if (Boolean(titleA) !== Boolean(titleB)) return titleA ? -1 : 1
        const textA = a.text ? 0 : 1
        const textB = b.text ? 0 : 1
        if (textA !== textB) return textA - textB
        const top = a.rect.top - b.rect.top
        if (Math.abs(top) > 6) return top
        return a.rect.left - b.rect.left
      })[0]?.link ?? null
  }

  function findCommentItemBodyElement(item: HTMLElement): HTMLElement | null {
    const preferred = Array.from(item.querySelectorAll<HTMLElement>('.Sbe6bqNb, .LqTo7UJT, .LvAtyU_f, [data-e2e*="comment-text" i], [data-e2e*="content" i]'))
      .filter(el => el.closest('[data-e2e="comment-item"]') === item)
      .filter(el => !el.closest('[data-e2e="video-comment-more"], .comment-reply-expand-btn, .comment-item-stats-container'))
      .map(el => ({ el, rect: el.getBoundingClientRect(), text: cleanCommentBody(textFromElementWithoutControls(el), '') }))
      .filter(entry => isUsefulTextRect(entry.rect) && isStructuredCommentBody(entry.text, ''))
      .sort((a, b) => scoreCommentTextElement(b.el, b.text, b.rect) - scoreCommentTextElement(a.el, a.text, a.rect))[0]?.el
    if (preferred) return preferred
    return findCommentTextElement(item, findCommentItemAuthorElement(item), normalizeAuthor(cleanText(findCommentItemAuthorElement(item)?.innerText || '')))
  }

  function declaredCommentCountCandidate(
    clip: { left: number; top: number; right: number; bottom: number },
  ): ExtractedRegionItem | null {
    const panelRoot = Array.from(document.querySelectorAll<HTMLElement>('#merge-all-comment-container'))
      .map(el => ({ el, rect: el.getBoundingClientRect() }))
      .filter(entry => entry.rect.width > 0 && entry.rect.height > 0)
      .filter(entry => intersects(entry.rect, clip) || containsClip(entry.rect, clip))
      .sort((a, b) => overlapAreaWithClip(b.rect, clip) - overlapAreaWithClip(a.rect, clip))[0]?.el ?? null
    const searchRoot: ParentNode = panelRoot ?? document
    const all = Array.from(searchRoot.querySelectorAll<HTMLElement>('button, [role], span, div, h1, h2, h3'))
      .map(el => ({ el, rect: el.getBoundingClientRect(), text: cleanText(el.innerText || el.textContent || '') }))
      .filter(entry => entry.text && entry.rect.width > 0 && entry.rect.height > 0)
      .filter(entry => {
        const centerX = entry.rect.left + entry.rect.width / 2
        return centerX >= clip.left && centerX <= clip.right && entry.rect.top <= clip.top + 180
      })
      .filter(entry => {
        const compact = cleanText(entry.text).replace(/\s+/g, '')
        return /^(全部)?评论[（(]?(?:\d|[,.万wWkK千])/.test(compact)
          || /^\d[\d,.]*(?:万|w|W|k|K|千)?条?评论$/.test(compact)
      })
      .sort((a, b) => a.rect.top - b.rect.top || a.rect.left - b.rect.left)
    for (const entry of all) {
      const count = parseDeclaredCommentCount(entry.text)
      if (count > 0) {
        return {
          text: String(count),
          role: entry.el.getAttribute('role') || undefined,
          tag: entry.el.tagName.toLowerCase(),
          bbox: bboxOf(entry.rect),
          itemType: 'comment_count',
        }
      }
    }
    return null
  }

  function parseDeclaredCommentCount(text: string): number {
    const compact = cleanText(text).replace(/\s+/g, '')
    const countNumber = '(?:\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)'
    const patterns = [
      new RegExp(`^全部评论[（(]?(${countNumber})(万|w|W|k|K|千)?[）)]?$`, 'u'),
      new RegExp(`^评论[（(]?(${countNumber})(万|w|W|k|K|千)?[）)]?$`, 'u'),
      new RegExp(`^(${countNumber})(万|w|W|k|K|千)?条?评论$`, 'u'),
    ]
    let best = 0
    for (const pattern of patterns) {
      let match: RegExpExecArray | null
      while ((match = pattern.exec(compact)) !== null) {
        best = Math.max(best, parseCount(match[1] || '', match[2] || ''))
        if (!pattern.global) break
      }
    }
    return best
  }

  function parseCount(number: string, unit: string): number {
    const value = Number.parseFloat(number.replace(/,/g, ''))
    if (!Number.isFinite(value)) return 0
    const normalized = unit.toLowerCase()
    const multiplier = unit === '万' || normalized === 'w'
      ? 10_000
      : unit === '千' || normalized === 'k'
        ? 1_000
        : 1
    return Math.round(value * multiplier)
  }

  function findAuthorElementLoose(container: HTMLElement, textEl: HTMLElement): HTMLElement | null {
    const selectors = [
      'a[href*="/user/"]',
      'a[href*="douyin.com/user"]',
      '[data-e2e*="user" i]',
      '[class*="author" i]',
      '[class*="name" i]',
    ].join(',')
    return Array.from(container.querySelectorAll<HTMLElement>(selectors))
      .filter(el => el !== textEl && !textEl.contains(el))
      .filter(el => {
        const text = normalizeAuthor(cleanText(el.innerText || el.textContent || ''))
        return isLikelyAuthorText(text) || looksLikeDouyinProfileHref((el as HTMLAnchorElement).href)
      })
      .sort((a, b) => {
        const ar = a.getBoundingClientRect()
        const br = b.getBoundingClientRect()
        const top = ar.top - br.top
        if (Math.abs(top) > 6) return top
        return ar.left - br.left
      })[0] ?? null
  }

  function toCommentCandidate(el: HTMLElement, domIndex: number): CommentCandidate | null {
    if (isIgnoredCommentControlElement(el)) return null
    const rect = el.getBoundingClientRect()
    if (!isUsefulCommentContainerRect(rect) || !mostlyInside(rect, regionRect)) return null

    const authorEl = findAuthorElement(el)
    const author = normalizeAuthor(cleanText(authorEl?.innerText || authorEl?.textContent || ''))
    const hrefs = Array.from(el.querySelectorAll<HTMLAnchorElement>('a[href]'))
      .map(a => absoluteHref(a.getAttribute('href') || a.href))
      .filter((href): href is string => Boolean(href))
    const profileHref = Array.from(new Set(hrefs)).find(looksLikeDouyinProfileHref)

    const textEl = findCommentTextElement(el, authorEl, author)
    const text = cleanCommentBody(
      cleanText(textEl?.innerText || textEl?.textContent || deriveCommentText(el, author)),
      author,
    )
    if (!isLikelyCommentBody(text, author)) return null

    return {
      el,
      rect,
      text,
      author,
      profileHref,
      hrefs: Array.from(new Set(hrefs)).slice(0, 8),
      role: el.getAttribute('role') || undefined,
      tag: el.tagName.toLowerCase(),
      domIndex,
    }
  }

  function looseVisibleTextCommentCandidates(domIndexOffset: number): CommentCandidate[] {
    const textElements = Array.from(document.querySelectorAll<HTMLElement>('span, p, div, a'))
      .map((el, index) => ({
        el,
        index,
        rect: el.getBoundingClientRect(),
        text: cleanText(el.innerText || el.textContent || ''),
      }))
      .filter(entry => mostlyInside(entry.rect, regionRect))
      .filter(entry => isUsefulTextRect(entry.rect))
      .filter(entry => isLikelyCommentBody(entry.text, ''))
      .filter(entry => !hasUsefulTextChild(entry.el, entry.text))
      .sort((a, b) => {
        const top = a.rect.top - b.rect.top
        if (Math.abs(top) > 6) return top
        return a.rect.left - b.rect.left
      })

    const out: CommentCandidate[] = []
    for (const entry of textElements) {
      const container = nearestLooseCommentContainer(entry.el)
      const authorEl = findAuthorElementLoose(container, entry.el)
      const author = normalizeAuthor(cleanText(authorEl?.innerText || authorEl?.textContent || ''))
      const hrefs = Array.from(container.querySelectorAll<HTMLAnchorElement>('a[href]'))
        .map(a => absoluteHref(a.getAttribute('href') || a.href))
        .filter((href): href is string => Boolean(href))
      const profileHref = Array.from(new Set(hrefs)).find(looksLikeDouyinProfileHref)
      const text = cleanCommentBody(entry.text, author)
      if (!isLikelyCommentBody(text, author)) continue
      const rect = container.getBoundingClientRect()
      if (!isUsefulCommentContainerRect(rect) && !mostlyInside(entry.rect, regionRect)) continue
      out.push({
        el: container,
        rect: isUsefulCommentContainerRect(rect) ? rect : entry.rect,
        text,
        author,
        profileHref,
        hrefs: Array.from(new Set(hrefs)).slice(0, 8),
        role: container.getAttribute('role') || undefined,
        tag: container.tagName.toLowerCase(),
        domIndex: domIndexOffset + entry.index,
      })
    }
    return out
  }

  function hasUsefulTextChild(el: HTMLElement, ownText: string): boolean {
    return Array.from(el.children).some(child => {
      if (!(child instanceof HTMLElement)) return false
      const childText = cleanText(child.innerText || child.textContent || '')
      if (!childText || childText === ownText) return false
      return ownText.includes(childText) && isLikelyCommentBody(childText, '')
    })
  }

  function nearestLooseCommentContainer(seed: HTMLElement): HTMLElement {
    let current: HTMLElement | null = seed
    let best = seed
    let depth = 0
    while (current && depth++ < 7) {
      const rect = current.getBoundingClientRect()
      const text = cleanText(current.innerText || current.textContent || '')
      const links = current.querySelectorAll('a[href*="/user"], a[href*="douyin.com/user"]').length
      const marker = `${current.getAttribute('data-e2e') || ''} ${current.className || ''}`.toLowerCase()
      if (mostlyInside(rect, regionRect)
        && isUsefulCommentContainerRect(rect)
        && (links > 0 || marker.includes('comment') || marker.includes('reply'))
        && text.includes(cleanText(seed.innerText || seed.textContent || ''))) {
        best = current
      }
      current = current.parentElement
    }
    return best
  }

  function findAuthorElement(container: HTMLElement): HTMLElement | null {
    const selectors = [
      'a[href*="/user/"]',
      'a[href*="douyin.com/user"]',
      '[data-e2e*="user" i]',
      '[class*="author" i]',
      '[class*="name" i]',
    ].join(',')
    return Array.from(container.querySelectorAll<HTMLElement>(selectors))
      .filter(el => mostlyInside(el.getBoundingClientRect(), regionRect))
      .filter(el => {
        const text = normalizeAuthor(cleanText(el.innerText || el.textContent || ''))
        return isLikelyAuthorText(text)
      })
      .sort((a, b) => {
        const titleA = a.matches('[data-click-from="title"]') || Boolean(a.querySelector('[data-click-from="title"]'))
        const titleB = b.matches('[data-click-from="title"]') || Boolean(b.querySelector('[data-click-from="title"]'))
        if (titleA !== titleB) return titleA ? -1 : 1
        const ar = a.getBoundingClientRect()
        const br = b.getBoundingClientRect()
        const top = ar.top - br.top
        if (Math.abs(top) > 6) return top
        return ar.left - br.left
      })[0] ?? null
  }

  function findCommentTextElement(
    container: HTMLElement,
    authorEl: HTMLElement | null,
    author: string,
  ): HTMLElement | null {
    const selectors = [
      '.Sbe6bqNb',
      '.LqTo7UJT',
      '.LvAtyU_f',
      '[data-e2e*="comment-text" i]',
      '[data-e2e*="content" i]',
      '[class*="comment-text" i]',
      '[class*="content" i]',
      '[class*="text" i]',
      'p',
      'span',
      'div',
    ].join(',')
    return Array.from(container.querySelectorAll<HTMLElement>(selectors))
      .filter(el => el !== authorEl && !(authorEl?.contains(el)))
      .filter(el => !isIgnoredCommentControlElement(el))
      .map(el => ({
        el,
        rect: el.getBoundingClientRect(),
        text: cleanCommentBody(cleanText(el.innerText || el.textContent || ''), author),
      }))
      .filter(entry => isUsefulTextRect(entry.rect) && mostlyInside(entry.rect, regionRect))
      .filter(entry => isLikelyCommentBody(entry.text, author))
      .sort((a, b) => scoreCommentTextElement(b.el, b.text, b.rect) - scoreCommentTextElement(a.el, a.text, a.rect))[0]?.el ?? null
  }

  function deriveCommentText(container: HTMLElement, author: string): string {
    const raw = container.innerText || container.textContent || ''
    const lines = raw
      .split(/\n|\r| {2,}/)
      .map(line => cleanCommentBody(cleanText(line), author))
      .filter(line => isLikelyCommentBody(line, author))
    return lines[0] ?? ''
  }

  function scoreCommentTextElement(el: HTMLElement, text: string, rect: DOMRect): number {
    const marker = `${el.getAttribute('data-e2e') || ''} ${el.className || ''}`.toLowerCase()
    let score = 0
    if (el.matches('.Sbe6bqNb, .LqTo7UJT, .LvAtyU_f')) score += 100
    if (marker.includes('comment-text')) score += 80
    if (marker.includes('content') || marker.includes('text')) score += 40
    score += Math.min(80, text.length)
    score -= Math.round(Math.max(0, rect.height - 120) / 4)
    score -= Math.round(Math.max(0, rect.width - (regionRect.right - regionRect.left)) / 8)
    return score
  }

  function cleanCommentBody(text: string, author: string): string {
    let value = cleanText(text)
    const normalizedAuthor = normalizeAuthor(author)
    if (normalizedAuthor && normalizeAuthor(value).startsWith(normalizedAuthor)) {
      value = cleanText(value.slice(author.length))
    }
    return value
      .replace(/^(作者)?回复\s*/u, '')
      .replace(/\s*(作者回复过|作者)$/u, '')
      .replace(/\s*(回复|展开\d*条?回复|展开回复|点赞|分享)$/u, '')
      .trim()
  }

  function isLikelyCommentBody(text: string, author: string): boolean {
    const value = cleanText(text)
    if (value.length < 2 || value.length > 600) return false
    if (normalizeAuthor(value) === normalizeAuthor(author)) return false
    if (value.endsWith('头像') || value.includes('头像')) return false
    if (isCommentControlText(value)) return false
    return value.length > 8 || /[？?。！!，,]/u.test(value)
  }

  function isStructuredCommentBody(text: string, author: string): boolean {
    const value = cleanText(text)
    if (value.length < 1 || value.length > 600) return false
    if (normalizeAuthor(value) === normalizeAuthor(author)) return false
    if (value.endsWith('头像') || value.includes('头像')) return false
    return !isCommentControlText(value)
  }

  function isCommentControlText(text: string): boolean {
    const value = cleanText(text).replace(/\s+/g, '')
    if (!value) return true
    if (/^\d+(?:\.\d+)?([万wWkK千])?$/.test(value)) return true
    if (/^\d+条?回复$/.test(value)) return true
    if (/^展开\d*条?回复$/.test(value)) return true
    if (/^(评论|全部评论|详情|TA的作品|问AI|回复|展开|展开回复|点赞|分享|收藏|加载中|留下你的精彩评论吧|说点什么|发表评论|大家都在搜：?|StopAgent)$/.test(value)) {
      return true
    }
    return /^(刚刚|\d+分钟前|\d+小时前|昨天|\d+天前)$/.test(value)
  }

  function isLikelyAuthorText(text: string): boolean {
    const value = normalizeAuthor(text)
    if (!value || value.length > 60) return false
    if (value.endsWith('头像') || isCommentControlText(value) || isAuthorControlText(value)) return false
    if (/^\d+(?:\.\d+)?([万wWkK千])?$/.test(value)) return false
    return /[\p{L}\p{N}_-]/u.test(value)
  }

  function isAuthorControlText(text: string): boolean {
    const value = cleanText(text).replace(/\s+/g, '')
    return /^(关注|已关注|互相关注|回关|私信|发私信|回复|展开|展开回复|点赞|分享|收藏|评论)$/.test(value)
  }

  function normalizeAuthor(text: string): string {
    return cleanText(text)
      .replace(/头像$/u, '')
      .replace(/作者回复过$/u, '')
      .replace(/作者$/u, '')
      .trim()
  }

  function isIgnoredCommentControlElement(el: HTMLElement): boolean {
    return Boolean(el.closest('[data-e2e="video-comment-more"], .comment-reply-expand-btn, .comment-item-stats-container, .comment-input-container, .comment-header-close-btn'))
  }

  function textFromElementWithoutControls(el: HTMLElement): string {
    const clone = el.cloneNode(true) as HTMLElement
    for (const node of Array.from(clone.querySelectorAll([
      'svg',
      '[data-e2e="video-comment-more"]',
      '.comment-reply-expand-btn',
      '.comment-item-stats-container',
      '.comment-input-container',
      '.comment-header-close-btn',
      '.comment-item-tag',
      '.semi-tag',
      '[class*="stats" i]',
      '[class*="share" i]',
    ].join(',')))) {
      node.remove()
    }
    return cleanText(clone.textContent || '')
  }

  function sameCommentCandidate(a: CommentCandidate, b: CommentCandidate): boolean {
    if (normalizeAuthor(a.author) === normalizeAuthor(b.author) && cleanText(a.text) === cleanText(b.text)) {
      return true
    }
    const overlap = overlapArea(a.rect, b.rect)
    const minArea = Math.max(1, Math.min(area(a.rect), area(b.rect)))
    return overlap / minArea >= 0.78 && similarCommentText(a.text, b.text)
  }

  function similarCommentText(a: string, b: string): boolean {
    return a === b || a.includes(b) || b.includes(a)
  }

  function mostlyInside(rect: DOMRect, clip: { left: number; top: number; right: number; bottom: number }): boolean {
    if (rect.width <= 0 || rect.height <= 0) return false
    const overlap = overlapAreaWithClip(rect, clip)
    const elementArea = Math.max(1, rect.width * rect.height)
    const centerInside = rect.left + rect.width / 2 >= clip.left &&
      rect.left + rect.width / 2 <= clip.right &&
      rect.top + rect.height / 2 >= clip.top &&
      rect.top + rect.height / 2 <= clip.bottom
    return centerInside || overlap / elementArea >= 0.58
  }

  function containsClip(rect: DOMRect, clip: { left: number; top: number; right: number; bottom: number }): boolean {
    return rect.left <= clip.left + 24 &&
      rect.right >= clip.right - 24 &&
      rect.top <= clip.top + 80 &&
      rect.bottom >= clip.bottom - 80
  }

  function intersects(rect: DOMRect, clip: { left: number; top: number; right: number; bottom: number }): boolean {
    return rect.width > 0 &&
      rect.height > 0 &&
      rect.right >= clip.left &&
      rect.left <= clip.right &&
      rect.bottom >= clip.top &&
      rect.top <= clip.bottom
  }

  function isUsefulCommentContainerRect(rect: DOMRect): boolean {
    const regionWidth = regionRect.right - regionRect.left
    const regionHeight = regionRect.bottom - regionRect.top
    return rect.width >= 80 &&
      rect.height >= 28 &&
      rect.width <= Math.max(regionWidth * 1.08, 360) &&
      rect.height <= Math.max(regionHeight * 0.46, 220)
  }

  function isUsefulCommentListItemRect(rect: DOMRect): boolean {
    const regionWidth = regionRect.right - regionRect.left
    const regionHeight = regionRect.bottom - regionRect.top
    return rect.width >= 80 &&
      rect.height >= 28 &&
      rect.width <= Math.max(regionWidth * 1.18, 420) &&
      rect.height <= Math.max(regionHeight * 0.86, 520)
  }

  function isUsefulTextRect(rect: DOMRect): boolean {
    return rect.width >= 12 && rect.height >= 8
  }

  function cleanText(text: string): string {
    return text.replace(/\s+/g, ' ').trim()
  }

  function absoluteHref(href?: string): string | undefined {
    if (!href) return undefined
    try {
      return new URL(href, location.href).href
    } catch {
      return href
    }
  }

  function looksLikeDouyinProfileHref(href?: string): boolean {
    if (!href) return false
    const lower = href.toLowerCase()
    return lower.includes('douyin.com') && lower.includes('/user')
  }

  function bboxOf(rect: DOMRect): ExtractedRegionItem['bbox'] {
    return {
      x: Math.round(rect.x),
      y: Math.round(rect.y),
      width: Math.round(rect.width),
      height: Math.round(rect.height),
    }
  }

  function area(rect: DOMRect): number {
    return Math.max(0, rect.width) * Math.max(0, rect.height)
  }

  function overlapArea(a: DOMRect, b: DOMRect): number {
    const left = Math.max(a.left, b.left)
    const right = Math.min(a.right, b.right)
    const top = Math.max(a.top, b.top)
    const bottom = Math.min(a.bottom, b.bottom)
    return Math.max(0, right - left) * Math.max(0, bottom - top)
  }

  function overlapAreaWithClip(
    rect: DOMRect,
    clip: { left: number; top: number; right: number; bottom: number },
  ): number {
    const left = Math.max(rect.left, clip.left)
    const right = Math.min(rect.right, clip.right)
    const top = Math.max(rect.top, clip.top)
    const bottom = Math.min(rect.bottom, clip.bottom)
    return Math.max(0, right - left) * Math.max(0, bottom - top)
  }
}

function extractDouyinSearchResults(
  regionRect: { left: number; top: number; right: number; bottom: number },
  max: number,
): ExtractedRegionItem[] {
  const candidates = Array.from(document.querySelectorAll<HTMLElement>(
    [
      'a[href*="/video/"]',
      'a[href*="modal_id="]',
      'a[href*="aweme_id="]',
      'a[href*="douyin.com/video"]',
      'a[href*="douyin.com/jingxuan/search"][href*="modal_id="]',
      '[data-e2e*="search" i] a[href]',
      '[data-e2e*="video" i]',
      '[class*="search" i]',
      '[class*="video" i]',
      '[class*="card" i]',
      'article',
      'li',
      '[role="link"]',
      '[role="button"]',
      'main div',
    ].join(','),
  ))
    .map((el, domIndex) => toVideoCandidate(el, domIndex))
    .filter((candidate): candidate is VideoCandidate => candidate !== null)
    .filter(candidate => intersects(candidate.rect, regionRect))
    .filter(candidate => isUsefulResultRect(candidate.rect))
    .filter(candidate => !looksLikeChromeOrFilter(candidate.text, candidate.href))

  const unique: VideoCandidate[] = []
  for (const candidate of candidates) {
    const duplicate = unique.some(existing => sameVideoCandidate(existing, candidate))
    if (!duplicate) unique.push(candidate)
  }

  return orderVideoCandidates(unique)
    .slice(0, max)
    .map(candidate => ({
      text: candidate.text,
      role: candidate.role,
      tag: candidate.tag,
      href: candidate.href,
      bbox: bboxOf(candidate.rect),
      itemType: 'douyin_video_result',
      hrefs: candidate.hrefs,
    }))

  interface VideoCandidate {
    el: HTMLElement
    rect: DOMRect
    text: string
    href?: string
    hrefs: string[]
    role?: string
    tag: string
    domIndex: number
  }

  function toVideoCandidate(el: HTMLElement, domIndex: number): VideoCandidate | null {
    const clickEl = bestVideoClickableElement(el)
    const rect = bestVideoRect(el, clickEl)
    const hrefs = Array.from(el.querySelectorAll<HTMLAnchorElement>('a[href]'))
      .map(a => absoluteHref(a.getAttribute('href') || a.href))
      .filter((href): href is string => Boolean(href))
    if (clickEl instanceof HTMLAnchorElement) {
      const href = absoluteHref(clickEl.getAttribute('href') || clickEl.href)
      if (href) hrefs.unshift(href)
    }
    const uniqueHrefs = Array.from(new Set(hrefs))
    const href = uniqueHrefs.find(looksLikeDouyinVideoHref) ?? uniqueHrefs[0]
    const text = cleanText(el.innerText || el.textContent || clickEl.innerText || clickEl.textContent || '')
    const marker = `${clickEl.getAttribute('data-e2e') || ''} ${clickEl.className || ''} ${clickEl.getAttribute('aria-label') || ''}`
    if (!looksLikeDouyinVideoHref(href) && !looksLikeVideoResultWithoutHref(clickEl, text, marker, rect)) return null
    return {
      el: clickEl,
      rect,
      text: titleText(text, uniqueHrefs),
      href,
      hrefs: uniqueHrefs.slice(0, 8),
      role: clickEl.getAttribute('role') || undefined,
      tag: clickEl.tagName.toLowerCase(),
      domIndex,
    }
  }

  function looksLikeVideoResultWithoutHref(el: HTMLElement, text: string, marker: string, rect: DOMRect): boolean {
    if (!isUsefulResultRect(rect)) return false
    if (!looksLikeVideoMarker(marker) && !looksLikeVideoText(text)) return false
    if (looksLikeChromeOrFilter(text, undefined)) return false
    const tag = el.tagName.toLowerCase()
    return tag === 'article' ||
      tag === 'li' ||
      tag === 'a' ||
      el.getAttribute('role') === 'link' ||
      el.getAttribute('role') === 'button' ||
      rect.height >= 120 ||
      rect.width >= 180
  }

  function bestVideoClickableElement(el: HTMLElement): HTMLElement {
    const anchor = closestVideoAnchor(el)
    if (anchor) {
      return anchor
    }
    const descendants = Array.from(el.querySelectorAll<HTMLElement>('a[href], [role="link"], [role="button"], img, video, canvas'))
    const videoAnchor = descendants
      .map(desc => desc instanceof HTMLAnchorElement ? desc : closestVideoAnchor(desc))
      .find((desc): desc is HTMLElement => Boolean(desc))
    if (videoAnchor) return videoAnchor
    return largestVisibleDescendant(el) ?? el
  }

  function bestVideoRect(el: HTMLElement, clickEl: HTMLElement): DOMRect {
    const card = nearestVideoCardContainer(el, clickEl)
    if (card) return card.getBoundingClientRect()
    return clickEl.getBoundingClientRect()
  }

  function nearestVideoCardContainer(el: HTMLElement, clickEl: HTMLElement): HTMLElement | null {
    const chain: HTMLElement[] = []
    let current: HTMLElement | null = clickEl
    while (current && current !== document.body && chain.length < 8) {
      chain.push(current)
      current = current.parentElement
    }
    if (!chain.includes(el)) chain.push(el)
    return chain
      .map(node => ({ node, rect: node.getBoundingClientRect(), text: cleanText(node.innerText || node.textContent || '') }))
      .filter(entry => isUsefulResultRect(entry.rect))
      .filter(entry => !looksLikeChromeOrFilter(entry.text, undefined))
      .filter(entry => looksLikeVideoText(entry.text) || Boolean(entry.node.querySelector('img, video, canvas, a[href*="/video/"], a[href*="modal_id="], a[href*="aweme_id="]')))
      .sort((a, b) => {
        const aTooBroad = a.rect.width > window.innerWidth * 0.75 || a.rect.height > window.innerHeight * 0.9
        const bTooBroad = b.rect.width > window.innerWidth * 0.75 || b.rect.height > window.innerHeight * 0.9
        if (aTooBroad !== bTooBroad) return aTooBroad ? 1 : -1
        return area(b.rect) - area(a.rect)
      })[0]?.node ?? null
  }

  function closestVideoAnchor(el: HTMLElement): HTMLAnchorElement | null {
    if (el instanceof HTMLAnchorElement && looksLikeDouyinVideoHref(absoluteHref(el.getAttribute('href') || el.href))) {
      return el
    }
    const inside = el.querySelector<HTMLAnchorElement>('a[href*="/video/"], a[href*="modal_id="], a[href*="aweme_id="]')
    if (inside) return inside
    const parent = el.closest<HTMLAnchorElement>('a[href*="/video/"], a[href*="modal_id="], a[href*="aweme_id="]')
    return parent
  }

  function largestVisibleDescendant(el: HTMLElement): HTMLElement | null {
    return Array.from(el.querySelectorAll<HTMLElement>('img, video, canvas, [style], div, a'))
      .map(child => ({ child, rect: child.getBoundingClientRect() }))
      .filter(entry => usefulRect(entry.rect))
      .sort((a, b) => (b.rect.width * b.rect.height) - (a.rect.width * a.rect.height))[0]?.child ?? null
  }

  function orderVideoCandidates(list: VideoCandidate[]): VideoCandidate[] {
    const sorted = [...list].sort((a, b) => {
      const top = a.rect.top - b.rect.top
      if (Math.abs(top) > 8) return top
      const left = a.rect.left - b.rect.left
      if (Math.abs(left) > 8) return left
      return a.domIndex - b.domIndex
    })
    const rows: VideoCandidate[][] = []
    for (const candidate of sorted) {
      const lastRow = rows.at(-1)
      const threshold = rowClusterThreshold(candidate.rect, lastRow)
      if (!lastRow || Math.abs(candidate.rect.top - medianTop(lastRow)) > threshold) {
        rows.push([candidate])
      } else {
        lastRow.push(candidate)
      }
    }
    return rows.flatMap(row => row.sort(compareWithinVisualRow))
  }

  function compareWithinVisualRow(a: VideoCandidate, b: VideoCandidate): number {
    const left = a.rect.left - b.rect.left
    if (Math.abs(left) > 8) return left
    const top = a.rect.top - b.rect.top
    if (Math.abs(top) > 8) return top
    return a.domIndex - b.domIndex
  }

  function rowClusterThreshold(rect: DOMRect, row?: VideoCandidate[]): number {
    const heights = [rect.height, ...(row ?? []).map(candidate => candidate.rect.height)]
      .filter(height => Number.isFinite(height) && height > 0)
      .sort((a, b) => a - b)
    const medianHeight = heights[Math.floor(heights.length / 2)] ?? 180
    return Math.max(24, Math.min(96, medianHeight * 0.28))
  }

  function medianTop(row: VideoCandidate[]): number {
    const tops = row.map(candidate => candidate.rect.top).sort((a, b) => a - b)
    return tops[Math.floor(tops.length / 2)] ?? 0
  }

  function sameVideoCandidate(a: VideoCandidate, b: VideoCandidate): boolean {
    if (a.href && b.href && normalizeHref(a.href) === normalizeHref(b.href)) return true
    const overlap = overlapArea(a.rect, b.rect)
    const minArea = Math.max(1, Math.min(area(a.rect), area(b.rect)))
    return overlap / minArea >= 0.72
  }

  function looksLikeDouyinVideoHref(href?: string): boolean {
    if (!href) return false
    const lower = href.toLowerCase()
    return lower.includes('douyin.com') &&
      (lower.includes('/video/') || lower.includes('modal_id=') || lower.includes('aweme_id='))
  }

  function looksLikeVideoMarker(marker: string): boolean {
    const lower = marker.toLowerCase()
    return lower.includes('video') || lower.includes('aweme') || lower.includes('search-result')
  }

  function looksLikeVideoText(text: string): boolean {
    const value = cleanText(text)
    return value.length >= 8 &&
      !looksLikeChromeOrFilter(value, undefined) &&
      (value.toLowerCase().includes('openclaw') || value.includes('#') || value.includes('龙虾') || /\d+(?:\.\d+)?万/.test(value))
  }

  function looksLikeChromeOrFilter(text: string, href?: string): boolean {
    const value = cleanText(text).replace(/\s+/g, '')
    if (href && /\/user\//i.test(href) && !looksLikeDouyinVideoHref(href)) return true
    return /^(首页|推荐|精选|朋友|关注|商城|消息|我的|搜索|筛选|综合|视频|用户|直播|商品|音乐|话题|地点|最多点赞|最新发布|发布时间|全部时间|问问AI|AI搜索)$/.test(value)
  }

  function titleText(text: string, hrefs: string[]): string {
    const value = cleanText(text)
    if (value) return value.slice(0, 320)
    return hrefs.find(looksLikeDouyinVideoHref) ?? ''
  }

  function cleanText(text: string): string {
    return text.replace(/\s+/g, ' ').trim()
  }

  function absoluteHref(href?: string): string | undefined {
    if (!href) return undefined
    try {
      return new URL(href, location.href).href
    } catch {
      return href
    }
  }

  function normalizeHref(href: string): string {
    try {
      const url = new URL(href, location.href)
      return `${url.origin}${url.pathname}?modal_id=${url.searchParams.get('modal_id') || ''}&aweme_id=${url.searchParams.get('aweme_id') || ''}`
    } catch {
      return href
    }
  }

  function intersects(rect: DOMRect, clip: { left: number; top: number; right: number; bottom: number }): boolean {
    return rect.width > 0 &&
      rect.height > 0 &&
      rect.right >= clip.left &&
      rect.left <= clip.right &&
      rect.bottom >= clip.top &&
      rect.top <= clip.bottom
  }

  function usefulRect(rect: DOMRect): boolean {
    return rect.width >= 32 && rect.height >= 24
  }

  function isUsefulResultRect(rect: DOMRect): boolean {
    return rect.width >= 80 && rect.height >= 50
  }

  function area(rect: DOMRect): number {
    return Math.max(0, rect.width) * Math.max(0, rect.height)
  }

  function overlapArea(a: DOMRect, b: DOMRect): number {
    const left = Math.max(a.left, b.left)
    const right = Math.min(a.right, b.right)
    const top = Math.max(a.top, b.top)
    const bottom = Math.min(a.bottom, b.bottom)
    return Math.max(0, right - left) * Math.max(0, bottom - top)
  }

  function bboxOf(rect: DOMRect): ExtractedRegionItem['bbox'] {
    return {
      x: Math.round(rect.x),
      y: Math.round(rect.y),
      width: Math.round(rect.width),
      height: Math.round(rect.height),
    }
  }
}
