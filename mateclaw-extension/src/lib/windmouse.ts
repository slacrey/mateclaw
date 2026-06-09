// WindMouse — pure-math human-like cursor path generator.
//
// Based on Benjamin C. Gray's 2008 WindMouse algorithm (a wind/gravity force
// model) plus per-step log-normal jitter. The output is a list of integer
// waypoints with millisecond timestamps that a caller (e.g. B7 move_mouse
// handler) can feed to CDP `Input.dispatchMouseEvent` one at a time.
//
// No chrome.* APIs, no DOM, no async, no side effects, no global state.
// All randomness flows through an injectable RNG so tests are deterministic.

export interface Point {
  readonly x: number
  readonly y: number
}

export interface Waypoint extends Point {
  /** milliseconds since the start of this movement that the cursor should arrive here */
  readonly t: number
}

export type Profile = 'natural' | 'linear'

export interface WindMouseOptions {
  /** Total movement duration in ms (default: distance-dependent ~ 200-800ms). */
  readonly durationMs?: number
  /** Movement profile. 'linear' = N=2 straight line; 'natural' = WindMouse + log-normal. */
  readonly profile?: Profile
  /** Injectable RNG for tests. Must return values in [0, 1). Defaults to Math.random. */
  readonly random?: () => number
}

// ---------------------------------------------------------------------------
// Tunable constants — chosen to match the original WindMouse paper's defaults,
// adjusted slightly so that even modest 400-500px distances produce >=5 steps.
// ---------------------------------------------------------------------------

/** Strength of the wind perturbation (px). */
const W_CONST = 9
/** Strength of the pull toward the target (px). */
const G_CONST = 9
/** Maximum step magnitude (px). Caps acceleration so the cursor never teleports. */
const M_CONST = 12
/**
 * Distance below which the cursor uses a low-noise "approach" mode and
 * snaps to the target. Original paper uses 3; we keep that.
 */
const D_CONST = 3
/** Hard cap so we never loop forever on pathological inputs. */
const MAX_STEPS = 5000

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function defaultDuration(distance: number): number {
  // Roughly Fitts'-law-shaped: short hops snap fast (~200ms), long hauls take
  // up to 800ms. The 0.6 ms/px slope is hand-tuned to feel human in the
  // 100..1500px regime that browser viewports actually inhabit.
  const ms = 200 + distance * 0.6
  if (ms < 200) return 200
  if (ms > 800) return 800
  return Math.round(ms)
}

/**
 * Sample one value from a log-normal distribution using the Box–Muller
 * transform on the injected uniform RNG. Median is ~exp(mu); the small
 * sigma keeps the tail tight so we never produce a 50px jitter.
 *
 * With mu=ln(0.5) and sigma=0.5 the 50th percentile is ~0.5px and the
 * 99th percentile is ~1.8px — exactly the "organic but not noisy" band
 * the brief calls for.
 */
function sampleLogNormal(rng: () => number, mu: number, sigma: number): number {
  // Box–Muller — guard u1 against 0 so log() never explodes.
  const u1 = Math.max(rng(), 1e-12)
  const u2 = rng()
  const z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2)
  return Math.exp(mu + sigma * z)
}

interface RawStep {
  readonly x: number
  readonly y: number
  /** Euclidean distance covered from the previous raw step. */
  readonly d: number
}

/**
 * Run the WindMouse force-model loop and produce one raw point per step.
 * Timestamps are *not* assigned here — that happens after the loop, once
 * we know the total path length.
 */
function runWindMouse(
  from: Point,
  to: Point,
  rng: () => number,
): RawStep[] {
  // Use float state internally; we only round when emitting waypoints.
  let x = from.x
  let y = from.y
  let vx = 0
  let vy = 0
  let wx = 0
  let wy = 0

  const steps: RawStep[] = [{ x: Math.round(x), y: Math.round(y), d: 0 }]

  const sqrt3 = Math.sqrt(3)
  const sqrt5 = Math.sqrt(5)

  for (let i = 0; i < MAX_STEPS; i++) {
    const dx = to.x - x
    const dy = to.y - y
    const dist = Math.hypot(dx, dy)
    if (dist < 1) break

    // ---- 1. Wind term -----------------------------------------------------
    const wMag = Math.min(W_CONST, dist)
    if (dist >= D_CONST) {
      // Normal flight: full wind perturbation. Decay then perturb.
      wx = wx / sqrt3 + ((rng() * 2 - 1) * wMag) / sqrt5
      wy = wy / sqrt3 + ((rng() * 2 - 1) * wMag) / sqrt5
    } else {
      // Final approach: wind decays harder, gravity stays.
      wx = wx / sqrt3
      wy = wy / sqrt3
    }

    // ---- 2. Velocity update ----------------------------------------------
    vx += wx + (G_CONST * dx) / dist
    vy += wy + (G_CONST * dy) / dist

    // Clamp velocity magnitude to M_CONST so the cursor can't teleport.
    const vMag = Math.hypot(vx, vy)
    if (vMag > M_CONST) {
      const cap = M_CONST / 2 + rng() * (M_CONST / 2)
      vx = (vx / vMag) * cap
      vy = (vy / vMag) * cap
    }

    // ---- 3. Log-normal jitter (perpendicular to motion, small) -----------
    // The jitter is *added to the position*, not the velocity, so it does
    // not compound across steps. Median ~0.5px, max ~2px — well below
    // human flick noise of 1-3px observed in mousepath studies.
    if (dist >= D_CONST) {
      const jitterMag = sampleLogNormal(rng, Math.log(0.5), 0.5)
      // Sign chosen by another draw from the RNG.
      const sign = rng() < 0.5 ? -1 : 1
      // Perpendicular unit vector to (vx, vy). Falls back to (0,1) if v≈0.
      const vNorm = Math.hypot(vx, vy)
      let px = 0
      let py = 0
      if (vNorm > 1e-6) {
        px = -vy / vNorm
        py = vx / vNorm
      }
      x += vx + sign * jitterMag * px
      y += vy + sign * jitterMag * py
    } else {
      // No jitter on final approach — twitching at the target looks worse
      // than being a hair too aggressive.
      x += vx
      y += vy
    }

    const rx = Math.round(x)
    const ry = Math.round(y)
    const prev = steps[steps.length - 1]!
    if (rx !== prev.x || ry !== prev.y) {
      steps.push({
        x: rx,
        y: ry,
        d: Math.hypot(rx - prev.x, ry - prev.y),
      })
    }
  }

  // Snap to exact target.
  const tail = steps[steps.length - 1]!
  if (tail.x !== to.x || tail.y !== to.y) {
    steps.push({
      x: to.x,
      y: to.y,
      d: Math.hypot(to.x - tail.x, to.y - tail.y),
    })
  }

  return steps
}

