import { describe, it, expect, afterEach } from 'vitest'
import { WebSocketServer, WebSocket as WsClient } from 'ws'
import { createServer } from 'node:http'
import { Kind, make } from '../edgeproto/edgeproto.js'
import { Client, AuthError, HeartbeatTimeoutError } from './client.js'

/** Spin up a WS server on a random OS-assigned port; tear down after the test. */
function makeServer(handler: (ws: WsClient) => void): Promise<{ url: string; close: () => Promise<void> }> {
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

/** Sends a hello.ack back and then holds the connection open. */
function fakeCpHello(sessionId: string) {
  return (ws: WsClient) => {
    ws.once('message', (data: Buffer) => {
      const hello = JSON.parse(data.toString())
      if (hello.kind !== Kind.Hello) {
        ws.close()
        return
      }
      ws.send(
        JSON.stringify({
          v: 1,
          msg_id: 'ack-1',
          kind: Kind.HelloAck,
          ts: Date.now(),
          trace_id: 'tr',
          session_id: sessionId,
          in_reply_to: hello.msg_id,
          payload: {
            session_id: sessionId,
            server_version: '1.4.0',
            heartbeat_interval_ms: 10000,
          },
        }),
      )
      // Hold open — tests close explicitly
    })
  }
}

describe('Client — B4 hello handshake', () => {
  it('completes hello handshake and returns server-issued sessionId', async () => {
    const { url, close } = await makeServer(fakeCpHello('sess-server-issued'))
    try {
      const client = new Client({ url, authToken: 'tok', agentVersion: '0.1.0' })
      const sessionId = await client.connect()
      expect(sessionId).toBe('sess-server-issued')
      expect(client.sessionId()).toBe('sess-server-issued')
      await client.close()
    } finally {
      await close()
    }
  })

  it('rejects with AuthError when server returns 401', async () => {
    // Use a plain HTTP server that rejects with 401
    const http = createServer((req, res) => {
      res.writeHead(401)
      res.end('Unauthorized')
    })
    await new Promise<void>((r) => http.listen(0, '127.0.0.1', r))
    const addr = http.address() as { port: number }
    const url = `ws://127.0.0.1:${addr.port}`

    try {
      const client = new Client({ url, authToken: 'bad' })
      await expect(client.connect()).rejects.toThrow(AuthError)
    } finally {
      await new Promise<void>((r) => http.close(() => r()))
    }
  })
})

describe('Client — B5 heartbeat monitor', () => {
  it('sends heartbeats and applies server-issued interval from hello.ack', async () => {
    let heartbeatCount = 0
    const { url, close } = await makeServer((ws) => {
      ws.once('message', (data: Buffer) => {
        const hello = JSON.parse(data.toString())
        ws.send(
          JSON.stringify({
            v: 1,
            msg_id: 'ack-1',
            kind: Kind.HelloAck,
            ts: Date.now(),
            trace_id: 'tr',
            session_id: 's',
            in_reply_to: hello.msg_id,
            payload: { session_id: 's', heartbeat_interval_ms: 100 },
          }),
        )
        ws.on('message', (d: Buffer) => {
          const msg = JSON.parse(d.toString())
          if (msg.kind === Kind.Heartbeat) {
            heartbeatCount++
            ws.send(
              JSON.stringify({
                v: 1,
                msg_id: 'hbk',
                kind: Kind.HeartbeatAck,
                ts: Date.now(),
                trace_id: 'tr',
                session_id: '',
                in_reply_to: msg.msg_id,
              }),
            )
          }
        })
      })
    })

    const client = new Client({ url, authToken: 't', agentVersion: '0.1.0' })
    await client.connect()

    const ac = new AbortController()
    const runPromise = client.run(ac.signal)

    // Wait 600ms — at 100ms interval we should get >= 3 heartbeats
    await new Promise((r) => setTimeout(r, 650))
    ac.abort()
    await runPromise.catch(() => {}) // ignore abort error

    await client.close()
    await close()

    expect(heartbeatCount).toBeGreaterThanOrEqual(3)
  })

  it('applies heartbeat_interval_ms from hello.ack', async () => {
    const timestamps: number[] = []
    const { url, close } = await makeServer((ws) => {
      ws.once('message', (data: Buffer) => {
        const hello = JSON.parse(data.toString())
        ws.send(
          JSON.stringify({
            v: 1,
            msg_id: 'ack',
            kind: Kind.HelloAck,
            ts: Date.now(),
            trace_id: 'tr',
            session_id: 's',
            in_reply_to: hello.msg_id,
            payload: { session_id: 's', heartbeat_interval_ms: 80 },
          }),
        )
        ws.on('message', (d: Buffer) => {
          const msg = JSON.parse(d.toString())
          if (msg.kind === Kind.Heartbeat) {
            timestamps.push(Date.now())
            ws.send(
              JSON.stringify({
                v: 1,
                msg_id: 'hbk',
                kind: Kind.HeartbeatAck,
                ts: Date.now(),
                trace_id: 'tr',
                session_id: '',
              }),
            )
          }
        })
      })
    })

    const client = new Client({ url, authToken: 't' })
    await client.connect()

    const ac = new AbortController()
    const runPromise = client.run(ac.signal)
    await new Promise((r) => setTimeout(r, 500))
    ac.abort()
    await runPromise.catch(() => {})
    await client.close()
    await close()

    // Should have at least 2 heartbeats to measure spacing
    expect(timestamps.length).toBeGreaterThanOrEqual(2)
    const gap = timestamps[1]! - timestamps[0]!
    // 80ms interval: expect gap between 50ms and 150ms
    expect(gap).toBeGreaterThanOrEqual(50)
    expect(gap).toBeLessThanOrEqual(150)
  })

  it('returns HeartbeatTimeoutError when server stops acking', async () => {
    const { url, close } = await makeServer((ws) => {
      ws.once('message', (data: Buffer) => {
        const hello = JSON.parse(data.toString())
        ws.send(
          JSON.stringify({
            v: 1,
            msg_id: 'ack',
            kind: Kind.HelloAck,
            ts: Date.now(),
            trace_id: 'tr',
            session_id: 's',
            in_reply_to: hello.msg_id,
            payload: { session_id: 's', heartbeat_interval_ms: 50 },
          }),
        )
        // Intentionally don't ack heartbeats
        ws.on('message', () => {})
      })
    })

    const client = new Client({ url, authToken: 't' })
    await client.connect()

    const ac = new AbortController()
    const err = await client.run(ac.signal).catch((e) => e)
    await client.close().catch(() => {})
    await close()

    expect(err).toBeInstanceOf(HeartbeatTimeoutError)
  })
})
