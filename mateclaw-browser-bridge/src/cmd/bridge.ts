/**
 * bridge — MateClaw Browser Agent Native Host entrypoint.
 *
 * Bridges the Chrome Extension (via Native Messaging over stdio) to the
 * MateClaw Control Plane (via Bearer-over-WSS).
 * See ../../docs/specs/edge-protocol.md for the wire format.
 */
import { loadConfig } from '../internal/config/config.js'
import { Client } from '../internal/edge/client.js'
import { Runner } from '../internal/runner/runner.js'

const VERSION = '0.1.0'

async function main(args: string[]): Promise<void> {
  if (args.includes('--version') || args.includes('-version')) {
    process.stdout.write(`mateclaw-browser-bridge ${VERSION}\n`)
    process.exit(0)
  }

  if (args[0] === 'run') {
    const cfg = await loadConfig()

    if (!cfg.authToken) {
      process.stderr.write(
        'bridge run: MATECLAW_BRIDGE_AUTH_TOKEN or bridge.yaml auth_token is required\n',
      )
      process.exit(1)
    }

    const client = new Client({
      url: cfg.controlPlaneUrl,
      authToken: cfg.authToken,
      agentVersion: cfg.agentVersion,
      heartbeatIntervalMs: cfg.heartbeatIntervalMs,
    })

    const runner = new Runner({
      client,
      stdin: process.stdin,
      stdout: process.stdout,
    })

    // Wire SIGINT / SIGTERM to a graceful abort
    const ac = new AbortController()
    const handleSignal = (): void => {
      if (!ac.signal.aborted) ac.abort()
    }
    process.once('SIGINT', handleSignal)
    process.once('SIGTERM', handleSignal)

    try {
      await runner.run(ac.signal)
    } catch (err) {
      process.stderr.write(`bridge run: fatal — ${String(err)}\n`)
      process.exit(1)
    } finally {
      process.off('SIGINT', handleSignal)
      process.off('SIGTERM', handleSignal)
    }

    process.exit(0)
    return
  }

  process.stderr.write(
    `bridge: no command (try --version or run)\n`,
  )
  process.exit(1)
}

main(process.argv.slice(2)).catch((err) => {
  process.stderr.write(`bridge: unexpected error — ${String(err)}\n`)
  process.exit(1)
})
