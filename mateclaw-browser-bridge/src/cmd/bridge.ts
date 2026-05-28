/**
 * bridge — MateClaw Browser Agent Native Host entrypoint.
 *
 * Bridges the Chrome Extension (via Native Messaging over stdio) to the
 * MateClaw Control Plane (via Bearer-over-WSS).
 * See ../../docs/specs/edge-protocol.md for the wire format.
 */

const VERSION = '0.1.0'

function main(args: string[]): void {
  if (args.includes('--version') || args.includes('-version')) {
    process.stdout.write(`mateclaw-browser-bridge ${VERSION}\n`)
    process.exit(0)
  }

  if (args[0] === 'run') {
    // Runner wiring lands in Task B7 — imports Runner and starts the pipeline.
    process.stderr.write(
      'bridge run: Runner wiring not yet connected (B7 pending)\n',
    )
    process.exit(1)
  }

  process.stderr.write(
    `bridge: no command (try --version or run)\n`,
  )
  process.exit(1)
}

main(process.argv.slice(2))
