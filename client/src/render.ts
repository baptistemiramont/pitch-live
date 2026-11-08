import type { Frame, Pitch, Player, PlayerProfile, Position, Team } from './protocol.ts'

/** Marge autour du terrain, en mètres : de quoi voir les buts et les joueurs sur la ligne. */
const MARGIN = 4

/** Les lignes réglementaires font 12 cm. */
const LINE_WIDTH = 0.12

/** Plus gros qu'en vrai, pour rester lisibles. */
const PLAYER_RADIUS = 0.9
const BALL_RADIUS = 0.45

const TEAM_COLORS: Record<Team, string> = { HOME: '#e53935', AWAY: '#1e88e5' }
const GRASS = '#2e7d32'
const LINES = 'rgba(255, 255, 255, 0.8)'
const BALL = '#ffeb3b'

/**
 * Dessine le terrain, les joueurs et le ballon dans un canvas 2D.
 *
 * Tout arrive en mètres, dans le même repère que le canvas (origine en haut à
 * gauche, y vers le bas) : passer des mètres aux pixels n'est qu'une échelle et un
 * décalage, recalculés à chaque dessin.
 */
export class PitchRenderer {
  private readonly canvas: HTMLCanvasElement
  private readonly context: CanvasRenderingContext2D
  private ratio = 1
  private pixelsPerMetre = 1
  private originX = 0
  private originY = 0

  constructor(canvas: HTMLCanvasElement) {
    const context = canvas.getContext('2d')
    if (!context) {
      throw new Error('Canvas 2D indisponible')
    }
    this.canvas = canvas
    this.context = context
  }

  /**
   * Aligne la résolution du canvas sur sa taille à l'écran. Sur un écran Retina,
   * devicePixelRatio vaut 2 : sans ce réglage, le dessin serait flou.
   * Renvoie true si la taille a changé, et qu'il faut donc redessiner.
   */
  fitToScreen(): boolean {
    this.ratio = window.devicePixelRatio
    const width = Math.round(this.canvas.clientWidth * this.ratio)
    const height = Math.round(this.canvas.clientHeight * this.ratio)
    if (width === this.canvas.width && height === this.canvas.height) {
      return false
    }
    this.canvas.width = width
    this.canvas.height = height
    return true
  }

  draw(pitch: Pitch, profiles: Map<string, PlayerProfile>, frame: Frame) {
    this.computeScale(pitch)
    const context = this.context
    context.fillStyle = '#10141a'
    context.fillRect(0, 0, this.canvas.width, this.canvas.height)

    // Le terrain est redessiné à chaque image : une douzaine de tracés, c'est
    // négligeable. Avec des cartes de chaleur ou des trajectoires, on le dessinerait
    // une fois dans un canvas hors écran, puis on le recopierait.
    this.drawPitch(pitch)
    for (const player of frame.players) {
      this.drawPlayer(player, profiles.get(player.id))
    }
    if (frame.ball) {
      this.drawBall(frame.ball)
    }
  }

  /** Le plus grand terrain qui tient dans le canvas, centré. */
  private computeScale(pitch: Pitch) {
    const { width, height } = this.canvas
    this.pixelsPerMetre = Math.min(width / (pitch.length + 2 * MARGIN), height / (pitch.width + 2 * MARGIN))
    this.originX = (width - pitch.length * this.pixelsPerMetre) / 2
    this.originY = (height - pitch.width * this.pixelsPerMetre) / 2
  }

  private drawPitch(pitch: Pitch) {
    const context = this.context
    const { length, width } = pitch
    const centreY = width / 2

    context.fillStyle = GRASS
    context.fillRect(this.x(-MARGIN), this.y(-MARGIN), this.size(length + 2 * MARGIN), this.size(width + 2 * MARGIN))

    context.strokeStyle = LINES
    context.lineWidth = Math.max(this.ratio, this.size(LINE_WIDTH))
    context.beginPath()
    this.rect(0, 0, length, width)
    this.line(length / 2, 0, length / 2, width)
    this.arc(length / 2, centreY, 9.15)

    for (const goalLine of [0, length]) {
      // +1 depuis le but de gauche, -1 depuis celui de droite : vers le centre du terrain
      const inward = goalLine === 0 ? 1 : -1
      this.rect(goalLine, centreY - 20.16, inward * 16.5, 40.32) // surface de réparation
      this.rect(goalLine, centreY - 9.16, inward * 5.5, 18.32) // surface de but
      this.rect(goalLine, centreY - 3.66, -inward * 2, 7.32) // le but, derrière la ligne

      // L'arc de cercle : la partie du cercle de 9,15 m autour du point de penalty
      // (à 11 m) qui dépasse de la surface (à 16,5 m), soit 5,5 m plus loin.
      const halfAngle = Math.acos(5.5 / 9.15)
      const facing = inward === 1 ? 0 : Math.PI
      this.arc(goalLine + inward * 11, centreY, 9.15, facing - halfAngle, facing + halfAngle)
    }
    context.stroke()

    context.fillStyle = LINES
    for (const spotX of [length / 2, 11, length - 11]) {
      context.beginPath()
      this.arc(spotX, centreY, 0.25)
      context.fill()
    }
  }

  private drawPlayer(player: Player, profile: PlayerProfile | undefined) {
    const context = this.context
    const radius = Math.max(6 * this.ratio, this.size(PLAYER_RADIUS))
    const x = this.x(player.position.x)
    const y = this.y(player.position.y)

    context.beginPath()
    context.arc(x, y, radius, 0, 2 * Math.PI)
    context.fillStyle = profile ? TEAM_COLORS[profile.team] : '#9e9e9e'
    context.fill()
    context.lineWidth = Math.max(this.ratio, radius * 0.12)
    context.strokeStyle = 'white'
    context.stroke()

    if (profile) {
      context.fillStyle = 'white'
      context.font = `bold ${Math.round(radius * 1.05)}px system-ui, sans-serif`
      context.textAlign = 'center'
      context.textBaseline = 'middle'
      context.fillText(String(profile.number), x, y + radius * 0.06)
    }
  }

  private drawBall(ball: Position) {
    const context = this.context
    context.beginPath()
    context.arc(this.x(ball.x), this.y(ball.y), Math.max(3 * this.ratio, this.size(BALL_RADIUS)), 0, 2 * Math.PI)
    context.fillStyle = BALL
    context.fill()
    context.lineWidth = this.ratio
    context.strokeStyle = '#212121'
    context.stroke()
  }

  // Des mètres aux pixels du canvas

  private x(metres: number) {
    return this.originX + metres * this.pixelsPerMetre
  }

  private y(metres: number) {
    return this.originY + metres * this.pixelsPerMetre
  }

  private size(metres: number) {
    return metres * this.pixelsPerMetre
  }

  private rect(x: number, y: number, width: number, height: number) {
    this.context.rect(this.x(x), this.y(y), this.size(width), this.size(height))
  }

  private line(x1: number, y1: number, x2: number, y2: number) {
    this.context.moveTo(this.x(x1), this.y(y1))
    this.context.lineTo(this.x(x2), this.y(y2))
  }

  /** Un arc ou un cercle complet. Le moveTo évite un trait parasite depuis le tracé précédent. */
  private arc(x: number, y: number, radius: number, start = 0, end = 2 * Math.PI) {
    this.context.moveTo(this.x(x + radius * Math.cos(start)), this.y(y + radius * Math.sin(start)))
    this.context.arc(this.x(x), this.y(y), this.size(radius), start, end)
  }
}
