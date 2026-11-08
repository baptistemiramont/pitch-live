package dev.baptiste.pitch_live;

/**
 * The events of the match so far, already encoded as a JSON message, with a version
 * that changes every time the log changes (a new goal, a new card, a restart).
 *
 * The version lets each client's sender thread tell whether its browser already has
 * this log, without comparing the content.
 */
public record EventLog(long version, String json) {
}
