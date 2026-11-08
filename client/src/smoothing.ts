import type { Frame, Player, Position } from './protocol.ts'

/** A frame as received: its content, the time it was sent and the time it was received. */
export type ReceivedFrame = { frame: Frame; sentAt: number; receivedAt: number }

/** What gets drawn: a frame, real or in-between, and the send time it corresponds to. */
export type ShownFrame = { frame: Frame; sentAt: number }

/**
 * The in-between state between the second-to-last and the latest frame received
 * (interpolation).
 *
 * The idea: instead of drawing the latest frame as soon as it arrives, we glide from
 * the previous one towards it, over the time that separated them on the server. The
 * latest frame is reached when the next one is due, and the movement becomes
 * continuous. The price: the pitch is shown one frame late, that is 40 ms at 25 frames
 * per second, but a whole second at 1 frame per second.
 *
 * The limit: frames are assumed to arrive at regular intervals. If the network delays
 * one, the drawing stops on the latest frame while waiting, without extrapolating; if
 * two arrive together, it jumps. A sturdier player would keep a small reserve of frames
 * (a jitter buffer) and draw with a fixed delay.
 */
export function interpolate(previous: ReceivedFrame, latest: ReceivedFrame, now: number): ShownFrame {
  const interval = latest.sentAt - previous.sentAt
  const progress = interval > 0 ? clamp((now - latest.receivedAt) / interval) : 1

  const before = new Map(previous.frame.players.map((player) => [player.id, player.position]))
  const players: Player[] = latest.frame.players.map((player) => {
    const from = before.get(player.id)
    // A player who has just come on has no previous position: they appear directly
    return from ? { id: player.id, position: lerp(from, player.position, progress) } : player
  })
  const ball =
    previous.frame.ball && latest.frame.ball ? lerp(previous.frame.ball, latest.frame.ball, progress) : latest.frame.ball

  return {
    frame: { ...latest.frame, players, ball },
    sentAt: previous.sentAt + progress * interval,
  }
}

function lerp(from: Position, to: Position, progress: number): Position {
  return { x: from.x + (to.x - from.x) * progress, y: from.y + (to.y - from.y) * progress }
}

function clamp(value: number) {
  return Math.min(1, Math.max(0, value))
}
