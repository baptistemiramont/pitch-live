package dev.baptiste.pitch_live;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Exposes the broadcast on ws://localhost:8080/match. */
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
		// The browser sends the origin of the page (the Vite client, on another port).
		// Spring rejects foreign origins by default, to prevent cross-site WebSocket
		// hijacking: the client's origins are allowed explicitly.
		registry.addHandler(handler, "/match")
				.setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
	}
}
