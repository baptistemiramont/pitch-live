package dev.baptiste.pitch_live;

/**
 * What we know about a player regardless of the moment: their team and shirt number.
 *
 * Profiles are sent once, in the match presentation (the roster). Frames only carry
 * the player id, to stay light at 25 messages per second.
 */
public record PlayerProfile(String id, Team team, int number) {
}
