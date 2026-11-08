import './style.css'
import { connect, type ConnectionStatus } from './connection.ts'
import { LatencyWindow, now } from './latency.ts'
import {
  PROTOCOL_VERSION,
  decodeBinaryFrame,
  type Frame,
  type MatchMessage,
  type PlayerProfile,
  type ServerMessage,
} from './protocol.ts'
import { PitchRenderer } from './render.ts'
import { interpolate, type ReceivedFrame, type ShownFrame } from './smoothing.ts'

// The format is chosen on connection, through the page address: ?format=binary
const BINARY = new URLSearchParams(location.search).get('format') === 'binary'

// By default, the server runs on port 8080 of the machine serving the page.
// VITE_SERVER_URL points to another one: a test server, a deployment.
const SERVER_URL =
  (import.meta.env.VITE_SERVER_URL ?? `ws://${location.hostname}:8080/match`) + (BINARY ? '?format=binary' : '')

const STATUS_LABELS: Record<ConnectionStatus, string> = {
  connecting: 'Connecting…',
  open: 'Connected',
  closed: 'Disconnected, retrying…',
}

// The state shared between receiving and drawing: plain variables, no framework.
// Receiving replaces them on every message; drawing reads them at the screen's pace.
// With a framework's state, like React's, every frame (25 per second) would trigger
// a component re-render for nothing.
let match: MatchMessage | null = null
let profiles = new Map<string, PlayerProfile>()
let latest: ReceivedFrame | null = null
// The frame before the latest one is only used to smooth the movement
let previous: ReceivedFrame | null = null
let smoothing = false
let slowClient = false
let receivedThisSecond = 0
let bytesThisSecond = 0

// Skipped frames show up as gaps in the frame numbers: at 5 frames per second, the
// server only sends one frame out of five, always the latest one.
let skippedThisSecond = 0
let requestedRate = 25

// Two latencies, measured from the moment the server published the frame (sentAt):
// - on receipt: server queueing, send, network, event loop;
// - on screen: the age of what is displayed, sampled on every screen refresh. This is
//   the latency the analyst experiences: a frame ages until the next one arrives, and
//   smoothing adds one frame of delay. Pixels only appear on the following refresh,
//   which the page cannot see.
const receptionLatency = new LatencyWindow()
const screenLatency = new LatencyWindow()

const renderer = new PitchRenderer(document.querySelector<HTMLCanvasElement>('#pitch')!)
const element = (selector: string) => document.querySelector<HTMLElement>(selector)!
const statusLabel = element('#status')
const periodLabel = element('#period')
const clockLabel = element('#clock')
const ballLabel = element('#ball')
const receivedLabel = element('#received')
const skippedLabel = element('#skipped')
const sizeLabel = element('#size')
const receptionLatencyLabel = element('#latency-reception')
const screenLatencyLabel = element('#latency-screen')
const rateSelect = document.querySelector<HTMLSelectElement>('#rate')!
const formatSelect = document.querySelector<HTMLSelectElement>('#format')!
const smoothingCheckbox = document.querySelector<HTMLInputElement>('#smoothing')!
const slowClientCheckbox = document.querySelector<HTMLInputElement>('#slow-client')!

// Receiving: no drawing here, we only keep the last two frames.
function onMessage(message: ServerMessage, receivedAt: number, size: number) {
  if (message.v !== PROTOCOL_VERSION) {
    console.warn(`Ignored a version ${message.v} message, this client speaks version ${PROTOCOL_VERSION}`)
    return
  }
  switch (message.type) {
    case 'match':
      // New connection: earlier frames must not be used for smoothing
      match = message
      profiles = new Map(message.players.map((player) => [player.id, player]))
      previous = null
      latest = null
      break
    case 'frame': {
      if (slowClient) {
        simulateHeavyWork()
      }
      const frame = message.frame
      if (latest && frame.id > latest.frame.id + 1) {
        skippedThisSecond += frame.id - latest.frame.id - 1
      }
      // A number lower than the previous one: the match restarted from kick-off, and
      // gliding from the end of the match to its start would make no sense.
      previous = latest && frame.id > latest.frame.id ? latest : null
      latest = { frame, sentAt: message.sentAt, receivedAt }
      receptionLatency.add(receivedAt - message.sentAt)
      receivedThisSecond++
      bytesThisSecond += size
      break
    }
  }
}

// A binary frame only makes sense with the roster, received in JSON just before:
// the server always sends the presentation first.
function onBinary(data: ArrayBuffer, receivedAt: number) {
  const message = match && decodeBinaryFrame(data, match.players)
  if (message) {
    onMessage(message, receivedAt, data.byteLength)
  }
}

