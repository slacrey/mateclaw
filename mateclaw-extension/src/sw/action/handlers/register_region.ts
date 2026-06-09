import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { RegisterRegionParams } from '../types'
import type { RegionRegistry } from '../../../runtime/region-registry'

export interface RegisterRegionHandlerDeps {
  regions: RegionRegistry
}

export const registerRegionHandler = (deps: RegisterRegionHandlerDeps): ActionHandler<RegisterRegionParams> => {
  return async (tabId, params, _deadlineMs) => {
    if (!isValidParams(params)) {
      throw new ActionFailureError(
        'HANDLER_ERROR',
        'register_region params were malformed',
        false,
      )
    }

    const region = deps.regions.register({
      key: params.regionKey,
      tabId,
      rect: params.rect,
      source: params.source ?? 'server',
    })

    return {
      ok: true,
      elapsed_ms: 0,
      payload: {
        regionKey: region.key,
        rect: {
          x: region.x,
          y: region.y,
          width: region.width,
          height: region.height,
        },
      },
    }
  }
}

function isValidParams(params: RegisterRegionParams): boolean {
  return !!params
    && typeof params.regionKey === 'string'
    && params.regionKey.trim().length > 0
    && !!params.rect
    && isFiniteNumber(params.rect.x)
    && isFiniteNumber(params.rect.y)
    && isPositiveNumber(params.rect.width)
    && isPositiveNumber(params.rect.height)
    && (params.source === undefined || typeof params.source === 'string')
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isPositiveNumber(value: unknown): value is number {
  return isFiniteNumber(value) && value > 0
}
