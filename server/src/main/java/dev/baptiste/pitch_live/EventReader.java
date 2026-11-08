package dev.baptiste.pitch_live;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import dev.baptiste.pitch_live.MatchEvent.Kind;

/**
 * Reads Metrica's event file and keeps what an analyst wants to see live: goals and cards.
 *
 * Unlike tracking, the file is small (1,745 events, 140 KB for a whole match): it is
 * loaded in one go. Passes, recoveries and challenges are left out.
 */
final class EventReader {

	// Team,Type,Subtype,Period,Start Frame,Start Time [s],End Frame,End Time [s],From,To,…
	private static final int TEAM = 0;
	private static final int TYPE = 1;
	private static final int SUBTYPE = 2;
	private static final int PERIOD = 3;
	private static final int START_FRAME = 4;
	private static final int START_TIME = 5;
	private static final int FROM = 8;

	private EventReader() {
	}

	/** The goals and cards of the match, in the order they happen. */
	static List<MatchEvent> read(Path file) throws IOException {
		try (Stream<String> lines = Files.lines(file)) {
			return lines.skip(1)
					.map(line -> line.split(",", -1))
					.map(EventReader::toEvent)
					.flatMap(Optional::stream)
					.toList();
		}
	}

	private static Optional<MatchEvent> toEvent(String[] columns) {
		String type = columns[TYPE];
		String subtype = columns[SUBTYPE];
		Kind kind;
		if (type.equals("SHOT") && subtype.endsWith("-GOAL")) {
			kind = Kind.GOAL;
		} else if (type.equals("CARD")) {
			kind = subtype.contains("RED") ? Kind.RED_CARD : Kind.YELLOW_CARD;
		} else {
			return Optional.empty();
		}
		return Optional.of(new MatchEvent(
				Integer.parseInt(columns[START_FRAME]),
				Integer.parseInt(columns[PERIOD]),
				Double.parseDouble(columns[START_TIME]),
				Team.valueOf(columns[TEAM].toUpperCase()),
				kind,
				columns[FROM]));
	}
}
