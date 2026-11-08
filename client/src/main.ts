import './style.css'
import { connect, type ConnectionStatus } from './connection.ts'
import { PROTOCOL_VERSION, type Frame, type MatchMessage, type PlayerProfile, type ServerMessage } from './protocol.ts'
import { PitchRenderer } from './render.ts'

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
let latestFrame: Frame | null = null
let receivedThisSecond = 0

// Les images sautées se voient aux trous dans les numéros : à 5 images par seconde,
// le serveur n'envoie qu'une image sur cinq, toujours la plus récente.
let lastFrameId: number | null = null
let skippedThisSecond = 0
let requestedRate = 25

const renderer = new PitchRenderer(document.querySelector<HTMLCanvasElement>('#pitch')!)
const statusLabel = document.querySelector<HTMLElement>('#status')!
const clockLabel = document.querySelector<HTMLElement>('#clock')!
const receivedLabel = document.querySelector<HTMLElement>('#received')!
const rateSelect = document.querySelector<HTMLSelectElement>('#rate')!

// Réception : aucun dessin ici, on garde seulement la dernière image.
function onMessage(message: ServerMessage) {
  if (message.v !== PROTOCOL_VERSION) {
    console.warn(`Message en version ${message.v} ignoré, ce client parle la version ${PROTOCOL_VERSION}`)
    return
  }
  switch (message.type) {
    case 'match':
      match = message
      profiles = new Map(message.players.map((player) => [player.id, player]))
      break
    case 'frame':
      // Un numéro plus petit que le précédent : le match a repris au coup d'envoi
      if (lastFrameId !== null && message.frame.id > lastFrameId + 1) {
        skippedThisSecond += message.frame.id - lastFrameId - 1
      }
      lastFrameId = message.frame.id
      latestFrame = message.frame
      receivedThisSecond++
      break
  }
}

// Dessin : une fois par rafraîchissement de l'écran (60 fois par seconde en général),
// et seulement s'il y a du nouveau. Des messages arrivés en rafale entre deux
// rafraîchissements ne coûtent qu'une affectation chacun : seul le dernier est dessiné.
// Dans un onglet en arrière-plan, le navigateur suspend requestAnimationFrame : on ne
// dessine plus, mais la réception continue.
let lastDrawn: Frame | null = null

function render() {
  if (match && latestFrame) {
    const resized = renderer.fitToScreen()
    if (resized || latestFrame !== lastDrawn) {
      renderer.draw(match.pitch, profiles, latestFrame)
      lastDrawn = latestFrame
    }
  }
  requestAnimationFrame(render)
}

// Le bandeau du haut n'a pas besoin de suivre l'écran : une mise à jour par seconde suffit.
setInterval(() => {
  receivedLabel.textContent = `${receivedThisSecond} images reçues par seconde, ${skippedThisSecond} sautées`
  receivedThisSecond = 0
  skippedThisSecond = 0
  if (latestFrame) {
    clockLabel.textContent = `Période ${latestFrame.period} · ${formatClock(latestFrame.time)}${latestFrame.ball ? '' : ' · ballon absent'}`
  }
}, 1000)

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

requestAnimationFrame(render)
