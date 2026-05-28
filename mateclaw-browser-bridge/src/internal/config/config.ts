/**
 * Config loader for MateClaw Browser Bridge.
 *
 * Priority order (highest to lowest):
 *   1. Environment variables (MATECLAW_BRIDGE_*)
 *   2. YAML file at $MATECLAW_HOME/bridge.yaml (or ~/.mateclaw/bridge.yaml)
 *   3. Hardcoded defaults
 */
import { readFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { parse as parseYaml } from 'yaml'

export interface Config {
  /** WebSocket URL for the Control Plane edge endpoint. */
  controlPlaneUrl: string
  /** Bearer token for authenticating to the Control Plane. */
  authToken: string
  /** Reported agent version in hello message. */
  agentVersion: string
  /** Heartbeat interval in milliseconds (overridden by hello.ack). */
  heartbeatIntervalMs: number
}

/** YAML file shape (snake_case keys). */
interface YamlConfig {
  control_plane_url?: string
  auth_token?: string
  agent_version?: string
  heartbeat_interval_ms?: number
}

const DEFAULTS: Config = {
  controlPlaneUrl: 'ws://localhost:18088/api/v1/browser/edge',
  authToken: '',
  agentVersion: 'dev',
  heartbeatIntervalMs: 10000,
}

/** Resolves the MateClaw home directory (env or ~/.mateclaw). */
function resolveHome(): string {
  const envHome = process.env['MATECLAW_HOME']
  if (envHome && envHome.trim() !== '') {
    return envHome.trim()
  }
  return join(homedir(), '.mateclaw')
}

/** Loads the YAML config file; returns null if file doesn't exist. */
async function loadYaml(home: string): Promise<YamlConfig | null> {
  const path = join(home, 'bridge.yaml')
  try {
    const raw = await readFile(path, 'utf8')
    const parsed = parseYaml(raw) as YamlConfig | null
    return parsed ?? null
  } catch (err: unknown) {
    const code = (err as NodeJS.ErrnoException).code
    if (code === 'ENOENT') {
      return null
    }
    throw new Error(`config: failed to read ${path}: ${String(err)}`)
  }
}

/**
 * loadConfig resolves configuration by merging defaults, YAML file, and env vars.
 * Env vars always win.
 */
export async function loadConfig(): Promise<Config> {
  const home = resolveHome()
  const yaml = await loadYaml(home)

  // Start from defaults, overlay YAML, then overlay env vars.
  const cfg: Config = { ...DEFAULTS }

  // Layer 2: YAML (if present)
  if (yaml) {
    if (yaml.control_plane_url) cfg.controlPlaneUrl = yaml.control_plane_url
    if (yaml.auth_token) cfg.authToken = yaml.auth_token
    if (yaml.agent_version) cfg.agentVersion = yaml.agent_version
    if (typeof yaml.heartbeat_interval_ms === 'number' && yaml.heartbeat_interval_ms > 0) {
      cfg.heartbeatIntervalMs = yaml.heartbeat_interval_ms
    }
  }

  // Layer 1: env vars (highest priority)
  const cpUrl = process.env['MATECLAW_BRIDGE_CP_URL']
  if (cpUrl && cpUrl.trim() !== '') cfg.controlPlaneUrl = cpUrl.trim()

  const authToken = process.env['MATECLAW_BRIDGE_AUTH_TOKEN']
  if (authToken && authToken.trim() !== '') cfg.authToken = authToken.trim()

  const agentVersion = process.env['MATECLAW_BRIDGE_AGENT_VERSION']
  if (agentVersion && agentVersion.trim() !== '') cfg.agentVersion = agentVersion.trim()

  return cfg
}
