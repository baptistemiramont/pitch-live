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

/** Un test JUnit simple, sans Spring : il s'exécute en une fraction de seconde. */
class TrackingReaderTest {

	// Maven lance les tests depuis server/, d'où le ../
	static final Path HOME = Path.of("../data/Sample_Game_1/Sample_Game_1_RawTrackingData_Home_Team.csv");
	static final Path AWAY = Path.of("../data/Sample_Game_1/Sample_Game_1_RawTrackingData_Away_Team.csv");

	@BeforeEach
	void requireData() {
		assumeTrue(Files.exists(HOME) && Files.exists(AWAY), "Données absentes : lancer make data");
	}

	@Test
	void readsTheFirstFrameInMetres() throws IOException {
		try (var reader = new TrackingReader(HOME, AWAY)) {
			Frame frame = reader.next().orElseThrow();

			assertEquals(1, frame.id());
			assertEquals(1, frame.period());
			assertEquals(0.04, frame.time(), 1e-9);

			// 14 colonnes par équipe, dont 3 remplaçants encore à NaN au coup d'envoi
			assertEquals(22, frame.players().size());
			assertTrue(frame.players().stream().noneMatch(player -> player.id().equals("Player12")));

			// Les décimaux se comparent avec une tolérance, jamais à l'égalité stricte
			Position player11 = positionOf(frame, "Player11");
			assertEquals(0.00082 * 105, player11.x(), 1e-6);
			assertEquals(0.48238 * 68, player11.y(), 1e-6);

			assertEquals(0.45472 * 105, frame.ball().x(), 1e-6);
			assertEquals(0.38709 * 68, frame.ball().y(), 1e-6);
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
