package dev.baptiste.pitch_live;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes a frame in binary: 135 bytes for 22 players, against 1,240 in JSON.
 *
 * Layout, big-endian (the default byte order of ByteBuffer in Java and of DataView in
 * the browser); mirrored by decodeBinaryFrame in client/src/protocol.ts:
 *
 * <pre>
 * offset size   field
 *  0     1       protocol version
 *  1     1       message type (1 = frame)
 *  2     8       sentAt, in milliseconds since 1970 (double)
 * 10     4       frame number
 * 14     1       period
 * 15     4       match time, in milliseconds
 * 19     1       ball present (1) or missing (0)
 * 20     2       ball x, in centimetres (signed: the ball can leave the pitch)
 * 22     2       ball y, in centimetres
 * 24     1       number of players on the pitch n
 * 25     5 × n   for each player: their rank in the roster (1 byte),
 *                then x and y in centimetres (2 bytes each)
 * </pre>
 *
 * The gain comes from three choices: no field names, the player referred to by their
 * rank in the presentation instead of "Player11", and positions in centimetres on two
 * bytes instead of a decimal number written out as text.
 *
 * On the wire, the difference is much smaller: the browser and Tomcat negotiate the
 * permessage-deflate compression by default, which brings JSON down to about 176 bytes
 * and binary to 126 (estimated by replaying that compression over 250 frames). And it
 * runs per connection, so once per client and per frame; Tomcat applies it to binary
 * messages too. Binary would only save that CPU by turning compression off for its
 * clients: it would then go out as is, 135 bytes encoded once for everyone.
 */
public class BinaryFrameEncoder {

	static final byte FRAME_TYPE = 1;
	private static final int HEADER_SIZE = 25;
	private static final int PLAYER_SIZE = 5;

	/** The rank of each player in the roster sent on connection. */
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

	/** Two signed bytes cover ±327 m: plenty for a 105 m pitch. */
	private static short centimetres(double metres) {
		return (short) Math.round(metres * 100);
	}
}
