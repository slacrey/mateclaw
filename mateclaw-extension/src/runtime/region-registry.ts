export interface RuntimeRegion {
  key: string
  tabId: number
  x: number
  y: number
  width: number
  height: number
  source?: string
  updatedAt: number
}

export interface RegionRegistration {
  key: string
  tabId: number
  rect: {
    x: number
    y: number
    width: number
    height: number
  }
  source?: string
}

export class RegionRegistry {
  private readonly regions = new Map<string, RuntimeRegion>()

  register(region: RegionRegistration, now = Date.now()): RuntimeRegion {
    assertRegionRegistration(region)
    const stored: RuntimeRegion = {
      key: region.key,
      tabId: region.tabId,
      x: region.rect.x,
      y: region.rect.y,
      width: region.rect.width,
      height: region.rect.height,
      source: region.source,
      updatedAt: now,
    }
    this.regions.set(regionKey(region.tabId, region.key), stored)
    return stored
  }

  get(tabId: number, key: string): RuntimeRegion | null {
    if (!Number.isInteger(tabId) || !isNonEmptyString(key)) return null
    return this.regions.get(regionKey(tabId, key)) ?? null
  }

  delete(tabId: number, key: string): boolean {
    if (!Number.isInteger(tabId) || !isNonEmptyString(key)) return false
    return this.regions.delete(regionKey(tabId, key))
  }

  clearTab(tabId: number): void {
    if (!Number.isInteger(tabId)) return
    for (const key of this.regions.keys()) {
      if (key.startsWith(`${tabId}:`)) this.regions.delete(key)
    }
  }

  clear(): void {
    this.regions.clear()
  }
}

function regionKey(tabId: number, key: string): string {
  return `${tabId}:${key}`
}

function assertRegionRegistration(region: RegionRegistration): void {
  if (!isNonEmptyString(region.key)) throw new Error('region key must be a non-empty string')
  if (!Number.isInteger(region.tabId)) throw new Error('region tabId must be an integer')
  if (!isFiniteNumber(region.rect.x) || !isFiniteNumber(region.rect.y)) {
    throw new Error('region rect origin must be finite')
  }
  if (!isPositiveNumber(region.rect.width) || !isPositiveNumber(region.rect.height)) {
    throw new Error('region rect size must be positive')
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
