package dev.baptiste.pitch_live;

/**
 * A notable event of the match, read from Metrica's event data: a goal or a card.
 *
 * Its frame number is aligned with the tracking data: that is what lets the clock
 * release each event exactly when it happens in the replay.
 */
public record MatchEvent(int frame, int period, double time, Team team, Kind kind, String player) {

	public enum Kind {
		GOAL,
		YELLOW_CARD,
		RED_CARD
	}
}