// Drawing: once per screen refresh (usually 60 times per second). Without smoothing,
// we only redraw when a frame arrives; messages arriving in a burst between two
// refreshes only cost one assignment each. With smoothing, every refresh shows a
// different in-between state.
// In a background tab, the browser pauses requestAnimationFrame: drawing stops, but
// receiving goes on.
let lastDrawn: Frame | null = null

function render() {
  if (match && latest) {
    const resized = renderer.fitToScreen()
    const at = now()
    const shown: ShownFrame = smoothing && previous ? interpolate(previous, latest, at) : latest
    if (resized || shown.frame !== lastDrawn) {
      renderer.draw(match.pitch, profiles, shown.frame)
      lastDrawn = shown.frame
    }
    screenLatency.add(at - shown.sentAt)
  }
  requestAnimationFrame(render)
}

// The panel does not need to follow the screen: one update per second is enough.
// Counters are divided by the time actually elapsed: when the page is overloaded,
// this timer fires late, and not every second.
let lastPanelUpdate = performance.now()

setInterval(() => {
  const elapsedSeconds = (performance.now() - lastPanelUpdate) / 1000
  lastPanelUpdate = performance.now()
  receivedLabel.textContent = `${Math.round(receivedThisSecond / elapsedSeconds)} per second`
  skippedLabel.textContent = `${Math.round(skippedThisSecond / elapsedSeconds)} per second`
  if (receivedThisSecond) {
    sizeLabel.textContent = `${Math.round(bytesThisSecond / receivedThisSecond).toLocaleString('en-US')} bytes`
  }
  receivedThisSecond = 0
  skippedThisSecond = 0
  bytesThisSecond = 0

  showLatency(receptionLatencyLabel, receptionLatency.flush())
  showLatency(screenLatencyLabel, screenLatency.flush())

  if (latest) {
    const { period, time, ball } = latest.frame
    periodLabel.textContent = String(period)
    clockLabel.textContent = formatClock(time)
    ballLabel.textContent = ball ? 'tracked' : 'missing'
  }
}, 1000)

/** The average, with the maximum underneath, more discreet. */
function showLatency(label: HTMLElement, latency: { average: number; max: number } | null) {
  if (!latency) {
    return
  }
  const max = document.createElement('span')
  max.className = 'max'
  max.textContent = `max ${formatMs(latency.max)}`
  label.replaceChildren(formatMs(latency.average), max)
}

function formatMs(milliseconds: number) {
  return `${milliseconds.toLocaleString('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} ms`
}

function formatClock(seconds: number) {
  const minutes = Math.floor(seconds / 60)
  return `${minutes}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`
}

const connection = connect(SERVER_URL, {
  onMessage,
  onBinary,
  onStatus: (status) => {
    statusLabel.textContent = STATUS_LABELS[status]
    statusLabel.dataset.status = status
    // The server forgets everything about a client when it disconnects: it is up to
    // the client to ask for its rate again on every new connection.
    if (status === 'open' && requestedRate < 25) {
      requestRate()
    }
  },
})

function requestRate() {
  connection.send({ v: PROTOCOL_VERSION, type: 'rate', fps: requestedRate })
}

rateSelect.addEventListener('change', () => {
  requestedRate = Number(rateSelect.value)
  requestRate()
})

smoothingCheckbox.addEventListener('change', () => {
  smoothing = smoothingCheckbox.checked
})

// Switching format means opening another connection: we reload the page
formatSelect.value = BINARY ? 'binary' : 'json'
formatSelect.addEventListener('change', () => {
  location.search = formatSelect.value === 'binary' ? '?format=binary' : ''
})

slowClientCheckbox.addEventListener('change', () => {
  slowClient = slowClientCheckbox.checked
})

/**
 * Simulates a genuinely slow client: every frame keeps the main thread busy for 60 ms,
 * more than the 40 ms between frames. The page only handles 17 frames per second out of 25.
 *
 * What the measurement showed: the delay grows without bound, by a third of a second
 * every second (32 s behind after 95 s), and the server does not skip a single frame.
 * Its one-slot mailbox only protects against a send that blocks; here, sends do not
 * block. Frames pile up in the buffers between the server and the page (the OS ones, up
 * to 4 MB on each side on macOS, and the browser's) long before the server notices
 * anything, and the browser's WebSocket API offers no way to tell it to slow down.
 *
 * The remedies: the client asks for fewer frames itself (at 10 per second, this client
 * keeps up, with a steady latency around 20 ms), or flow control in the protocol, where
 * the client acknowledges every frame and the server never has more than one in flight
 * towards it.
 */
function simulateHeavyWork() {
  const end = performance.now() + 60
  while (performance.now() < end) {
    // busy wait: nothing else can run on the page meanwhile
  }
}

requestAnimationFrame(render)
