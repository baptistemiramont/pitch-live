package dev.baptiste.pitch_live;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import dev.baptiste.pitch_live.Protocol.RateRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The raw WebSocket endpoint, without STOMP: we keep control over what goes to each
 * client, and at what rate.
 *
 * These methods are called by Tomcat threads. They only plug and unplug the client;
 * the sends happen in each client's own thread.
 */
@Component
public class MatchWebSocketHandler extends TextWebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(MatchWebSocketHandler.class);

	private final FrameBroadcaster broadcaster;
	private final JsonMapper json;

	public MatchWebSocketHandler(FrameBroadcaster broadcaster, JsonMapper json) {
		this.broadcaster = broadcaster;
		this.json = json;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		String query = session.getUri() == null ? null : session.getUri().getQuery();
		broadcaster.connect(session.getId(), new SessionChannel(session), FrameFormat.fromQuery(query));
	}

	/**
	 * A message from the browser. It comes from outside: we validate it, and a message
	 * we cannot understand is ignored rather than closing the connection.
	 */
	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		try {
			RateRequest request = json.readValue(message.getPayload(), RateRequest.class);
			if (request.v() == Protocol.VERSION && "rate".equals(request.type()) && request.fps() >= 1) {
				broadcaster.setRate(session.getId(), request.fps());
				return;
			}
		} catch (JacksonException e) {
			// Invalid JSON: handled as an unknown message, just below
		}
		log.warn("Ignored message from client {}: {}", session.getId(), message.getPayload());
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		broadcaster.disconnect(session.getId());
	}

	/** Adapts a Spring WebSocket session to what the broadcast expects. */
	private record SessionChannel(WebSocketSession session) implements ClientChannel {

		@Override
		public void sendText(String message) throws IOException {
			session.sendMessage(new TextMessage(message));
		}

		@Override
		public void sendBinary(byte[] message) throws IOException {
			session.sendMessage(new BinaryMessage(message));
		}

		@Override
		public void close() {
			try {
				session.close(CloseStatus.SESSION_NOT_RELIABLE);
			} catch (IOException e) {
				// The connection is already broken: nothing more to do.
			}
		}
	}
}
