import type { Frame, Pitch, Player, PlayerProfile, Position, Team } from './protocol.ts'

/** Margin around the pitch, in metres: room for the goals and the players on the line. */
const MARGIN = 4

/** Regulation lines are 12 cm wide. */
const LINE_WIDTH = 0.12

/** Bigger than in real life, to stay readable. */
const PLAYER_RADIUS = 0.9
const BALL_RADIUS = 0.45

const TEAM_COLORS: Record<Team, string> = { HOME: '#e53935', AWAY: '#1e88e5' }
const GRASS = '#2e7d32'
const LINES = 'rgba(255, 255, 255, 0.8)'
const BALL = '#ffeb3b'

/**
 * Draws the pitch, the players and the ball in a 2D canvas.
 *
 * Everything arrives in metres, in the same coordinate system as the canvas (origin at
 * the top left, y pointing down): going from metres to pixels is just a scale and an
 * offset, recomputed on every draw.
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
      throw new Error('2D canvas unavailable')
    }
    this.canvas = canvas
    this.context = context
  }

  /**
   * Matches the canvas resolution to its size on screen. On a Retina display,
   * devicePixelRatio is 2: without this, the drawing would be blurry.
   * Returns true if the size changed, meaning a redraw is needed.
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

    // The pitch is redrawn on every frame: a dozen paths, which is negligible. With
    // heatmaps or trajectories, we would draw it once in an offscreen canvas, then
    // copy it over.
    this.drawPitch(pitch)
    for (const player of frame.players) {
      this.drawPlayer(player, profiles.get(player.id))
    }
    if (frame.ball) {
      this.drawBall(frame.ball)
    }
  }

  /** The largest pitch that fits in the canvas, centred. */
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
      // +1 from the left goal, -1 from the right one: towards the centre of the pitch
      const inward = goalLine === 0 ? 1 : -1
      this.rect(goalLine, centreY - 20.16, inward * 16.5, 40.32) // penalty area
      this.rect(goalLine, centreY - 9.16, inward * 5.5, 18.32) // goal area
      this.rect(goalLine, centreY - 3.66, -inward * 2, 7.32) // the goal, behind the line

      // The penalty arc: the part of the 9.15 m circle around the penalty spot (11 m
      // out) that sticks out of the area (16.5 m out), that is 5.5 m further.
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

  // From metres to canvas pixels

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

  /** An arc or a full circle. The moveTo avoids a stray line from the previous path. */
  private arc(x: number, y: number, radius: number, start = 0, end = 2 * Math.PI) {
    this.context.moveTo(this.x(x + radius * Math.cos(start)), this.y(y + radius * Math.sin(start)))
    this.context.arc(this.x(x), this.y(y), this.size(radius), start, end)
  }
}
