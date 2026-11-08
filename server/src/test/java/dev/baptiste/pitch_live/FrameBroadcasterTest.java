package dev.baptiste.pitch_live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * La propriété centrale du serveur : un client lent ne retarde personne, et il
 * reçoit la dernière image plutôt qu'un arriéré.
 */
class FrameBroadcasterTest {

	private final FrameBroadcaster broadcaster = new FrameBroadcaster();

	FrameBroadcasterTest() {
		broadcaster.setPresentation("match");
	}

	@AfterEach
	void disconnectEveryone() {
		broadcaster.disconnect("fast");
		broadcaster.disconnect("slow");
		broadcaster.disconnect("late");
	}

	@Test
	void aSlowClientDoesNotHoldBackTheOthers() throws InterruptedException {
		FakeClient fast = new FakeClient(Duration.ZERO);
		FakeClient slow = new FakeClient(Duration.ofMillis(300));
		broadcaster.connect("fast", fast);
		broadcaster.connect("slow", slow);

		// 20 images, une toutes les 10 ms, comme une horloge accélérée
		long publishingNanos = 0;
		for (int i = 1; i <= 20; i++) {
			long start = System.nanoTime();
			broadcaster.publish("frame-" + i);
			publishingNanos += System.nanoTime() - start;
			Thread.sleep(10);
		}

		// Publier n'a jamais attendu le client lent : 20 dépôts en bien moins de 300 ms
		assertTrue(Duration.ofNanos(publishingNanos).toMillis() < 50,
				"publish a bloqué " + Duration.ofNanos(publishingNanos).toMillis() + " ms");

		// Le client rapide a presque tout reçu, et le client lent finit sur la dernière image
		waitUntil(() -> fast.lastReceived().equals("frame-20") && slow.lastReceived().equals("frame-20"));
		assertTrue(fast.frameCount() >= 15, "le client rapide n'a reçu que " + fast.frameCount() + " images");

		// … en sautant presque toutes les autres au lieu de les accumuler
		assertTrue(slow.frameCount() <= 3, "le client lent a reçu " + slow.frameCount() + " images");
	}

	@Test
	void aLateClientGetsThePresentationThenTheCurrentFrameAtOnce() {
		broadcaster.publish("frame-1");
		broadcaster.publish("frame-2");

		FakeClient late = new FakeClient(Duration.ZERO);
		broadcaster.connect("late", late);

		waitUntil(() -> late.received.size() == 2);
		assertEquals(List.of("match", "frame-2"), late.received);
	}

	/** Un faux navigateur : il note ce qu'il reçoit, et met readTime à lire chaque message. */
	private static final class FakeClient implements ClientChannel {

		final List<String> received = new CopyOnWriteArrayList<>();
		private final Duration readTime;

		FakeClient(Duration readTime) {
			this.readTime = readTime;
		}

		@Override
		public void send(String message) throws IOException {
			received.add(message);
			try {
				Thread.sleep(readTime);
			} catch (InterruptedException e) {
				throw new InterruptedIOException();
			}
		}

		@Override
		public void close() {
		}

		String lastReceived() {
			return received.isEmpty() ? "" : received.getLast();
		}

		long frameCount() {
			return received.stream().filter(message -> message.startsWith("frame-")).count();
		}
	}

	private static void waitUntil(BooleanSupplier condition) {
		long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("condition non atteinte en 2 s");
			}
			Thread.onSpinWait();
		}
	}
}
