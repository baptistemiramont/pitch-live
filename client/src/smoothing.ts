import type { Frame, Player, Position } from './protocol.ts'

/** Une image telle que reçue : son contenu, son heure d'envoi et son heure de réception. */
export type ReceivedFrame = { frame: Frame; sentAt: number; receivedAt: number }

/** Ce qu'on dessine : une image, réelle ou intermédiaire, et l'heure d'envoi qui lui correspond. */
export type ShownFrame = { frame: Frame; sentAt: number }

/**
 * L'état intermédiaire entre l'avant-dernière et la dernière image reçue.
 *
 * Le principe : au lieu de dessiner la dernière image dès son arrivée, on glisse de
 * l'avant-dernière vers elle, pendant le temps qui les séparait côté serveur. La
 * dernière est atteinte quand la suivante doit arriver, et le mouvement devient
 * continu. Le prix : le terrain est montré avec une image de retard, soit 40 ms à
 * 25 images par seconde, mais une seconde entière à 1 image par seconde.
 *
 * La limite : on suppose que les images arrivent à intervalles réguliers. Si le réseau
 * en retarde une, le dessin s'arrête sur la dernière en attendant, sans extrapoler ;
 * si deux arrivent collées, il saute. Un lecteur plus robuste garderait une petite
 * réserve d'images (un « jitter buffer ») et dessinerait avec un retard fixe.
 */
export function interpolate(previous: ReceivedFrame, latest: ReceivedFrame, now: number): ShownFrame {
  const interval = latest.sentAt - previous.sentAt
  const progress = interval > 0 ? clamp((now - latest.receivedAt) / interval) : 1

  const before = new Map(previous.frame.players.map((player) => [player.id, player.position]))
  const players: Player[] = latest.frame.players.map((player) => {
    const from = before.get(player.id)
    // Un joueur qui vient d'entrer n'a pas de position précédente : il apparaît directement
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
