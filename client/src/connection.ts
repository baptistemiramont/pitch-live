import { now } from './latency.ts'
import type { ClientMessage, ServerMessage } from './protocol.ts'

export type ConnectionStatus = 'connecting' | 'open' | 'closed'

type Listeners = {
  /** size: the size of the decoded message, in bytes (not its compressed size on the wire). */
  onMessage: (message: ServerMessage, receivedAt: number, size: number) => void
  onBinary: (data: ArrayBuffer, receivedAt: number) => void
  onStatus: (status: ConnectionStatus) => void
}

const FIRST_RETRY_MS = 250
const MAX_RETRY_MS = 5000

/**
 * Opens the WebSocket, and reopens it when it drops.
 *
 * With WebSocket, reconnecting is our job: that is the cost noted in the design,
 * compared with SSE, which handles it on its own. The delay doubles on every failure,
 * capped at 5 s, so as not to hammer a server that is restarting (exponential backoff).
 */
export function connect(url: string, listeners: Listeners) {
  let retryDelay = FIRST_RETRY_MS
  let socket: WebSocket | null = null

  const open = () => {
    listeners.onStatus('connecting')
    socket = new WebSocket(url)
    // Binary messages arrive as ArrayBuffer, which DataView reads directly
    socket.binaryType = 'arraybuffer'

    socket.onopen = () => {
      retryDelay = FIRST_RETRY_MS
      listeners.onStatus('open')
    }

    socket.onmessage = (event) => {
      // The receipt time is taken before decoding, so that decoding is not counted
      const receivedAt = now()
      if (typeof event.data === 'string') {
        // The server's JSON is pure ASCII: one character, one byte
        listeners.onMessage(JSON.parse(event.data), receivedAt, event.data.length)
      } else {
        listeners.onBinary(event.data, receivedAt)
      }
    }

    socket.onclose = () => {
      listeners.onStatus('closed')
      setTimeout(open, retryDelay)
      retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS)
    }
  }

  open()

  return {
    /** Sends if the connection is open; otherwise the message is lost, hence resending on every connection. */
    send(message: ClientMessage) {
      if (socket?.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(message))
      }
    },
  }
}
