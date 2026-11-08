package dev.baptiste.pitch_live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.baptiste.pitch_live.MatchEvent.Kind;

class EventReaderTest {

	static final Path EVENTS = Path.of("../data/Sample_Game_1/Sample_Game_1_RawEventsData.csv");

	@Test
	void keepsOnlyTheGoalsAndCardsInOrder() throws IOException {
		assumeTrue(Files.exists(EVENTS), "Data missing: run make data");

		List<MatchEvent> events = EventReader.read(EVENTS);

		// Match 1 ends 3-0 for the home team, with four yellow cards
		assertEquals(3, events.stream().filter(event -> event.kind() == Kind.GOAL).count());
		assertEquals(4, events.stream().filter(event -> event.kind() == Kind.YELLOW_CARD).count());
		assertEquals(new MatchEvent(2289, 1, 91.56, Team.HOME, Kind.GOAL, "Player9"), events.getFirst());
		assertEquals(events.stream().sorted((a, b) -> Integer.compare(a.frame(), b.frame())).toList(), events);
	}
}
