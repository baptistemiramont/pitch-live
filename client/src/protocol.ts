// Le contrat entre le serveur et le navigateur.
// Miroir de server/src/main/java/dev/baptiste/pitch_live/Protocol.java : toute
// modification là-bas doit être reportée ici, à la main.

export const PROTOCOL_VERSION = 1

export type Team = 'HOME' | 'AWAY'

/** En mètres, origine en haut à gauche du terrain, axe y vers le bas. */
export type Position = { x: number; y: number }

export type Pitch = { length: number; width: number }

export type PlayerProfile = { id: string; team: Team; number: number }

/** Un joueur présent sur le terrain ; les absents ne figurent pas dans l'image. */
export type Player = { id: string; position: Position }

export type Frame = {
  id: number
  period: number
  /** Temps de jeu en secondes depuis le coup d'envoi. */
  time: number
  players: Player[]
  /** null quand le ballon n'est pas suivi (arrêt de jeu, perte de suivi). */
  ball: Position | null
}

/** Reçu une fois, à la connexion : le terrain et la feuille de match. */
export type MatchMessage = { v: number; type: 'match'; pitch: Pitch; players: PlayerProfile[] }

/** Reçu à chaque image ; sentAt est l'heure de publication par le serveur, en ms depuis 1970. */
export type FrameMessage = { v: number; type: 'frame'; sentAt: number; frame: Frame }

export type ServerMessage = MatchMessage | FrameMessage

/** Envoyé au serveur : ne recevoir que fps images par seconde (de 1 à 25). */
export type RateRequest = { v: number; type: 'rate'; fps: number }

export type ClientMessage = RateRequest
