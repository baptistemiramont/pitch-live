// The contract between the server and the browser.
// Mirrors server/src/main/java/dev/baptiste/pitch_live/Protocol.java (and
// BinaryFrameEncoder.java for the binary format): any change there must be carried
// over here, by hand.

export const PROTOCOL_VERSION = 1

export type Team = 'HOME' | 'AWAY'

/** In metres, origin at the top left of the pitch, y axis pointing down. */
export type Position = { x: number; y: number }

export type Pitch = { length: number; width: number }

export type PlayerProfile = { id: string; team: Team; number: number }

/** A player on the pitch; players off the pitch are not in the frame. */
export type Player = { id: string; position: Position }

export type Frame = {
  id: number
  period: number
  /** Match time in seconds since kick-off. */
  time: number
  players: Player[]
  /** null when the ball is not tracked (stoppage, tracking loss). */
  ball: Position | null
}

/** Received once, on connection: the pitch and the roster. */
export type MatchMessage = { v: number; type: 'match'; pitch: Pitch; players: PlayerProfile[] }

/** Received with every frame; sentAt is the time the server published it, in ms since 1970. */
export type FrameMessage = { v: number; type: 'frame'; sentAt: number; frame: Frame }

export type MatchEvent = {
  frame: number
  period: number
  /** Match time in seconds, on the same clock as the frames. */
  time: number
  team: Team
  kind: 'GOAL' | 'YELLOW_CARD' | 'RED_CARD'
  /** The player id, as in the roster. */
  player: string
}

/** Received on connection, then whenever it changes: the score and every goal and card so far. */
export type EventsMessage = {
  v: number
  type: 'events'
  score: { home: number; away: number }
  events: MatchEvent[]
}

export type ServerMessage = MatchMessage | FrameMessage | EventsMessage

/** Sent to the server: only send fps frames per second (1 to 25). */
export type RateRequest = { v: number; type: 'rate'; fps: number }

export type ClientMessage = RateRequest

/**
 * Decodes a frame received in binary (connection opened with ?format=binary).
 * BinaryFrameEncoder.java describes the layout byte by byte. DataView reads big-endian
 * by default, as ByteBuffer writes in Java. Players are referred to by their rank in
 * the roster received on connection.
 * Returns null for an unknown message type.
 */
export function decodeBinaryFrame(data: ArrayBuffer, roster: PlayerProfile[]): FrameMessage | null {
  const view = new DataView(data)
  if (view.getUint8(1) !== 1) {
    return null
  }
  const players: Player[] = []
  for (let i = 0; i < view.getUint8(24); i++) {
    const offset = 25 + 5 * i
    players.push({
      id: roster[view.getUint8(offset)].id,
      position: { x: view.getInt16(offset + 1) / 100, y: view.getInt16(offset + 3) / 100 },
    })
  }
  return {
    v: view.getUint8(0),
    type: 'frame',
    sentAt: view.getFloat64(2),
    frame: {
      id: view.getUint32(10),
      period: view.getUint8(14),
      time: view.getUint32(15) / 1000,
      players,
      ball: view.getUint8(19) === 1 ? { x: view.getInt16(20) / 100, y: view.getInt16(22) / 100 } : null,
    },
  }
}
