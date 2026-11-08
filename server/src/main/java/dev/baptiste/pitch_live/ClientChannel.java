package dev.baptiste.pitch_live;

import java.io.IOException;

/**
 * Ce dont la diffusion a besoin d'un client : lui envoyer un message, et couper.
 *
 * En production, c'est une session WebSocket (voir MatchWebSocketHandler). Dans les
 * tests, c'est un faux client qu'on peut rendre lent à volonté, sans navigateur.
 * Les envois peuvent bloquer tant que le client ne lit pas.
 */
public interface ClientChannel {

	void sendText(String message) throws IOException;

	void sendBinary(byte[] message) throws IOException;

	void close();
}
