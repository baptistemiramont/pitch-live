import './style.css'
import { connect, type ConnectionStatus } from './connection.ts'
import { LatencyWindow, now } from './latency.ts'
import { PROTOCOL_VERSION, type Frame, type MatchMessage, type PlayerProfile, type ServerMessage } from './protocol.ts'
import { PitchRenderer } from './render.ts'
import { interpolate, type ReceivedFrame, type ShownFrame } from './smoothing.ts'

const SERVER_URL = `ws://${location.hostname}:8080/match`

const STATUS_LABELS: Record<ConnectionStatus, string> = {
  connecting: 'Connexion…',
  open: 'Connecté',
  closed: 'Déconnecté, nouvelle tentative…',
}

// L'état partagé entre la réception et le dessin : de simples variables, sans
// framework. La réception les remplace à chaque message ; le dessin les lit au
// rythme de l'écran. Avec l'état d'un framework comme React, chaque image (25 par
// seconde) déclencherait un nouveau rendu de composants pour rien.
let match: MatchMessage | null = null
let profiles = new Map<string, PlayerProfile>()
let latest: ReceivedFrame | null = null
// L'avant-dernière image ne sert qu'au lissage du mouvement
let previous: ReceivedFrame | null = null
let smoothing = false
let receivedThisSecond = 0

// Les images sautées se voient aux trous dans les numéros : à 5 images par seconde,
// le serveur n'envoie qu'une image sur cinq, toujours la plus récente.
let skippedThisSecond = 0
let requestedRate = 25

// Deux latences, mesurées depuis la publication par le serveur (sentAt) :
// - à la réception : file d'attente du serveur, envoi, réseau, boucle d'événements ;
// - à l'écran : l'âge de ce qui est affiché, relevé à chaque rafraîchissement de
//   l'écran. C'est la latence que vit l'analyste : une image vieillit tant que la
//   suivante n'est pas arrivée, et le lissage ajoute une image de retard. Les pixels
//   n'apparaissent qu'au rafraîchissement suivant, que la page ne voit pas.
const receptionLatency = new LatencyWindow()
const screenLatency = new LatencyWindow()

const renderer = new PitchRenderer(document.querySelector<HTMLCanvasElement>('#pitch')!)
const statusLabel = document.querySelector<HTMLElement>('#status')!
const clockLabel = document.querySelector<HTMLElement>('#clock')!
const receivedLabel = document.querySelector<HTMLElement>('#received')!
const rateSelect = document.querySelector<HTMLSelectElement>('#rate')!
const smoothingCheckbox = document.querySelector<HTMLInputElement>('#smoothing')!
const latencyLabel = document.querySelector<HTMLElement>('#latency')!

// Réception : aucun dessin ici, on garde seulement les deux dernières images.
function onMessage(message: ServerMessage, receivedAt: number) {
  if (message.v !== PROTOCOL_VERSION) {
    console.warn(`Message en version ${message.v} ignoré, ce client parle la version ${PROTOCOL_VERSION}`)
    return
  }
  switch (message.type) {
    case 'match':
      // Nouvelle connexion : les images d'avant ne doivent pas servir au lissage
      match = message
      profiles = new Map(message.players.map((player) => [player.id, player]))
      previous = null
      latest = null
      break
    case 'frame': {
      const frame = message.frame
      if (latest && frame.id > latest.frame.id + 1) {
        skippedThisSecond += frame.id - latest.frame.id - 1
      }
      // Un numéro plus petit que le précédent : le match a repris au coup d'envoi,
      // et glisser de la fin du match vers le début n'aurait pas de sens.
      previous = latest && frame.id > latest.frame.id ? latest : null
      latest = { frame, sentAt: message.sentAt, receivedAt }
      receptionLatency.add(receivedAt - message.sentAt)
      receivedThisSecond++
      break
    }
  }
}

// Dessin : une fois par rafraîchissement de l'écran (60 fois par seconde en général).
// Sans lissage, on ne redessine qu'à l'arrivée d'une image ; des messages arrivés en
// rafale entre deux rafraîchissements ne coûtent qu'une affectation chacun. Avec le
// lissage, chaque rafraîchissement montre un état intermédiaire différent.
// Dans un onglet en arrière-plan, le navigateur suspend requestAnimationFrame : on ne
// dessine plus, mais la réception continue.
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

// Le bandeau du haut n'a pas besoin de suivre l'écran : une mise à jour par seconde suffit.
setInterval(() => {
  receivedLabel.textContent = `${receivedThisSecond} images reçues par seconde, ${skippedThisSecond} sautées`
  receivedThisSecond = 0
  skippedThisSecond = 0

  const reception = receptionLatency.flush()
  const screen = screenLatency.flush()
  const parts = []
  if (reception) {
    parts.push(`à la réception ${formatMs(reception.average)} (max ${formatMs(reception.max)})`)
  }
  if (screen) {
    parts.push(`à l'écran ${formatMs(screen.average)} (max ${formatMs(screen.max)})`)
  }
  latencyLabel.textContent = parts.length ? `Latence ${parts.join(', ')}` : ''

  if (latest) {
    const { period, time, ball } = latest.frame
    clockLabel.textContent = `Période ${period} · ${formatClock(time)}${ball ? '' : ' · ballon absent'}`
  }
}, 1000)

function formatMs(milliseconds: number) {
  return `${milliseconds.toLocaleString('fr-FR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} ms`
}

function formatClock(seconds: number) {
  const minutes = Math.floor(seconds / 60)
  return `${minutes}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`
}

const connection = connect(SERVER_URL, {
  onMessage,
  onStatus: (status) => {
    statusLabel.textContent = STATUS_LABELS[status]
    // Le serveur oublie tout d'un client à sa déconnexion : c'est au client de
    // redemander sa fréquence à chaque nouvelle connexion.
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

requestAnimationFrame(render)
