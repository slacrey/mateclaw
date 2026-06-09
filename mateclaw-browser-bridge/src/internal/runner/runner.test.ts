/**
 * B7 — Runner with reconnect + session_id stamp tests.
 *
 * Uses in-process ws.Server and PassThrough streams for full integration.
 * Vitest timeout is extended for reconnect timing tests.
 */
import { describe, it, expect, afterEach } from 'vitest'
import { WebSocketServer, WebSocket as WsClient } from 'ws'
import { createServer } from 'node:http'
import { PassThrough } from 'node:stream'
import { Kind, make } from '../edgeproto/edgeproto.js'
import { Client } from '../edge/client.js'
import { Runner } from './runner.js'
import { writeFrame, writeJsonFrame, readFrame } from '../nm/server.js'

// ── helpers ───────────────────────────────────────────────────────────────────

type ServerHandle = {
  url: string
  close: () => Promise<void>
}

/**
 * Spin up a WebSocket server on a random port.
 * `handler` is called once per connection.
 */
function makeServer(handler: (ws: WsClient) => void): Promise<ServerHandle> {
  return new Promise((resolve, reject) => {
    const http = createServer()
    const wss = new WebSocketServer({ server: http })
    wss.on('connection', handler)
    http.listen(0, '127.0.0.1', () => {
      const addr = http.address() as { port: number }
      const url = `ws://127.0.0.1:${addr.port}`
      const close = (): Promise<void> =>
        new Promise((res) => {
          wss.close(() => http.close(() => res()))
        })
      resolve({ url, close })
    })
    http.on('error', reject)
  })
}

/**
 * Build a hello.ack response for a given sessionId and ws socket.
 * Also sends heartbeat.ack for each heartbeat received.
 */
function respondHelloAck(ws: WsClient, sessionId: string): void {
  ws.once('message', (data: Buffer) => {
    const hello = JSON.parse(data.toString())
    if (hello.kind !== Kind.Hello) { ws.close(); return }
    ws.send(JSON.stringify({
      v: 1, msg_id: 'ack-1', kind: Kind.HelloAck,
      ts: Date.now(), trace_id: 'tr', session_id: sessionId,
      in_reply_to: hello.msg_id,
      payload: { session_id: sessionId, heartbeat_interval_ms: 5000 },
    }))
    // Respond to heartbeats so watchdog doesn't fire
    ws.on('message', (d: Buffer) => {
      const msg = JSON.parse(d.toString())
      if (msg.kind === Kind.Heartbeat) {
        ws.send(JSON.stringify({
          v: 1, msg_id: 'hbk', kind: Kind.HeartbeatAck,
          ts: Date.now(), trace_id: 'tr', session_id: '',
          in_reply_to: msg.msg_id,
        }))
      }
    })
  })
}

// ── B7 tests ──────────────────────────────────────────────────────────────────

