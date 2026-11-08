package dev.baptiste.pitch_live;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fans every frame out to all connected clients.
 *
 * Three kinds of threads meet here: the clock thread publishes, Tomcat threads connect
 * and disconnect clients, and every client has its own sender thread. All the shared
 * state therefore lives in structures designed for it.
 */
@Component
public class FrameBroadcaster {

	private static final Logger log = LoggerFactory.getLogger(FrameBroadcaster.class);

	/**
	 * ConcurrentHashMap, not HashMap: the clock iterates over the map while Tomcat adds
	 * or removes clients. A HashMap could throw a ConcurrentModificationException, or
	 * worse, corrupt itself silently.
	 */
	private final Map<String, ClientConnection> clients = new ConcurrentHashMap<>();

	/** The latest published frame, so that a new client sees the pitch right away. */
	private final AtomicReference<EncodedFrame> lastFrame = new AtomicReference<>();

	/**
	 * Written once at startup, then read by Tomcat threads. volatile guarantees that
	 * those other threads see the written value, and not a stale copy left in the cache
	 * of a CPU core.
	 */
	private volatile String presentation;

	void setPresentation(String presentation) {
		this.presentation = presentation;
	}

	/** Called by the clock thread, 25 times per second: never blocks. */
	void publish(EncodedFrame frame) {
		lastFrame.set(frame);
		for (ClientConnection client : clients.values()) {
			client.offer(frame);
		}
	}

	public void connect(String id, ClientChannel channel, FrameFormat format) {
		ClientConnection client = new ClientConnection(id, channel, presentation, format);
		// The latest frame is dropped in before registration: the clock cannot write to
		// this mailbox yet, so there is no risk of overwriting a more recent frame.
		EncodedFrame last = lastFrame.get();
		if (last != null) {
			client.offer(last);
		}
		clients.put(id, client);
		client.start();
		log.info("Client {} connected in {} ({} in total)", id, format, clients.size());
	}

	public void setRate(String id, int framesPerSecond) {
		ClientConnection client = clients.get(id);
		if (client != null) {
			client.setRate(framesPerSecond);
			log.info("Client {}: {} frames per second", id, Math.min(framesPerSecond, 25));
		}
	}

	public void disconnect(String id) {
		ClientConnection client = clients.remove(id);
		if (client != null) {
			client.stop();
			log.info("Client {} disconnected ({} in total)", id, clients.size());
		}
	}
}
