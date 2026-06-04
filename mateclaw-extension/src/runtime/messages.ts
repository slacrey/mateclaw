import {
  BrowserRuntimeV2MessageKind,
  type RuntimeRegionClearMessage,
  type RuntimeRegionRegisterMessage,
} from '../shared/browser-runtime-v2'
import type { RegionRegistration } from './region-registry'

export function parseRegionRegistrationMessage(raw: unknown): RegionRegistration | null {
  if (!raw || typeof raw !== 'object') return null
  const msg = raw as RuntimeRegionRegisterMessage
  if (msg.kind !== BrowserRuntimeV2MessageKind.RegionRegister) return null

  const payload = msg.payload as Record<string, unknown> | undefined
  if (!payload || typeof payload !== 'object') return null
  const rect = payload.rect as Record<string, unknown> | undefined
  if (!rect || typeof rect !== 'object') return null

  if (!isNonEmptyString(payload.regionKey)) return null
  if (!Number.isInteger(payload.tabId)) return null
  if (!isFiniteNumber(rect.x) || !isFiniteNumber(rect.y)) return null
  if (!isPositiveNumber(rect.width) || !isPositiveNumber(rect.height)) return null
  if (payload.source !== undefined && typeof payload.source !== 'string') return null

  return {
    key: payload.regionKey,
    tabId: payload.tabId,
    rect: {
      x: rect.x,
      y: rect.y,
      width: rect.width,
      height: rect.height,
    },
    source: payload.source,
  }
}

export function parseRegionClearMessage(raw: unknown): RuntimeRegionClearMessage['payload'] | null {
  if (!raw || typeof raw !== 'object') return null
  const msg = raw as RuntimeRegionClearMessage
  if (msg.kind !== BrowserRuntimeV2MessageKind.RegionClear) return null
  if (!msg.payload || typeof msg.payload !== 'object') return null

  const payload = msg.payload as Record<string, unknown>
  if (payload.tabId !== undefined && !Number.isInteger(payload.tabId)) return null
  if (payload.regionKey !== undefined && !isNonEmptyString(payload.regionKey)) return null

  return {
    tabId: payload.tabId as number | undefined,
    regionKey: payload.regionKey as string | undefined,
  }
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isPositiveNumber(value: unknown): value is number {
  return isFiniteNumber(value) && value > 0
}
