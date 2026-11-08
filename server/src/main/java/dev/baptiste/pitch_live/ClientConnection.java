package dev.baptiste.pitch_live;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Un navigateur connecté : une boîte aux lettres d'une place, et un thread virtuel
 * qui en vide le contenu vers le navigateur.
 *
 * C'est ici que se règle la question du client lent. L'horloge dépose chaque image
 * dans la boîte sans jamais attendre ; si l'image précédente n'est pas encore partie,
 * elle est remplacée. Un client lent saute donc des images mais reste en direct, et
 * comme chaque client a son propre thread d'envoi, sa lenteur ne bloque que lui.
 */
final class ClientConnection {

	private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

	private final String id;
	private final ClientChannel channel;
	private final String presentation;

	/**
	 * Une seule place : ce n'est pas une file d'attente mais « la dernière image ».
	 * Une file sans limite ferait prendre au client lent un retard croissant, et
	 * finirait par remplir la mémoire du serveur.
	 */
	private final BlockingQueue<String> mailbox = new ArrayBlockingQueue<>(1);

	/**
	 * L'intervalle minimal entre deux envois ; 0 pour envoyer toutes les images.
	 * Écrit par un thread de Tomcat quand le navigateur demande une autre fréquence,
	 * lu par le thread d'envoi : volatile, pour que le second voie ce qu'écrit le premier.
	 */
	private volatile long intervalNanos = 0;

	private Thread sender;

	ClientConnection(String id, ClientChannel channel, String presentation) {
		this.id = id;
		this.channel = channel;
		this.presentation = presentation;
	}

	String id() {
		return id;
	}

	/**
	 * Dépose une image, en remplaçant celle qui attendait encore. Appelé par le
	 * thread d'horloge : ne bloque jamais.
	 */
	void offer(String frame) {
		while (!mailbox.offer(frame)) {
			mailbox.poll();
		}
	}

	/** Limite le client à framesPerSecond images par seconde ; 25 ou plus, c'est tout envoyer. */
	void setRate(int framesPerSecond) {
		intervalNanos = framesPerSecond >= 25 ? 0 : Duration.ofSeconds(1).toNanos() / framesPerSecond;
	}

	/**
	 * Un thread virtuel par client : il passe l'essentiel de son temps bloqué, à
	 * attendre une image ou que le réseau accepte l'envoi. Un thread virtuel bloqué
	 * ne coûte presque rien ; mille clients feraient mille threads virtuels, portés
	 * par une poignée de threads du système.
	 */
	void start() {
		sender = Thread.ofVirtual().name("client-" + id).start(this::run);
	}

	void stop() {
		if (sender != null) {
			sender.interrupt();
		}
	}

	private void run() {
		try {
			// La présentation part en premier, depuis ce thread : un seul thread
			// écrit dans la session, les messages ne peuvent pas se croiser.
			channel.send(presentation);
			while (true) {
				String frame = mailbox.take();
				long sentAt = System.nanoTime();
				channel.send(frame);

				// Fréquence réduite : le thread dort jusqu'au prochain envoi permis. Pendant
				// ce temps, l'horloge continue de remplacer l'image en attente ; au réveil,
				// c'est donc la plus récente qui part, jamais une image périmée.
				long remaining = intervalNanos - (System.nanoTime() - sentAt);
				if (remaining > 0) {
					Thread.sleep(Duration.ofNanos(remaining));
				}
			}
		} catch (InterruptedException e) {
			// stop() a été appelé : le client s'est déconnecté, le thread s'arrête.
		} catch (IOException e) {
			// Le navigateur ne lit plus, ou la connexion est rompue. Un client qui ne
			// lit plus du tout bloque son envoi jusqu'au délai de Tomcat (20 s par
			// défaut), qui le fait échouer : on coupe.
			log.info("Envoi impossible au client {}, déconnexion : {}", id, e.getMessage());
			channel.close();
		}
	}
}
