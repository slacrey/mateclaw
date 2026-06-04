import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { ScrollParams, ScrollRegionParams } from '../types'
import type { RegionRegistry } from '../../../runtime/region-registry'
import { parseScrollRegionParams, toCompatibleScrollParams } from '../../../runtime/scroll-region'

export interface ScrollRegionHandlerDeps {
  regions: RegionRegistry
  scroll: ActionHandler<ScrollParams>
}

export const scrollRegionHandler = (deps: ScrollRegionHandlerDeps): ActionHandler<ScrollRegionParams> => {
  return async (tabId, params, deadlineMs) => {
    const parsed = parseScrollRegionParams(params)
    if (!parsed) {
      throw new ActionFailureError(
        'HANDLER_ERROR',
        'scroll_region params were malformed',
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

    const compatible = toCompatibleScrollParams(parsed, region)
    const result = await deps.scroll(tabId, compatible, deadlineMs)

    if (result.ok !== true) return result
    return {
      ...result,
      payload: {
        ...result.payload,
        regionKey: parsed.regionKey,
        stopWhen: parsed.stopWhen,
      },
    }
  }
}
