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
 * The core property of the server: a slow client holds nobody back, and it gets the
 * latest frame rather than a backlog.
 */
class FrameBroadcasterTest {

	private static final EventLog NO_EVENTS = new EventLog(0, "events-0");

	private final FrameBroadcaster broadcaster = new FrameBroadcaster();

	FrameBroadcasterTest() {
		broadcaster.setPresentation("match");
	}

	@AfterEach
	void disconnectEveryone() {
		broadcaster.disconnect("fast");
		broadcaster.disconnect("slow");
		broadcaster.disconnect("late");
		broadcaster.disconnect("binary");
	}

	@Test
	void aSlowClientDoesNotHoldBackTheOthers() throws InterruptedException {
		FakeClient fast = new FakeClient(Duration.ZERO);
		FakeClient slow = new FakeClient(Duration.ofMillis(300));
		broadcaster.connect("fast", fast, FrameFormat.JSON);
		broadcaster.connect("slow", slow, FrameFormat.JSON);

		// 20 frames, one every 10 ms, like a sped-up clock
		long publishingNanos = 0;
		for (int i = 1; i <= 20; i++) {
			long start = System.nanoTime();
			broadcaster.publish(frame(i));
			publishingNanos += System.nanoTime() - start;
			Thread.sleep(10);
		}

		// Publishing never waited for the slow client: 20 drops in well under 300 ms
		assertTrue(Duration.ofNanos(publishingNanos).toMillis() < 50,
				"publish blocked for " + Duration.ofNanos(publishingNanos).toMillis() + " ms");

		// The fast client got almost everything, and the slow client ends on the latest frame
		waitUntil(() -> fast.lastReceived().equals("frame-20") && slow.lastReceived().equals("frame-20"));
		assertTrue(fast.frameCount() >= 15, "the fast client only got " + fast.frameCount() + " frames");

		// … skipping almost all the others instead of piling them up
		assertTrue(slow.frameCount() <= 3, "the slow client got " + slow.frameCount() + " frames");
	}

	@Test
	void aLateClientGetsThePresentationThenTheCurrentFrameAtOnce() {
		broadcaster.publish(frame(1));
		broadcaster.publish(frame(2));

		FakeClient late = new FakeClient(Duration.ZERO);
		broadcaster.connect("late", late, FrameFormat.JSON);

		waitUntil(() -> late.received.size() == 3);
		assertEquals(List.of("match", "events-0", "frame-2"), late.received);
	}

	@Test
	void aClientCanAskForFewerFramesAndStillGetsTheLatest() throws InterruptedException {
		FakeClient fast = new FakeClient(Duration.ZERO);
		broadcaster.connect("fast", fast, FrameFormat.JSON);
		broadcaster.setRate("fast", 5);

		// 50 frames in 500 ms; at 5 frames per second, one every 200 ms
		for (int i = 1; i <= 50; i++) {
			broadcaster.publish(frame(i));
			Thread.sleep(10);
		}

		waitUntil(() -> fast.lastReceived().equals("frame-50"));
		assertTrue(fast.frameCount() >= 3 && fast.frameCount() <= 5,
				"expected 3 to 5 frames, got " + fast.frameCount());
	}

	@Test
	void aBinaryClientGetsThePresentationAsTextThenBinaryFrames() {
		FakeClient binary = new FakeClient(Duration.ZERO);
		broadcaster.connect("binary", binary, FrameFormat.BINARY);
		broadcaster.publish(frame(7));

		waitUntil(() -> binary.received.size() == 3);
		assertEquals(List.of("match", "events-0", "binary-7"), binary.received);
	}

	@Test
	void aSlowClientSkipsFramesButNeverMissesTheLatestEventLog() {
		FakeClient slow = new FakeClient(Duration.ofMillis(300));
		broadcaster.connect("slow", slow, FrameFormat.JSON);

		// The log changes twice while the slow client is still busy with the presentation
		EventLog firstGoal = new EventLog(1, "events-1");
		EventLog secondGoal = new EventLog(2, "events-2");
		for (int i = 1; i <= 20; i++) {
			broadcaster.publish(frame(i, i < 5 ? NO_EVENTS : i < 15 ? firstGoal : secondGoal));
		}

		waitUntil(() -> slow.lastReceived().equals("frame-20"));
		int log = slow.received.indexOf("events-2");
		assertTrue(log >= 0 && log < slow.received.indexOf("frame-20"),
				"the latest log must arrive, before its frame: " + slow.received);
	}

	/** An encoded frame: "frame-i" in JSON, a single byte i in binary. */
	private static EncodedFrame frame(int i) {
		return frame(i, NO_EVENTS);
	}

	private static EncodedFrame frame(int i, EventLog events) {
		return new EncodedFrame("frame-" + i, new byte[] { (byte) i }, events);
	}

	/** A fake browser: it records what it receives, and takes readTime to read each message. */
	private static final class FakeClient implements ClientChannel {

		final List<String> received = new CopyOnWriteArrayList<>();
		private final Duration readTime;

		FakeClient(Duration readTime) {
			this.readTime = readTime;
		}

		@Override
		public void sendText(String message) throws IOException {
			receive(message);
		}

		@Override
		public void sendBinary(byte[] message) throws IOException {
			receive("binary-" + message[0]);
		}

		private void receive(String message) throws IOException {
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
				throw new AssertionError("condition not met within 2 s");
			}
			Thread.onSpinWait();
		}
	}
}
