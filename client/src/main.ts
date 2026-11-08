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

// Le format se choisit à la connexion, par l'adresse de la page : ?format=binary
const BINARY = new URLSearchParams(location.search).get('format') === 'binary'
const SERVER_URL = `ws://${location.hostname}:8080/match${BINARY ? '?format=binary' : ''}`

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
let slowClient = false
let receivedThisSecond = 0
let bytesThisSecond = 0

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
const slowClientCheckbox = document.querySelector<HTMLInputElement>('#slow-client')!
const formatSelect = document.querySelector<HTMLSelectElement>('#format')!
const latencyLabel = document.querySelector<HTMLElement>('#latency')!

// Réception : aucun dessin ici, on garde seulement les deux dernières images.
function onMessage(message: ServerMessage, receivedAt: number, size: number) {
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
      if (slowClient) {
        simulateHeavyWork()
      }
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
      bytesThisSecond += size
      break
    }
  }
}

// Une image binaire ne se comprend qu'avec la feuille de match, reçue en JSON juste
// avant : le serveur envoie toujours la présentation en premier.
function onBinary(data: ArrayBuffer, receivedAt: number) {
  const message = match && decodeBinaryFrame(data, match.players)
  if (message) {
    onMessage(message, receivedAt, data.byteLength)
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
// Les compteurs sont divisés par le temps réellement écoulé : quand la page est
// surchargée, ce minuteur se déclenche en retard, et pas toutes les secondes.
let lastHudUpdate = performance.now()

setInterval(() => {
  const elapsedSeconds = (performance.now() - lastHudUpdate) / 1000
  lastHudUpdate = performance.now()
  const received = Math.round(receivedThisSecond / elapsedSeconds)
  const skipped = Math.round(skippedThisSecond / elapsedSeconds)
  const averageSize = receivedThisSecond ? Math.round(bytesThisSecond / receivedThisSecond) : 0
  receivedLabel.textContent =
    `${plural(received, 'image reçue', 'images reçues')} par seconde, ${plural(skipped, 'sautée', 'sautées')}, ` +
    `${averageSize.toLocaleString('fr-FR')} octets par image`
  receivedThisSecond = 0
  skippedThisSecond = 0
  bytesThisSecond = 0

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

/** En français, 0 et 1 sont au singulier. */
function plural(count: number, singular: string, pluralForm: string) {
  return `${count} ${count > 1 ? pluralForm : singular}`
}

function formatMs(milliseconds: number) {
  return `${milliseconds.toLocaleString('fr-FR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} ms`
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

// Changer de format, c'est ouvrir une autre connexion : on recharge la page
formatSelect.value = BINARY ? 'binary' : 'json'
formatSelect.addEventListener('change', () => {
  location.search = formatSelect.value === 'binary' ? '?format=binary' : ''
})

slowClientCheckbox.addEventListener('change', () => {
  slowClient = slowClientCheckbox.checked
})

/**
 * Simule un client lent pour de vrai : chaque image occupe le fil principal 60 ms,
 * plus que les 40 ms qui les séparent. La page n'en traite que 17 par seconde sur 25.
 *
 * Ce que la mesure a montré : le retard grandit sans limite, d'un tiers de seconde
 * chaque seconde (32 s de retard au bout de 95 s), et le serveur ne saute aucune
 * image. Sa boîte aux lettres d'une place ne protège que d'un envoi qui bloque ; or
 * ici l'envoi ne bloque pas. Les images s'entassent dans les tampons entre le serveur
 * et la page (ceux du système, jusqu'à 4 Mo de chaque côté sur macOS, et ceux du
 * navigateur) bien avant que le serveur ne ressente quoi que ce soit, et l'API
 * WebSocket du navigateur n'offre aucun moyen de lui dire de ralentir.
 *
 * Les parades : que le client demande lui-même moins d'images (à 10 par seconde, ce
 * client suit, avec une latence stable autour de 20 ms), ou un contrôle de flux dans
 * le protocole, où le client accuse réception de chaque image et où le serveur n'en
 * a jamais plus d'une en route vers lui.
 */
function simulateHeavyWork() {
  const end = performance.now() + 60
  while (performance.now() < end) {
    // attente active : rien d'autre ne peut s'exécuter sur la page pendant ce temps
  }
}

requestAnimationFrame(render)
