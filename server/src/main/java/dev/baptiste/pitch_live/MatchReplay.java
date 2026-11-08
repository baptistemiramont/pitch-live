package dev.baptiste.pitch_live;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * L'horloge du rejeu : une image toutes les 40 ms, soit 25 par seconde, comme le
 * match a été filmé. Quand le match se termine, il reprend au coup d'envoi.
 */
@Component
public class MatchReplay {

	private static final Logger log = LoggerFactory.getLogger(MatchReplay.class);

	static final long FRAME_INTERVAL_MS = 40;

	private final PitchLiveProperties properties;

	/**
	 * Un seul thread, dédié et nommé (on le retrouve dans jcmd Thread.print).
	 * C'est un thread classique et non virtuel : il tourne en continu et sa
	 * régularité compte, alors que les threads virtuels servent à attendre
	 * beaucoup de choses à la fois à moindre coût.
	 */
	private final ScheduledExecutorService clock =
			Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("replay-clock").factory());

	/**
	 * Confiné au thread d'horloge : seul lui le lit ou le remplace une fois le rejeu
	 * lancé. Pas besoin de verrou pour un état qu'un seul thread touche.
	 */
	private TrackingReader reader;

	public MatchReplay(PitchLiveProperties properties) {
		this.properties = properties;
	}

	/** Démarre quand l'application est entièrement prête, Tomcat compris. */
	@EventListener(ApplicationReadyEvent.class)
	public void start() throws IOException {
		reader = openReader();
		// scheduleAtFixedRate vise 0, 40, 80, 120 ms… depuis le départ : un retard
		// ponctuel est rattrapé et le rythme ne dérive pas. scheduleWithFixedDelay
		// attendrait 40 ms après la fin de chaque image, et le temps de traitement
		// s'ajouterait à chaque tour : le match prendrait du retard sur l'horloge.
		clock.scheduleAtFixedRate(this::tick, 0, FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS);
		log.info("Rejeu démarré, une image toutes les {} ms", FRAME_INTERVAL_MS);
	}

	@PreDestroy
	public void stop() throws IOException, InterruptedException {
		clock.shutdownNow();
		clock.awaitTermination(1, TimeUnit.SECONDS);
		if (reader != null) {
			reader.close();
		}
	}

	private void tick() {
		// Piège classique : une exception qui s'échappe d'une tâche planifiée annule
		// en silence toutes les exécutions suivantes. Le match se figerait sans un
		// mot dans les logs, d'où ce try qui attrape tout.
		try {
			Optional<Frame> frame = reader.next();
			if (frame.isEmpty()) {
				log.info("Fin du match, reprise au coup d'envoi");
				reader.close();
				reader = openReader();
				frame = reader.next();
			}
			frame.ifPresent(this::publish);
		} catch (Exception e) {
			log.error("Image perdue", e);
		}
	}

	private void publish(Frame frame) {
		// Une ligne par seconde de match : l'heure des logs doit avancer d'une seconde pile.
		if (frame.id() % 25 == 0) {
			log.info("Image {} (t = {} s, {} joueurs, ballon {})",
					frame.id(), frame.time(), frame.players().size(), frame.ball() == null ? "absent" : "présent");
		}
	}

	private TrackingReader openReader() throws IOException {
		return new TrackingReader(Path.of(properties.homeFile()), Path.of(properties.awayFile()));
	}
}
