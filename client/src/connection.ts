import type { ClientMessage, ServerMessage } from './protocol.ts'

export type ConnectionStatus = 'connecting' | 'open' | 'closed'

type Listeners = {
  onMessage: (message: ServerMessage) => void
  onStatus: (status: ConnectionStatus) => void
}

const FIRST_RETRY_MS = 250
const MAX_RETRY_MS = 5000

/**
 * Ouvre la WebSocket, et la rouvre quand elle tombe.
 *
 * Avec WebSocket, la reconnexion est à notre charge : c'est le coût noté dans la
 * conception face à SSE, qui la gère seul. Le délai double à chaque échec, plafonné
 * à 5 s, pour ne pas marteler un serveur en train de redémarrer.
 */
export function connect(url: string, listeners: Listeners) {
  let retryDelay = FIRST_RETRY_MS
  let socket: WebSocket | null = null

  const open = () => {
    listeners.onStatus('connecting')
    socket = new WebSocket(url)

    socket.onopen = () => {
      retryDelay = FIRST_RETRY_MS
      listeners.onStatus('open')
    }

    socket.onmessage = (event) => {
      listeners.onMessage(JSON.parse(event.data))
    }

    socket.onclose = () => {
      listeners.onStatus('closed')
      setTimeout(open, retryDelay)
      retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS)
    }
  }

  open()

  return {
    /** Envoie si la connexion est ouverte ; sinon le message est perdu, d'où le renvoi à chaque connexion. */
    send(message: ClientMessage) {
      if (socket?.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(message))
      }
    },
  }
}
