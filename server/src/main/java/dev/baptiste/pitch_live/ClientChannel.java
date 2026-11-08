package dev.baptiste.pitch_live;

import java.io.IOException;

/**
 * What the broadcast needs from a client: send it a message, and hang up.
 *
 * In production, this is a WebSocket session (see MatchWebSocketHandler). In tests,
 * it is a fake client that can be made as slow as we like, without a browser.
 * Sends may block for as long as the client is not reading.
 */
public interface ClientChannel {

	void sendText(String message) throws IOException;

	void sendBinary(byte[] message) throws IOException;

	void close();
}