describe('Runner — B7', () => {
  /**
   * Test 1: extension sends session_id: "", bridge stamps real server-issued id.
   */
  it('pingPong_sessionIdStampedByBridge', async () => {
    const received: string[] = []

    const { url, close } = await makeServer((ws) => {
      respondHelloAck(ws, 'sess-server-issued')
      // After hello.ack, capture whatever the bridge forwards
      ws.on('message', (data: Buffer) => {
        const msg = JSON.parse(data.toString())
        if (msg.kind === Kind.Ping) {
          received.push(msg.session_id)
          // Echo a pong back so the test can observe liveness
          ws.send(JSON.stringify({
            v: 1, msg_id: 'pong-1', kind: Kind.Pong,
            ts: Date.now(), trace_id: 'tr', session_id: 'sess-server-issued',
          }))
          ws.close()
        }
      })
    })

    const stdinPt = new PassThrough()
    const stdoutPt = new PassThrough()

    const client = new Client({ url, authToken: 'tok', heartbeatIntervalMs: 5000 })
    const runner = new Runner({ client, stdin: stdinPt, stdout: stdoutPt, maxAttempts: 1 })

    const ac = new AbortController()
    const runPromise = runner.run(ac.signal)

    // Wait a tick for connect to complete
    await new Promise((r) => setTimeout(r, 100))

    // Extension sends a ping with EMPTY session_id
    const pingMsg = make({ kind: Kind.Ping, sessionId: '' })
    await writeJsonFrame(stdinPt, pingMsg)

    // Wait for server to receive it and close
    await new Promise((r) => setTimeout(r, 200))
    ac.abort()
    await runPromise.catch(() => {})
    await close()

    expect(received.length).toBeGreaterThanOrEqual(1)
    expect(received[0]).toBe('sess-server-issued')
  }, 8000)

  /**
   * Test 2: extension sends a forged session_id; bridge overrides it.
   */
  it('overridesAttackerSuppliedSessionId', async () => {
    const received: string[] = []

    const { url, close } = await makeServer((ws) => {
      respondHelloAck(ws, 'sess-real')
      ws.on('message', (data: Buffer) => {
        const msg = JSON.parse(data.toString())
        if (msg.kind === Kind.Ping) {
          received.push(msg.session_id)
          ws.close()
        }
      })
    })

    const stdinPt = new PassThrough()
    const stdoutPt = new PassThrough()

    const client = new Client({ url, authToken: 'tok', heartbeatIntervalMs: 5000 })
    const runner = new Runner({ client, stdin: stdinPt, stdout: stdoutPt, maxAttempts: 1 })

    const ac = new AbortController()
    const runPromise = runner.run(ac.signal)

    await new Promise((r) => setTimeout(r, 100))

    // Extension sends a ping with ATTACKER session_id
    const pingMsg = make({ kind: Kind.Ping, sessionId: 'sess-attacker' })
    await writeJsonFrame(stdinPt, pingMsg)

    await new Promise((r) => setTimeout(r, 200))
    ac.abort()
    await runPromise.catch(() => {})
    await close()

    expect(received.length).toBeGreaterThanOrEqual(1)
    expect(received[0]).toBe('sess-real')
    expect(received[0]).not.toBe('sess-attacker')
  }, 8000)

  /**
   * Test 3: reconnect with backoff.
   * Server drops first two connections immediately after hello.ack.
   * Third connection is kept open.
   * Assert ≥3 distinct dials, gap between dial 1 and dial 2 is ≥ 50ms.
   */
  it('reconnectsWithBackoff', async () => {
    const dialTimes: number[] = []
    let connectionCount = 0

    const { url, close } = await makeServer((ws) => {
      connectionCount++
      dialTimes.push(Date.now())
      const attempt = connectionCount

      ws.once('message', (data: Buffer) => {
        const hello = JSON.parse(data.toString())
        if (hello.kind !== Kind.Hello) { ws.close(); return }
        ws.send(JSON.stringify({
          v: 1, msg_id: 'ack', kind: Kind.HelloAck,
          ts: Date.now(), trace_id: 'tr',
          session_id: `sess-${attempt}`,
          in_reply_to: hello.msg_id,
          payload: { session_id: `sess-${attempt}`, heartbeat_interval_ms: 5000 },
        }))

        if (attempt <= 2) {
          // Immediately drop the connection after hello.ack
          setTimeout(() => ws.close(1001, 'drop'), 20)
        }
        // Third connection stays open
      })
    })

    const stdinPt = new PassThrough()
    const stdoutPt = new PassThrough()

    const client = new Client({ url, authToken: 'tok', heartbeatIntervalMs: 5000 })
    const runner = new Runner({
      client,
      stdin: stdinPt,
      stdout: stdoutPt,
      backoffBase: 50,
      backoffMax: 500,
      maxAttempts: 5,
    })

    const ac = new AbortController()
    const runPromise = runner.run(ac.signal)

    // Wait long enough for 3 connections + backoff delays
    await new Promise((r) => setTimeout(r, 4000))
    ac.abort()
    await runPromise.catch(() => {})
    await close()

    // (a) ≥3 distinct dials
    expect(dialTimes.length).toBeGreaterThanOrEqual(3)

    // (b) gap between dial 1 and dial 2 is ≥ 50ms (backoffBase)
    if (dialTimes.length >= 2) {
      const gap = dialTimes[1]! - dialTimes[0]!
      expect(gap).toBeGreaterThanOrEqual(50)
    }
  }, 10000)
})
