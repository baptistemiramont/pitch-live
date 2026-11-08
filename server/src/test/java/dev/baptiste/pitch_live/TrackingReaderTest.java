package dev.baptiste.pitch_live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A plain JUnit test, without Spring: it runs in a fraction of a second. */
class TrackingReaderTest {

	// Maven runs the tests from server/, hence the ../
	static final Path HOME = Path.of("../data/Sample_Game_1/Sample_Game_1_RawTrackingData_Home_Team.csv");
	static final Path AWAY = Path.of("../data/Sample_Game_1/Sample_Game_1_RawTrackingData_Away_Team.csv");

	@BeforeEach
	void requireData() {
		assumeTrue(Files.exists(HOME) && Files.exists(AWAY), "Data missing: run make data");
	}

	@Test
	void readsTheFirstFrameInMetres() throws IOException {
		try (var reader = new TrackingReader(HOME, AWAY)) {
			Frame frame = reader.next().orElseThrow();

			assertEquals(1, frame.id());
			assertEquals(1, frame.period());
			assertEquals(0.04, frame.time(), 1e-9);

			// 14 columns per team, including 3 substitutes still at NaN at kick-off
			assertEquals(22, frame.players().size());
			assertTrue(frame.players().stream().noneMatch(player -> player.id().equals("Player12")));

			// Decimals are compared with a tolerance, never for strict equality;
			// half a centimetre here, since positions are rounded to the centimetre
			Position player11 = positionOf(frame, "Player11");
			assertEquals(0.00082 * 105, player11.x(), 0.005);
			assertEquals(0.48238 * 68, player11.y(), 0.005);

			assertEquals(0.45472 * 105, frame.ball().x(), 0.005);
			assertEquals(0.38709 * 68, frame.ball().y(), 0.005);
		}
	}

	@Test
	void describesBothSquadsFromTheHeaders() throws IOException {
		try (var reader = new TrackingReader(HOME, AWAY)) {
			var profiles = reader.profiles();

			assertEquals(28, profiles.size());
			assertEquals(new PlayerProfile("Player11", Team.HOME, 11), profiles.getFirst());
			assertTrue(profiles.contains(new PlayerProfile("Player25", Team.AWAY, 25)));
		}
	}

	@Test
	void streamsTheWholeMatchThenReportsTheEnd() throws IOException {
		try (var reader = new TrackingReader(HOME, AWAY)) {
			int count = 0;
			Frame last = null;
			for (Optional<Frame> frame = reader.next(); frame.isPresent(); frame = reader.next()) {
				count++;
				last = frame.get();
			}

			assertEquals(145_006, count);
			assertEquals(2, last.period());
			assertTrue(reader.next().isEmpty());
		}
	}

	@Test
	void reportsAMissingBallAsNull() throws IOException {
		try (var reader = new TrackingReader(HOME, AWAY)) {
			Frame frame;
			do {
				frame = reader.next().orElseThrow();
			} while (frame.ball() != null);

			assertNull(frame.ball());
		}
	}

	private static Position positionOf(Frame frame, String id) {
		return frame.players().stream()
				.filter(player -> player.id().equals(id))
				.findFirst()
				.orElseThrow()
				.position();
	}
}
