package dev.baptiste.pitch_live;

import java.util.List;

/**
 * Le contrat entre le serveur et les navigateurs, en JSON.
 *
 * Il est tenu à la main en miroir de client/src/protocol.ts : toute modification
 * ici doit y être reportée. Chaque message porte sa version (v) ; un changement
 * incompatible créera une version 2, et le serveur acceptera les deux le temps
 * de la transition.
 */
public final class Protocol {

	public static final int VERSION = 1;

	private Protocol() {
	}

	/** Dimensions du terrain, en mètres. */
	public record Pitch(double length, double width) {
	}

	/**
	 * Envoyé une fois, à la connexion : le terrain et la feuille de match complète,
	 * remplaçants compris. Un analyste qui arrive en cours de match a ainsi tout ce
	 * qu'il faut pour interpréter les images qui suivent.
	 */
	public record MatchMessage(int v, String type, Pitch pitch, List<PlayerProfile> players) {
		public MatchMessage(Pitch pitch, List<PlayerProfile> players) {
			this(VERSION, "match", pitch, List.copyOf(players));
		}
	}

	/**
	 * Envoyé à chaque image. sentAt est l'heure de publication par le serveur, en
	 * millisecondes depuis 1970, pour que le navigateur mesure la latence.
	 */
	public record FrameMessage(int v, String type, double sentAt, Frame frame) {
		public FrameMessage(double sentAt, Frame frame) {
			this(VERSION, "frame", sentAt, frame);
		}
	}
}
