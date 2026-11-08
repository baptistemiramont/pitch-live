package dev.baptiste.pitch_live;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Relit l'encodage champ par champ, comme le fera decodeBinaryFrame dans le navigateur. */
class BinaryFrameEncoderTest {

	private final BinaryFrameEncoder encoder = new BinaryFrameEncoder(List.of(
			new PlayerProfile("Player11", Team.HOME, 11),
			new PlayerProfile("Player1", Team.HOME, 1),
			new PlayerProfile("Player25", Team.AWAY, 25)));

	@Test
	void encodesAFrameInTwentyFiveBytesPlusFivePerPlayer() {
		Frame frame = new Frame(71269, 2, 2850.76,
				List.of(new Player("Player25", new Position(104.5, 34.2)), new Player("Player1", new Position(-0.37, 67.99))),
				new Position(52.5, 34.0));

		ByteBuffer buffer = ByteBuffer.wrap(encoder.encode(1_794_151_634_269.365, frame));

		assertEquals(25 + 5 * 2, buffer.capacity());
		assertEquals(Protocol.VERSION, buffer.get());
		assertEquals(BinaryFrameEncoder.FRAME_TYPE, buffer.get());
		assertEquals(1_794_151_634_269.365, buffer.getDouble());
		assertEquals(71269, buffer.getInt());
		assertEquals(2, buffer.get());
		assertEquals(2_850_760, buffer.getInt());

		assertEquals(1, buffer.get());
		assertEquals(5250, buffer.getShort());
		assertEquals(3400, buffer.getShort());

		assertEquals(2, buffer.get());
		// Player25 est au rang 2 de la feuille de match
		assertEquals(2, buffer.get());
		assertEquals(10450, buffer.getShort());
		assertEquals(3420, buffer.getShort());
		// Une position négative (joueur hors du terrain) passe grâce aux entiers signés
		assertEquals(1, buffer.get());
		assertEquals(-37, buffer.getShort());
		assertEquals(6799, buffer.getShort());
	}

	@Test
	void encodesAMissingBallAsAbsent() {
		Frame frame = new Frame(1, 1, 0.04, List.of(), null);

		ByteBuffer buffer = ByteBuffer.wrap(encoder.encode(0, frame));

		assertEquals(25, buffer.capacity());
		assertEquals(0, buffer.get(19));
	}
}
