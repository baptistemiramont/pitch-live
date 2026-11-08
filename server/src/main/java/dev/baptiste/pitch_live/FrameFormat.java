package dev.baptiste.pitch_live;

/**
 * The frame format of a client, chosen when it connects: ws://…/match for JSON,
 * ws://…/match?format=binary for binary. The presentation stays in JSON either way:
 * it is sent only once, so it might as well remain readable.
 */
public enum FrameFormat {
	JSON,
	BINARY;

	static FrameFormat fromQuery(String query) {
		return query != null && query.contains("format=binary") ? BINARY : JSON;
	}
}
