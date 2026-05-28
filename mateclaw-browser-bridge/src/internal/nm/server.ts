/**
 * Chrome Native Messaging stdio codec.
 *
 * Chrome Native Messaging protocol:
 *   - Each message is framed with a 4-byte little-endian length prefix.
 *   - Maximum frame size is 1 MB (Chrome's documented cap).
 *
 * Exported API:
 *   - MAX_FRAME_BYTES  — the 1 MB cap constant
 *   - readFrame(stream)          — read one frame; returns null on EOF
 *   - writeFrame(stream, payload) — write one frame
 *   - writeJsonFrame(stream, v)   — convenience: JSON.stringify + writeFrame
 */
import type { Readable, Writable } from 'node:stream'

/** Chrome Native Messaging 1 MB cap. */
export const MAX_FRAME_BYTES = 1024 * 1024

/**
 * Read exactly n bytes from a Node Readable stream.
 * Returns null on clean EOF (when buf is empty and the stream has ended).
 * Throws on unexpected EOF mid-frame.
 *
 * Uses the "wait for 'readable' or 'end'" pattern — does NOT use
 * AbortController / signal, which caused AbortErrors in prior attempts.
 *
 * Key subtlety: Readable.from(buffer) may have already emitted 'end' before
 * our listener is attached. We guard this by checking stream.readableEnded
 * synchronously after a null read.
 */
async function readN(stream: Readable, n: number): Promise<Buffer | null> {
  let buf = Buffer.alloc(0)

  while (buf.length < n) {
    const chunk = stream.read(n - buf.length) as Buffer | null

    if (chunk === null) {
      // Check if stream already ended (synchronous check first — avoids missing
      // the 'end' event that fires before our listener is attached).
      if (stream.readableEnded) {
        if (buf.length === 0) return null
        throw new Error(`nm: unexpected EOF mid-frame (got ${buf.length}, need ${n})`)
      }

      // No data available yet — wait for the stream to have data or to end.
      const ended = await new Promise<boolean>((resolve) => {
        const onReadable = (): void => {
          cleanup()
          resolve(false)
        }
        const onEnd = (): void => {
          cleanup()
          resolve(true)
        }
        const cleanup = (): void => {
          stream.off('readable', onReadable)
          stream.off('end', onEnd)
        }
        stream.once('readable', onReadable)
        stream.once('end', onEnd)
      })

      if (ended && buf.length === 0) return null
      if (ended) throw new Error(`nm: unexpected EOF mid-frame (got ${buf.length}, need ${n})`)
      // 'readable' fired — loop again and try stream.read()
      continue
    }

    buf = Buffer.concat([buf, chunk])
  }

  return buf
}

/**
 * Read one Native Messaging frame from a Readable stream.
 *
 * @returns Buffer with the frame payload, or null on clean EOF.
 * @throws  Error if the frame exceeds MAX_FRAME_BYTES or on unexpected EOF.
 */
export async function readFrame(stream: Readable): Promise<Buffer | null> {
  const header = await readN(stream, 4)
  if (header === null) return null

  const length = header.readUInt32LE(0)

  if (length === 0) return Buffer.alloc(0)

  if (length > MAX_FRAME_BYTES) {
    throw new Error(
      `nm: frame length ${length} exceeds 1MB cap (${MAX_FRAME_BYTES} bytes)`,
    )
  }

  return readN(stream, length)
}

/**
 * Write one Native Messaging frame to a Writable stream.
 * Prepends a 4-byte little-endian length prefix.
 */
export function writeFrame(stream: Writable, payload: Buffer | string): Promise<void> {
  const data: Buffer = typeof payload === 'string' ? Buffer.from(payload) : payload
  const header = Buffer.alloc(4)
  header.writeUInt32LE(data.length, 0)
  const frame = Buffer.concat([header, data])

  return new Promise<void>((resolve, reject) => {
    stream.write(frame, (err) => {
      if (err) reject(err)
      else resolve()
    })
  })
}

/**
 * Convenience: JSON-stringify a value and write it as a Native Messaging frame.
 */
export function writeJsonFrame(stream: Writable, v: unknown): Promise<void> {
  return writeFrame(stream, JSON.stringify(v))
}
