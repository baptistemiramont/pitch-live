package dev.baptiste.pitch_live;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lit en parallèle les deux fichiers de suivi de Metrica (domicile et extérieur),
 * une ligne de chacun à la fois, et en fait une {@link Frame}.
 *
 * Le match n'est jamais chargé en mémoire : le lecteur ne garde qu'une ligne par
 * fichier. C'est aussi la seule classe qui connaît le format de Metrica ; le reste
 * du serveur ne manipule que des Frame en mètres.
 */
public class TrackingReader implements AutoCloseable {

	/** Dimensions du terrain des matchs d'exemple, d'après le README de Metrica. */
	public static final double PITCH_LENGTH = 105.0;
	public static final double PITCH_WIDTH = 68.0;

	/** Chaque ligne commence par trois colonnes fixes : Period, Frame, Time [s]. */
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
			// Sans ce try, un échec sur le second fichier laisserait le premier ouvert :
			// l'appelant ne reçoit jamais l'objet, il ne peut donc pas appeler close().
			home.close();
			throw e;
		}
		homeProfiles = readHeader(home, Team.HOME);
		awayProfiles = readHeader(away, Team.AWAY);
	}

	/** Les joueurs des deux équipes, remplaçants compris : la feuille de match. */
	public List<PlayerProfile> profiles() {
		List<PlayerProfile> all = new ArrayList<>(homeProfiles);
		all.addAll(awayProfiles);
		return List.copyOf(all);
	}

	/** L'image suivante, ou rien quand le match est fini. */
	public Optional<Frame> next() throws IOException {
		String homeLine = home.readLine();
		String awayLine = away.readLine();
		if (homeLine == null || awayLine == null) {
			return Optional.empty();
		}

		String[] homeColumns = homeLine.split(",");
		String[] awayColumns = awayLine.split(",");
		// Les deux fichiers sont alignés ligne à ligne. On le vérifie au lieu de le supposer :
		// un décalage produirait des images fausses sans la moindre erreur visible.
		if (!homeColumns[1].equals(awayColumns[1])) {
			throw new IOException("Fichiers désalignés : image " + homeColumns[1]
					+ " à domicile, image " + awayColumns[1] + " à l'extérieur");
		}

		List<Player> players = new ArrayList<>();
		addPlayersOnPitch(homeColumns, homeProfiles, players);
		addPlayersOnPitch(awayColumns, awayProfiles, players);

		// Le ballon figure dans les deux fichiers, avec les mêmes valeurs : on le lit à domicile.
		// Il vaut NaN sur 39 % du match ; il est alors absent (null), jamais inventé.
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
	 * Lit les trois lignes d'en-tête : l'équipe (ligne 1, déjà connue), les numéros
	 * de maillot (ligne 2) et les noms de colonnes (ligne 3). Chaque joueur occupe deux
	 * colonnes, x puis y, et seule la première porte son nom.
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

	/** Ajoute les joueurs présents ; un joueur à NaN (remplaçant, remplacé) est laissé de côté. */
	private static void addPlayersOnPitch(String[] columns, List<PlayerProfile> profiles, List<Player> players) {
		for (int i = 0; i < profiles.size(); i++) {
			String id = profiles.get(i).id();
			position(columns, FIRST_PLAYER_COLUMN + 2 * i)
					.ifPresent(position -> players.add(new Player(id, position)));
		}
	}

	/** Convertit une paire de colonnes (x, y) de 0..1 en mètres, ou rien si Metrica a écrit NaN. */
	private static Optional<Position> position(String[] columns, int xColumn) {
		double x = Double.parseDouble(columns[xColumn]);
		double y = Double.parseDouble(columns[xColumn + 1]);
		if (Double.isNaN(x) || Double.isNaN(y)) {
			return Optional.empty();
		}
		return Optional.of(new Position(toCentimetres(x * PITCH_LENGTH), toCentimetres(y * PITCH_WIDTH)));
	}

	/**
	 * Arrondit au centimètre. Le suivi n'est pas plus précis, et les décimales en plus
	 * ne sont que du bruit de calcul (0.7434000000000001) qui alourdit chaque image :
	 * mesuré sur les 100 premières images, 1 487 octets en moyenne avant l'arrondi,
	 * 1 240 après (17 % de moins).
	 */
	private static double toCentimetres(double metres) {
		return Math.round(metres * 100) / 100.0;
	}
}
