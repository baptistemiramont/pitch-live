/**
 * L'heure actuelle en millisecondes depuis 1970, avec des décimales : la même échelle
 * que le sentAt du serveur. Date.now() s'arrête à la milliseconde, trop grossier
 * pour des latences de l'ordre de la milliseconde en local.
 */
export function now() {
  return performance.timeOrigin + performance.now()
}

/**
 * Accumule des mesures de latence sur une fenêtre (une seconde dans le panneau),
 * puis en rend la moyenne et le maximum et repart de zéro.
 *
 * La limite de la mesure : sentAt vient de l'horloge du serveur, l'heure de réception
 * de celle du navigateur. Sur une même machine, c'est la même horloge et la mesure
 * est juste. Sur deux machines, l'écart entre leurs horloges (quelques millisecondes
 * au mieux avec NTP) s'ajoute à la mesure, et peut dépasser la latence elle-même.
 * Il faudrait alors estimer cet écart, par exemple avec un aller-retour ping/pong,
 * dont on suppose la moitié égale à l'aller.
 */
export class LatencyWindow {
  private sum = 0
  private count = 0
  private max = 0

  add(milliseconds: number) {
    this.sum += milliseconds
    this.count++
    this.max = Math.max(this.max, milliseconds)
  }

  /** Moyenne et maximum depuis le dernier appel, ou null s'il n'y a eu aucune mesure. */
  flush(): { average: number; max: number } | null {
    if (this.count === 0) {
      return null
    }
    const result = { average: this.sum / this.count, max: this.max }
    this.sum = 0
    this.count = 0
    this.max = 0
    return result
  }
}
