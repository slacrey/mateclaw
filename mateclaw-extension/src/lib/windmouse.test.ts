import { describe, it, expect } from 'vitest'
import { generate, type Point } from './windmouse'

// Linear congruential-style RNG for reproducible test paths.
// Returns values in [0, 1). Same seed → same sequence.
function seededRandom(seed: number): () => number {
  let s = seed >>> 0
  return () => {
    s = (s * 9301 + 49297) % 233280
    return s / 233280
  }
}

describe('windmouse', () => {
  describe('profile: linear', () => {
    it('linear profile returns exactly 2 waypoints', () => {
      const from: Point = { x: 10, y: 20 }
      const to: Point = { x: 500, y: 400 }
      const wp = generate(from, to, { profile: 'linear', durationMs: 200 })
      expect(wp).toHaveLength(2)
      expect(wp[0]).toEqual({ x: 10, y: 20, t: 0 })
      expect(wp[1]).toEqual({ x: 500, y: 400, t: 200 })
    })
  })

  describe('profile: natural', () => {
    it('natural profile returns >= 5 waypoints for distance > 10px', () => {
      const from: Point = { x: 0, y: 0 }
      const to: Point = { x: 400, y: 300 }
      const wp = generate(from, to, {
        profile: 'natural',
        durationMs: 500,
        random: seededRandom(1),
      })
      expect(wp.length).toBeGreaterThanOrEqual(5)
    })

    it('first waypoint equals `from` with t=0', () => {
      const from: Point = { x: 17, y: 23 }
      const to: Point = { x: 400, y: 300 }
      const wp = generate(from, to, {
        profile: 'natural',
        durationMs: 400,
        random: seededRandom(7),
      })
      expect(wp[0]).toEqual({ x: 17, y: 23, t: 0 })
    })

    it('last waypoint equals `to` exactly (no off-by-one drift)', () => {
      const from: Point = { x: 5, y: 5 }
      const to: Point = { x: 987, y: 654 }
      const wp = generate(from, to, {
        profile: 'natural',
        durationMs: 600,
        random: seededRandom(42),
      })
      const last = wp[wp.length - 1]!
      expect(last.x).toBe(987)
      expect(last.y).toBe(654)
    })

    it('all coordinates are integers', () => {
      const from: Point = { x: 0, y: 0 }
      const to: Point = { x: 500, y: 500 }
      const wp = generate(from, to, {
        profile: 'natural',
        durationMs: 500,
        random: seededRandom(99),
      })
      for (const p of wp) {
        expect(Number.isInteger(p.x)).toBe(true)
        expect(Number.isInteger(p.y)).toBe(true)
      }
    })

    it('t is monotonically non-decreasing', () => {
      const from: Point = { x: 0, y: 0 }
      const to: Point = { x: 800, y: 600 }
      const wp = generate(from, to, {
        profile: 'natural',
        durationMs: 700,
        random: seededRandom(123),
      })
      for (let i = 1; i < wp.length; i++) {
        expect(wp[i]!.t).toBeGreaterThanOrEqual(wp[i - 1]!.t)
      }
    })

    it('last waypoint t equals total durationMs', () => {
      const from: Point = { x: 50, y: 50 }
      const to: Point = { x: 300, y: 400 }
      const wp = generate(from, to, {
        profile: 'natural',
        durationMs: 450,
        random: seededRandom(7),
      })
      expect(wp[wp.length - 1]!.t).toBe(450)
    })

    it('two runs with the same seed produce identical waypoints (determinism)', () => {
      // PROVES the RNG injection works — critical for testability of B7.
      const from: Point = { x: 0, y: 0 }
      const to: Point = { x: 500, y: 400 }
      const a = generate(from, to, {
        profile: 'natural',
        durationMs: 500,
        random: seededRandom(2024),
      })
      const b = generate(from, to, {
        profile: 'natural',
        durationMs: 500,
        random: seededRandom(2024),
      })
      expect(a).toEqual(b)
    })

    it('two runs with different seeds produce different paths (variance)', () => {
      const from: Point = { x: 0, y: 0 }
      const to: Point = { x: 500, y: 400 }
      const a = generate(from, to, {
        profile: 'natural',
        durationMs: 500,
        random: seededRandom(1),
      })
      const b = generate(from, to, {
        profile: 'natural',
        durationMs: 500,
        random: seededRandom(99999),
      })
      // Same endpoints, but the interior path must differ.
      expect(a).not.toEqual(b)
      expect(a[0]).toEqual(b[0])
      expect(a[a.length - 1]).toEqual(b[b.length - 1])
    })

    it('large distance (~1000px) returns reasonable waypoint count (10..400)', () => {
      const from: Point = { x: 0, y: 0 }
      const to: Point = { x: 1000, y: 0 }
      const wp = generate(from, to, {
        profile: 'natural',
        durationMs: 800,
        random: seededRandom(55),
      })
      expect(wp.length).toBeGreaterThanOrEqual(10)
      expect(wp.length).toBeLessThanOrEqual(400)
    })
  })

  describe('edge cases', () => {
    it('trivial distance (from == to) returns single waypoint', () => {
      const from: Point = { x: 100, y: 100 }
      const to: Point = { x: 100, y: 100 }
      const wp = generate(from, to, {
        profile: 'natural',
        durationMs: 200,
        random: seededRandom(1),
      })
      expect(wp).toHaveLength(1)
      expect(wp[0]).toEqual({ x: 100, y: 100, t: 0 })
    })

    it('very short distance (< 3px) still returns at least the endpoints', () => {
      const from: Point = { x: 100, y: 100 }
      const to: Point = { x: 102, y: 101 }
      const wp = generate(from, to, {
        profile: 'natural',
        durationMs: 100,
        random: seededRandom(1),
      })
      expect(wp.length).toBeGreaterThanOrEqual(2)
      expect(wp[0]).toEqual({ x: 100, y: 100, t: 0 })
      const last = wp[wp.length - 1]!
      expect(last.x).toBe(102)
      expect(last.y).toBe(101)
    })

    it('default profile is natural', () => {
      const from: Point = { x: 0, y: 0 }
      const to: Point = { x: 400, y: 300 }
      const wp = generate(from, to, {
        durationMs: 400,
        random: seededRandom(8),
      })
      // No explicit profile → must behave like natural (≥5 wp for >10px).
      expect(wp.length).toBeGreaterThanOrEqual(5)
    })

    it('default durationMs is distance-dependent within 200..800ms', () => {
      const from: Point = { x: 0, y: 0 }
      const to: Point = { x: 500, y: 0 }
      const wp = generate(from, to, {
        profile: 'natural',
        random: seededRandom(3),
      })
      const total = wp[wp.length - 1]!.t
      expect(total).toBeGreaterThanOrEqual(200)
      expect(total).toBeLessThanOrEqual(800)
    })

    it('no NaN coordinates ever appear (sanity guard)', () => {
      const from: Point = { x: 1, y: 1 }
      const to: Point = { x: 731, y: 542 }
      const wp = generate(from, to, {
        profile: 'natural',
        durationMs: 600,
        random: seededRandom(31415),
      })
      for (const p of wp) {
        expect(Number.isNaN(p.x)).toBe(false)
        expect(Number.isNaN(p.y)).toBe(false)
        expect(Number.isNaN(p.t)).toBe(false)
        expect(Number.isFinite(p.x)).toBe(true)
        expect(Number.isFinite(p.y)).toBe(true)
        expect(Number.isFinite(p.t)).toBe(true)
      }
    })

    it('negative durationMs is treated as default (no negative time)', () => {
      // Defensive guard — never let bad input produce negative t.
      const from: Point = { x: 0, y: 0 }
      const to: Point = { x: 200, y: 100 }
      const wp = generate(from, to, {
        profile: 'natural',
        durationMs: -50,
        random: seededRandom(2),
      })
      const last = wp[wp.length - 1]!
      expect(last.t).toBeGreaterThan(0)
    })
  })
})
