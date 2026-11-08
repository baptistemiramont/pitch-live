package dev.baptiste.pitch_live;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.baptiste.pitch_live.Protocol.FrameMessage;
import dev.baptiste.pitch_live.Protocol.MatchMessage;
import dev.baptiste.pitch_live.Protocol.Pitch;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * L'horloge du rejeu : une image toutes les 40 ms, soit 25 par seconde, comme le
 * match a été filmé. Quand le match se termine, il reprend au coup d'envoi.
 */
@Component
public class MatchReplay {

	private static final Logger log = LoggerFactory.getLogger(MatchReplay.class);

	static final long FRAME_INTERVAL_MS = 40;

	private final PitchLiveProperties properties;
	private final FrameBroadcaster broadcaster;
	private final JsonMapper json;

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

	/** Prêt avant le démarrage de l'horloge, jamais modifié ensuite. */
	private BinaryFrameEncoder binaryEncoder;

	/** Spring fournit le JsonMapper (Jackson 3) qu'il configure lui-même. */
	public MatchReplay(PitchLiveProperties properties, FrameBroadcaster broadcaster, JsonMapper json) {
		this.properties = properties;
		this.broadcaster = broadcaster;
		this.json = json;
	}

	/**
	 * Ouvre le match et prépare la présentation pendant la création des beans,
	 * avant que Tomcat n'accepte la moindre connexion : un client ne peut pas
	 * arriver avant que la présentation existe.
	 */
	@PostConstruct
	public void open() throws IOException {
		reader = openReader();
		Pitch pitch = new Pitch(TrackingReader.PITCH_LENGTH, TrackingReader.PITCH_WIDTH);
		broadcaster.setPresentation(json.writeValueAsString(new MatchMessage(pitch, reader.profiles())));
		binaryEncoder = new BinaryFrameEncoder(reader.profiles());
	}

	/** Démarre quand l'application est entièrement prête, Tomcat compris. */
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
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
		// Chaque format est produit une fois par image, ici, et non une fois par client :
		// avec cent clients, on encode toujours 25 fois par seconde, pas 2 500. Le binaire
		// est encodé même si aucun client ne le demande : 135 octets, c'est plus simple
		// que de tenir le compte des formats demandés.
		double sentAt = epochMillis(Instant.now());
		broadcaster.publish(new EncodedFrame(
				json.writeValueAsString(new FrameMessage(sentAt, frame)),
				binaryEncoder.encode(sentAt, frame)));

		if (frame.id() % (25 * 60) == 0) {
			log.info("Minute {} du match", frame.id() / (25 * 60));
		}
	}

	/** Millisecondes depuis 1970, avec les décimales : le format de Date.now() côté navigateur. */
	private static double epochMillis(Instant instant) {
		return instant.getEpochSecond() * 1000.0 + instant.getNano() / 1_000_000.0;
	}

	private TrackingReader openReader() throws IOException {
		return new TrackingReader(Path.of(properties.homeFile()), Path.of(properties.awayFile()));
	}
}
