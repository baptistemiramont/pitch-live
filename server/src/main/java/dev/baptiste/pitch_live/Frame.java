package dev.baptiste.pitch_live;

import java.util.List;

public record Frame(int id,
		int period,
		double time,
		List<Player> players,
		Position ball) {
	public Frame {
		players = List.copyOf(players);
	}
}
