package dev.baptiste.pitch_live;

import java.io.IOException;

/**
 * Ce dont la diffusion a besoin d'un client : lui envoyer un message, et couper.
 *
 * En production, c'est une session WebSocket (voir MatchWebSocketHandler). Dans les
 * tests, c'est un faux client qu'on peut rendre lent à volonté, sans navigateur.
 */
public interface ClientChannel {

	/** Envoie un message ; peut bloquer tant que le client ne lit pas. */
	void send(String message) throws IOException;

	void close();
}
