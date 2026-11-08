package dev.baptiste.pitch_live;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Expose la diffusion sur ws://localhost:8080/match. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final MatchWebSocketHandler handler;
	private final PitchLiveProperties properties;

	public WebSocketConfig(MatchWebSocketHandler handler, PitchLiveProperties properties) {
		this.handler = handler;
		this.properties = properties;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		// Le navigateur envoie l'origine de la page (le client Vite, sur un autre port).
		// Spring refuse par défaut les origines étrangères, contre le détournement de
		// WebSocket depuis un autre site : on autorise explicitement celles du client.
		registry.addHandler(handler, "/match")
				.setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
	}
}
