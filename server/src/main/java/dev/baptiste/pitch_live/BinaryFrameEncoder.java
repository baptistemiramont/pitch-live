package dev.baptiste.pitch_live;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encode une image en binaire : 135 octets pour 22 joueurs, contre 1 240 en JSON.
 *
 * Disposition, en big-endian (l'ordre par défaut de ByteBuffer en Java et de DataView
 * dans le navigateur) ; miroir de decodeBinaryFrame dans client/src/protocol.ts :
 *
 * <pre>
 * octet  taille  champ
 *  0     1       version du protocole
 *  1     1       type de message (1 = image)
 *  2     8       sentAt, en millisecondes depuis 1970 (double)
 * 10     4       numéro de l'image
 * 14     1       période
 * 15     4       temps de jeu, en millisecondes
 * 19     1       ballon présent (1) ou absent (0)
 * 20     2       ballon x, en centimètres (entier signé : le ballon peut sortir du terrain)
 * 22     2       ballon y, en centimètres
 * 24     1       nombre de joueurs présents n
 * 25     5 × n   pour chaque joueur : son rang dans la feuille de match (1 octet),
 *                puis x et y en centimètres (2 octets chacun)
 * </pre>
 *
 * Le gain vient de trois choix : plus de noms de champs, le joueur désigné par son rang
 * dans la présentation au lieu de "Player11", et des positions en centimètres sur deux
 * octets au lieu d'un nombre décimal écrit en texte.
 *
 * Sur le réseau, l'écart est bien moindre : navigateur et Tomcat négocient par défaut
 * la compression permessage-deflate, qui ramène le JSON à environ 176 octets et le
 * binaire à 126 (estimé en rejouant cette compression sur 250 images). Le vrai gain du
 * binaire est ailleurs : la compression se fait connexion par connexion, donc une fois
 * par client et par image, alors que le binaire compact s'encode une fois pour tous.
 */
public class BinaryFrameEncoder {

	static final byte FRAME_TYPE = 1;
	private static final int HEADER_SIZE = 25;
	private static final int PLAYER_SIZE = 5;

	/** Le rang de chaque joueur dans la feuille de match envoyée à la connexion. */
	private final Map<String, Integer> rankById = new HashMap<>();

	public BinaryFrameEncoder(List<PlayerProfile> profiles) {
		for (int rank = 0; rank < profiles.size(); rank++) {
			rankById.put(profiles.get(rank).id(), rank);
		}
	}

	public byte[] encode(double sentAt, Frame frame) {
		ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + PLAYER_SIZE * frame.players().size());
		buffer.put((byte) Protocol.VERSION);
		buffer.put(FRAME_TYPE);
		buffer.putDouble(sentAt);
		buffer.putInt(frame.id());
		buffer.put((byte) frame.period());
		buffer.putInt((int) Math.round(frame.time() * 1000));

		Position ball = frame.ball();
		buffer.put((byte) (ball == null ? 0 : 1));
		buffer.putShort(ball == null ? 0 : centimetres(ball.x()));
		buffer.putShort(ball == null ? 0 : centimetres(ball.y()));

		buffer.put((byte) frame.players().size());
		for (Player player : frame.players()) {
			buffer.put(rankById.get(player.id()).byteValue());
			buffer.putShort(centimetres(player.position().x()));
			buffer.putShort(centimetres(player.position().y()));
		}
		return buffer.array();
	}

	/** Deux octets signés couvrent ±327 m : largement de quoi décrire un terrain de 105 m. */
	private static short centimetres(double metres) {
		return (short) Math.round(metres * 100);
	}
}
