package dev.baptiste.pitch_live;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Distribue chaque image à tous les clients connectés.
 *
 * Trois sortes de threads s'y croisent : le thread d'horloge publie, les threads
 * de Tomcat connectent et déconnectent les clients, et chaque client a son thread
 * d'envoi. Tout l'état partagé est donc dans des structures prévues pour ça.
 */
@Component
public class FrameBroadcaster {

	private static final Logger log = LoggerFactory.getLogger(FrameBroadcaster.class);

	/**
	 * ConcurrentHashMap et non HashMap : l'horloge parcourt la map pendant que Tomcat
	 * y ajoute ou en retire des clients. Une HashMap pourrait lever une
	 * ConcurrentModificationException, ou pire, se corrompre sans rien dire.
	 */
	private final Map<String, ClientConnection> clients = new ConcurrentHashMap<>();

	/** La dernière image publiée, pour qu'un nouveau client voie le terrain tout de suite. */
	private final AtomicReference<String> lastFrame = new AtomicReference<>();

	/**
	 * Écrit une fois au démarrage, lu ensuite par les threads de Tomcat. volatile
	 * garantit que ces autres threads voient bien la valeur écrite, et pas une
	 * ancienne copie restée dans le cache d'un cœur du processeur.
	 */
	private volatile String presentation;

	void setPresentation(String presentation) {
		this.presentation = presentation;
	}

	/** Appelé par le thread d'horloge, 25 fois par seconde : ne bloque jamais. */
	void publish(String frame) {
		lastFrame.set(frame);
		for (ClientConnection client : clients.values()) {
			client.offer(frame);
		}
	}

	public void connect(String id, ClientChannel channel) {
		ClientConnection client = new ClientConnection(id, channel, presentation);
		// La dernière image est déposée avant l'inscription : l'horloge ne peut pas
		// encore écrire dans cette boîte, aucun risque d'écraser une image plus récente.
		String last = lastFrame.get();
		if (last != null) {
			client.offer(last);
		}
		clients.put(id, client);
		client.start();
		log.info("Client {} connecté ({} en tout)", id, clients.size());
	}

	public void setRate(String id, int framesPerSecond) {
		ClientConnection client = clients.get(id);
		if (client != null) {
			client.setRate(framesPerSecond);
			log.info("Client {} : {} images par seconde", id, Math.min(framesPerSecond, 25));
		}
	}

	public void disconnect(String id) {
		ClientConnection client = clients.remove(id);
		if (client != null) {
			client.stop();
			log.info("Client {} déconnecté ({} en tout)", id, clients.size());
		}
	}
}
