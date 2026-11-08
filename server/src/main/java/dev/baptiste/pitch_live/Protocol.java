package dev.baptiste.pitch_live;

import java.util.List;

/**
 * The contract between the server and the browsers, in JSON.
 *
 * It is mirrored by hand in client/src/protocol.ts: any change here must be carried
 * over there. Every message carries its version (v); a breaking change will create
 * version 2, and the server will accept both during the transition.
 */
public final class Protocol {

	public static final int VERSION = 1;

	private Protocol() {
	}

	/** Pitch dimensions, in metres. */
	public record Pitch(double length, double width) {
	}

	/**
	 * Sent once, on connection: the pitch and the full roster, substitutes included.
	 * An analyst who joins mid-match gets everything needed to read the frames that follow.
	 */
	public record MatchMessage(int v, String type, Pitch pitch, List<PlayerProfile> players) {
		public MatchMessage(Pitch pitch, List<PlayerProfile> players) {
			this(VERSION, "match", pitch, List.copyOf(players));
		}
	}

	/**
	 * Sent with every frame. sentAt is the time the server published it, in milliseconds
	 * since 1970, so that the browser can measure latency.
	 */
	public record FrameMessage(int v, String type, double sentAt, Frame frame) {
		public FrameMessage(double sentAt, Frame frame) {
			this(VERSION, "frame", sentAt, frame);
		}
	}

	/** Received from the browser: it only wants fps frames per second (1 to 25). */
	public record RateRequest(int v, String type, int fps) {
	}
}
