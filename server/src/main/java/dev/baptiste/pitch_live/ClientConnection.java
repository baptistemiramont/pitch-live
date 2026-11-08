package dev.baptiste.pitch_live;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A connected browser: a one-slot mailbox, and a virtual thread that drains it
 * towards the browser.
 *
 * This is where the slow client problem is solved. The clock drops each frame into
 * the mailbox without ever waiting; if the previous frame has not left yet, it is
 * replaced. A slow client therefore skips frames but stays live, and since every
 * client has its own sender thread, its slowness only blocks itself.
 */
final class ClientConnection {

	private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

	private final String id;
	private final ClientChannel channel;
	private final String presentation;
	private final FrameFormat format;

	/**
	 * A single slot: not a queue but "the latest frame". An unbounded queue would make
	 * a slow client fall further and further behind, and would end up filling the
	 * server's memory.
	 */
	private final BlockingQueue<EncodedFrame> mailbox = new ArrayBlockingQueue<>(1);

	/**
	 * The minimum interval between two sends; 0 to send every frame. Written by a
	 * Tomcat thread when the browser asks for another rate, read by the sender thread:
	 * volatile, so that the latter sees what the former writes.
	 */
	private volatile long intervalNanos = 0;

	private Thread sender;

	ClientConnection(String id, ClientChannel channel, String presentation, FrameFormat format) {
		this.id = id;
		this.channel = channel;
		this.presentation = presentation;
		this.format = format;
	}

	String id() {
		return id;
	}

	/**
	 * Drops a frame, replacing the one still waiting. Called by the clock thread:
	 * never blocks.
	 */
	void offer(EncodedFrame frame) {
		while (!mailbox.offer(frame)) {
			mailbox.poll();
		}
	}

	/** Limits the client to framesPerSecond frames per second; 25 or more means every frame. */
	void setRate(int framesPerSecond) {
		intervalNanos = framesPerSecond >= 25 ? 0 : Duration.ofSeconds(1).toNanos() / framesPerSecond;
	}

	/**
	 * One virtual thread per client: it spends most of its time blocked, waiting for a
	 * frame or for the network to accept a send. A blocked virtual thread costs almost
	 * nothing; a thousand clients would mean a thousand virtual threads, carried by a
	 * handful of OS threads.
	 */
	void start() {
		sender = Thread.ofVirtual().name("client-" + id).start(this::run);
	}

	void stop() {
		if (sender != null) {
			sender.interrupt();
		}
	}

	private void run() {
		try {
			// The presentation goes first, from this thread: a single thread writes to
			// the session, so messages cannot interleave.
			channel.sendText(presentation);
			while (true) {
				EncodedFrame frame = mailbox.take();
				long sentAt = System.nanoTime();
				send(frame);

				// Reduced rate: the thread sleeps until the next allowed send. Meanwhile,
				// the clock keeps replacing the waiting frame; on wake-up, the latest one
				// goes out, never a stale one.
				long remaining = intervalNanos - (System.nanoTime() - sentAt);
				if (remaining > 0) {
					Thread.sleep(Duration.ofNanos(remaining));
				}
			}
		} catch (InterruptedException e) {
			// stop() was called: the client disconnected, the thread ends.
		} catch (IOException e) {
			// The browser no longer reads, or the connection is broken. A client that
			// stops reading altogether blocks its send until Tomcat's timeout (20 s by
			// default) makes it fail: we hang up.
			log.info("Cannot send to client {}, disconnecting: {}", id, e.getMessage());
			channel.close();
		}
	}

	private void send(EncodedFrame frame) throws IOException {
		if (format == FrameFormat.BINARY) {
			channel.sendBinary(frame.binary());
		} else {
			channel.sendText(frame.json());
		}
	}
}
