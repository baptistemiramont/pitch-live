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
 * Le point d'entrée WebSocket brut, sans STOMP : on garde la main sur ce qui part
 * vers chaque client, et à quel rythme.
 *
 * Ces méthodes sont appelées par les threads de Tomcat. Elles ne font que brancher
 * et débrancher le client ; les envois se font dans le thread propre à chaque client.
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
	 * Un message du navigateur. Il vient de l'extérieur : on le valide, et un message
	 * incompréhensible est ignoré plutôt que de couper la connexion.
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
			// JSON invalide : traité comme un message inconnu, juste en dessous
		}
		log.warn("Message ignoré du client {} : {}", session.getId(), message.getPayload());
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		broadcaster.disconnect(session.getId());
	}

	/** Adapte une session WebSocket de Spring à ce qu'attend la diffusion. */
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
				// La connexion est déjà rompue : rien de plus à faire.
			}
		}
	}
}
