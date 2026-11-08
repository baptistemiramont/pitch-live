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
 * The replay clock: one frame every 40 ms, that is 25 per second, as the match was
 * recorded. When the match ends, it starts again from kick-off.
 */
@Component
public class MatchReplay {

	private static final Logger log = LoggerFactory.getLogger(MatchReplay.class);

	static final long FRAME_INTERVAL_MS = 40;

	private final PitchLiveProperties properties;
	private final FrameBroadcaster broadcaster;
	private final JsonMapper json;

	/**
	 * A single, dedicated and named thread (it shows up in jcmd Thread.print).
	 * It is a platform thread, not a virtual one: it runs all the time and its
	 * regularity matters, whereas virtual threads are for waiting on many things
	 * at once, cheaply.
	 */
	private final ScheduledExecutorService clock =
			Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("replay-clock").factory());

	/**
	 * Confined to the clock thread: once the replay has started, only that thread reads
	 * or replaces it. No lock is needed for state that a single thread touches.
	 */
	private TrackingReader reader;

	/** Ready before the clock starts, never modified afterwards. */
	private BinaryFrameEncoder binaryEncoder;

	/** Spring provides the JsonMapper (Jackson 3) it configures itself. */
	public MatchReplay(PitchLiveProperties properties, FrameBroadcaster broadcaster, JsonMapper json) {
		this.properties = properties;
		this.broadcaster = broadcaster;
		this.json = json;
	}

	/**
	 * Opens the match and prepares the presentation while the beans are being created,
	 * before Tomcat accepts any connection: no client can arrive before the presentation
	 * exists.
	 */
	@PostConstruct
	public void open() throws IOException {
		reader = openReader();
		Pitch pitch = new Pitch(TrackingReader.PITCH_LENGTH, TrackingReader.PITCH_WIDTH);
		broadcaster.setPresentation(json.writeValueAsString(new MatchMessage(pitch, reader.profiles())));
		binaryEncoder = new BinaryFrameEncoder(reader.profiles());
	}

	/** Starts once the application is fully ready, Tomcat included. */
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		// scheduleAtFixedRate aims at 0, 40, 80, 120 ms… from the start: an occasional
		// delay is caught up and the rate does not drift. scheduleWithFixedDelay would
		// wait 40 ms after the end of each frame, and the processing time would add up
		// on every tick: the match would fall behind the clock.
		clock.scheduleAtFixedRate(this::tick, 0, FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS);
		log.info("Replay started, one frame every {} ms", FRAME_INTERVAL_MS);
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
		// Classic pitfall: an exception escaping a scheduled task silently cancels every
		// following run. The match would freeze without a word in the logs, hence this
		// catch-all try.
		try {
			Optional<Frame> frame = reader.next();
			if (frame.isEmpty()) {
				log.info("End of the match, restarting from kick-off");
				reader.close();
				reader = openReader();
				frame = reader.next();
			}
			frame.ifPresent(this::publish);
		} catch (Exception e) {
			log.error("Frame lost", e);
		}
	}

	private void publish(Frame frame) {
		// Each format is produced once per frame, here, and not once per client: with a
		// hundred clients, we still encode 25 times per second, not 2,500. The binary
		// format is encoded even if no client asks for it: 135 bytes is simpler than
		// keeping track of the formats in use.
		double sentAt = epochMillis(Instant.now());
		broadcaster.publish(new EncodedFrame(
				json.writeValueAsString(new FrameMessage(sentAt, frame)),
				binaryEncoder.encode(sentAt, frame)));

		if (frame.id() % (25 * 60) == 0) {
			log.info("Minute {} of the match", frame.id() / (25 * 60));
		}
	}

	/** Milliseconds since 1970, with decimals: the scale of Date.now() in the browser. */
	private static double epochMillis(Instant instant) {
		return instant.getEpochSecond() * 1000.0 + instant.getNano() / 1_000_000.0;
	}

	private TrackingReader openReader() throws IOException {
		return new TrackingReader(Path.of(properties.homeFile()), Path.of(properties.awayFile()));
	}
}
