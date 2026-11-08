package dev.baptiste.pitch_live;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads Metrica's two tracking files (home and away) side by side, one line of each
 * at a time, and turns them into a {@link Frame}.
 *
 * The match is never loaded into memory: the reader only holds one line per file.
 * It is also the only class that knows Metrica's format; the rest of the server only
 * deals with frames in metres.
 */
public class TrackingReader implements AutoCloseable {

	/** Pitch dimensions of the sample matches, according to Metrica's README. */
	public static final double PITCH_LENGTH = 105.0;
	public static final double PITCH_WIDTH = 68.0;

	/** Every line starts with three fixed columns: Period, Frame, Time [s]. */
	private static final int FIRST_PLAYER_COLUMN = 3;

	private final BufferedReader home;
	private final BufferedReader away;
	private final List<PlayerProfile> homeProfiles;
	private final List<PlayerProfile> awayProfiles;

	public TrackingReader(Path homeFile, Path awayFile) throws IOException {
		home = Files.newBufferedReader(homeFile);
		try {
			away = Files.newBufferedReader(awayFile);
		} catch (IOException e) {
			// Without this try, a failure on the second file would leave the first one
			// open: the caller never gets the object, so it can never call close().
			home.close();
			throw e;
		}
		homeProfiles = readHeader(home, Team.HOME);
		awayProfiles = readHeader(away, Team.AWAY);
	}

	/** The players of both teams, substitutes included: the roster. */
	public List<PlayerProfile> profiles() {
		List<PlayerProfile> all = new ArrayList<>(homeProfiles);
		all.addAll(awayProfiles);
		return List.copyOf(all);
	}

	/** The next frame, or nothing once the match is over. */
	public Optional<Frame> next() throws IOException {
		String homeLine = home.readLine();
		String awayLine = away.readLine();
		if (homeLine == null || awayLine == null) {
			return Optional.empty();
		}

		String[] homeColumns = homeLine.split(",");
		String[] awayColumns = awayLine.split(",");
		// The two files are aligned line by line. We check it instead of assuming it:
		// an offset would produce wrong frames without any visible error.
		if (!homeColumns[1].equals(awayColumns[1])) {
			throw new IOException("Misaligned files: frame " + homeColumns[1]
					+ " at home, frame " + awayColumns[1] + " away");
		}

		List<Player> players = new ArrayList<>();
		addPlayersOnPitch(homeColumns, homeProfiles, players);
		addPlayersOnPitch(awayColumns, awayProfiles, players);

		// The ball is in both files, with the same values: we read it from the home file.
		// It is NaN for 39% of the match; it is then missing (null), never made up.
		Position ball = position(homeColumns, FIRST_PLAYER_COLUMN + 2 * homeProfiles.size()).orElse(null);

		return Optional.of(new Frame(
				Integer.parseInt(homeColumns[1]),
				Integer.parseInt(homeColumns[0]),
				Double.parseDouble(homeColumns[2]),
				players,
				ball));
	}

	@Override
	public void close() throws IOException {
		try {
			home.close();
		} finally {
			away.close();
		}
	}

	/**
	 * Reads the three header lines: the team (line 1, already known), the shirt numbers
	 * (line 2) and the column names (line 3). Each player takes two columns, x then y,
	 * and only the first one carries the player's name.
	 */
	private static List<PlayerProfile> readHeader(BufferedReader reader, Team team) throws IOException {
		reader.readLine();
		String[] numbers = reader.readLine().split(",");
		String[] names = reader.readLine().split(",");

		List<PlayerProfile> profiles = new ArrayList<>();
		for (int column = FIRST_PLAYER_COLUMN; names[column].startsWith("Player"); column += 2) {
			profiles.add(new PlayerProfile(names[column], team, Integer.parseInt(numbers[column])));
		}
		return profiles;
	}

	/** Adds the players on the pitch; a player at NaN (substitute, substituted) is left out. */
	private static void addPlayersOnPitch(String[] columns, List<PlayerProfile> profiles, List<Player> players) {
		for (int i = 0; i < profiles.size(); i++) {
			String id = profiles.get(i).id();
			position(columns, FIRST_PLAYER_COLUMN + 2 * i)
					.ifPresent(position -> players.add(new Player(id, position)));
		}
	}

	/** Converts a pair of columns (x, y) from 0..1 to metres, or nothing if Metrica wrote NaN. */
	private static Optional<Position> position(String[] columns, int xColumn) {
		double x = Double.parseDouble(columns[xColumn]);
		double y = Double.parseDouble(columns[xColumn + 1]);
		if (Double.isNaN(x) || Double.isNaN(y)) {
			return Optional.empty();
		}
		return Optional.of(new Position(toCentimetres(x * PITCH_LENGTH), toCentimetres(y * PITCH_WIDTH)));
	}

	/**
	 * Rounds to the centimetre. Tracking is not more accurate than that, and the extra
	 * decimals are just floating-point noise (0.7434000000000001) that makes every frame
	 * heavier: measured over the first 100 frames, 1,487 bytes on average before rounding,
	 * 1,240 after (17% less).
	 */
	private static double toCentimetres(double metres) {
		return Math.round(metres * 100) / 100.0;
	}
}
