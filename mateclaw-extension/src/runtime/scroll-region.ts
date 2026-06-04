import {
  BrowserRuntimeV2ActionKind,
  type ScrollRegionParams,
  type ScrollRegionStopWhen,
} from '../shared/browser-runtime-v2'
import type { ScrollParams } from '../sw/action/types'
import type { RuntimeRegion } from './region-registry'

export interface ParsedScrollRegionAction {
  kind: typeof BrowserRuntimeV2ActionKind.ScrollRegion
  params: ScrollRegionParams
}

export function parseScrollRegionAction(payload: unknown): ParsedScrollRegionAction | null {
  if (!payload || typeof payload !== 'object') return null
  const record = payload as Record<string, unknown>
  if (record.kind !== BrowserRuntimeV2ActionKind.ScrollRegion) return null

  const params = parseScrollRegionParams(record.params)
  if (!params) return null

  return { kind: BrowserRuntimeV2ActionKind.ScrollRegion, params }
}

export function parseScrollRegionParams(raw: unknown): ScrollRegionParams | null {
  if (!raw || typeof raw !== 'object') return null
  const p = raw as Record<string, unknown>

  if (!isNonEmptyString(p.regionKey)) return null
  if (!isDirection(p.direction)) return null
  if (!isPositiveFiniteNumber(p.amount)) return null

  const parsed: ScrollRegionParams = {
    regionKey: p.regionKey,
    direction: p.direction,
    amount: p.amount,
  }

  if (p.segments !== undefined) {
    if (!Number.isInteger(p.segments) || p.segments < 1) return null
    parsed.segments = p.segments
  }

  if (p.stopWhen !== undefined) {
    const stopWhen = parseStopWhen(p.stopWhen)
    if (!stopWhen) return null
    parsed.stopWhen = stopWhen
  }

  return parsed
}

export function toCompatibleScrollParams(
  params: ScrollRegionParams,
  region: RuntimeRegion,
): ScrollParams {
  return {
    direction: params.direction,
    distance_px: params.amount,
    segments: params.segments,
    x: region.x + region.width / 2,
    y: region.y + region.height / 2,
  }
}

function parseStopWhen(raw: unknown): ScrollRegionStopWhen | null {
  if (!raw || typeof raw !== 'object') return null
  const s = raw as Record<string, unknown>
  if (s.type !== 'edge' && s.type !== 'selector_visible' && s.type !== 'text_visible') return null

  if (s.type === 'selector_visible') {
    if (!isNonEmptyString(s.selector)) return null
    return { type: s.type, selector: s.selector }
  }
  if (s.type === 'text_visible') {
    if (!isNonEmptyString(s.text)) return null
    return { type: s.type, text: s.text }
  }
  return { type: s.type }
}

function isDirection(value: unknown): value is ScrollRegionParams['direction'] {
  return value === 'up' || value === 'down' || value === 'left' || value === 'right'
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isPositiveFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
}
