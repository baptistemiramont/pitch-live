package dev.baptiste.pitch_live;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Le point d'entrée WebSocket brut, sans STOMP : on garde la main sur ce qui part
 * vers chaque client, et à quel rythme.
 *
 * Ces méthodes sont appelées par les threads de Tomcat. Elles ne font que brancher
 * et débrancher le client ; les envois se font dans le thread propre à chaque client.
 */
@Component
public class MatchWebSocketHandler extends TextWebSocketHandler {

	private final FrameBroadcaster broadcaster;

	public MatchWebSocketHandler(FrameBroadcaster broadcaster) {
		this.broadcaster = broadcaster;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		broadcaster.connect(session.getId(), new SessionChannel(session));
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		broadcaster.disconnect(session.getId());
	}

	/** Adapte une session WebSocket de Spring à ce qu'attend la diffusion. */
	private record SessionChannel(WebSocketSession session) implements ClientChannel {

		@Override
		public void send(String message) throws IOException {
			session.sendMessage(new TextMessage(message));
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