/**
 * Generate a sequence of waypoints from `from` to `to`.
 *
 * Invariants the implementation satisfies:
 *  - Returned array's first element equals `from` with t=0.
 *  - Returned array's last element equals `to` exactly.
 *  - profile='linear' returns exactly [from, to] (2 waypoints).
 *  - profile='natural' returns N >= 5 waypoints for non-trivial distances (>10px).
 *  - Last waypoint's t equals durationMs (or computed default).
 *  - Monotonically non-decreasing t across waypoints.
 *  - Integer coordinates (rounded after computation).
 */
export function generate(from: Point, to: Point, opts: WindMouseOptions = {}): Waypoint[] {
  const profile: Profile = opts.profile ?? 'natural'
  const rng: () => number = opts.random ?? Math.random
  const fx = Math.round(from.x)
  const fy = Math.round(from.y)
  const tx = Math.round(to.x)
  const ty = Math.round(to.y)
  const distance = Math.hypot(tx - fx, ty - fy)

  // Resolve durationMs: a non-positive or non-finite override falls back
  // to the distance-derived default so callers can never poison t.
  let duration: number
  if (typeof opts.durationMs === 'number' && Number.isFinite(opts.durationMs) && opts.durationMs > 0) {
    duration = opts.durationMs
  } else {
    duration = defaultDuration(distance)
  }

  // ---- Trivial: from == to ----------------------------------------------
  if (fx === tx && fy === ty) {
    return [{ x: fx, y: fy, t: 0 }]
  }

  // ---- Linear profile ---------------------------------------------------
  if (profile === 'linear') {
    return [
      { x: fx, y: fy, t: 0 },
      { x: tx, y: ty, t: duration },
    ]
  }

  // ---- Natural profile: WindMouse loop ----------------------------------
  let raw = runWindMouse({ x: fx, y: fy }, { x: tx, y: ty }, rng)

  // For very short hops the loop may exit after one step (already at target).
  // The brief mandates N >= 5 for non-trivial distances (>10px); insert a
  // few near-linear interpolation samples to satisfy that invariant
  // without injecting unrealistic jitter.
  if (distance > 10 && raw.length < 5) {
    raw = densify({ x: fx, y: fy }, { x: tx, y: ty }, 5)
  }

  // ---- Time-distribute waypoints ----------------------------------------
  // Cumulative arc length → fraction → t. The last waypoint gets exactly
  // duration so the caller can sleep until it without rounding drift.
  const cum: number[] = [0]
  for (let i = 1; i < raw.length; i++) {
    cum.push(cum[i - 1]! + raw[i]!.d)
  }
  const total = cum[cum.length - 1]!

  const out: Waypoint[] = new Array(raw.length)
  for (let i = 0; i < raw.length; i++) {
    const r = raw[i]!
    let t: number
    if (i === 0) {
      t = 0
    } else if (i === raw.length - 1) {
      // Snap the final t to exactly duration — no rounding drift allowed.
      t = duration
    } else {
      // Floor preserves monotonicity even after rounding the previous t.
      t = total > 0 ? Math.floor((cum[i]! / total) * duration) : 0
    }
    out[i] = { x: r.x, y: r.y, t }
  }

  // Defensive: enforce monotone non-decreasing t.
  for (let i = 1; i < out.length; i++) {
    if (out[i]!.t < out[i - 1]!.t) {
      out[i] = { x: out[i]!.x, y: out[i]!.y, t: out[i - 1]!.t }
    }
  }

  return out
}

/**
 * Build a straight-line densified path between two points, used as a
 * fallback when WindMouse exits too quickly to satisfy the N>=5 invariant.
 * Integer-rounded; deduplicates collinear duplicates.
 */
function densify(from: Point, to: Point, n: number): RawStep[] {
  const out: RawStep[] = []
  let prev: RawStep | null = null
  for (let i = 0; i < n; i++) {
    const f = i / (n - 1)
    const rx = Math.round(from.x + (to.x - from.x) * f)
    const ry = Math.round(from.y + (to.y - from.y) * f)
    const d = prev ? Math.hypot(rx - prev.x, ry - prev.y) : 0
    const step: RawStep = { x: rx, y: ry, d }
    if (!prev || step.x !== prev.x || step.y !== prev.y) {
      out.push(step)
      prev = step
    }
  }
  return out
}
