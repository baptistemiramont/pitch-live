package dev.baptiste.pitch_live;

/**
 * Ce qu'on sait d'un joueur indépendamment de l'instant : son équipe et son numéro.
 *
 * Les profils partent une seule fois, dans le message de présentation du match.
 * Les images ne portent que l'identifiant, pour rester légères à 25 envois par seconde.
 */
public record PlayerProfile(String id, Team team, int number) {
}
