package dev.baptiste.pitch_live;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.baptiste.pitch_live.MatchEvent.Kind;
import dev.baptiste.pitch_live.Protocol.EventsMessage;
import dev.baptiste.pitch_live.Protocol.FrameMessage;
import dev.baptiste.pitch_live.Protocol.MatchMessage;
import dev.baptiste.pitch_live.Protocol.Pitch;
import dev.baptiste.pitch_live.Protocol.Score;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * The replay clock: one frame every 40 ms, that is 25 per second, as the match was
 * recorded. When the match ends, it starts again from kick-off.
 *
 * The clock also releases the match events (goals, cards) at the frame where they
 * happen: Metrica aligned its event data on the tracking frames, so the two streams
 * stay in sync without any extra clock.
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

	// Confined to the clock thread: once the replay has started, only that thread reads
	// or changes these fields. No lock is needed for state that a single thread touches.
	private TrackingReader reader;
	private int nextEvent;
	private final List<MatchEvent> eventsSoFar = new ArrayList<>();
	private EventLog eventLog;
	private long eventLogVersion;

	// Ready before the clock starts, never modified afterwards.
	private BinaryFrameEncoder binaryEncoder;
	private List<MatchEvent> events;

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
		events = loadEvents();
		eventLog = buildEventLog();
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
				rewind();
				frame = reader.next();
			}
			frame.ifPresent(this::publish);
		} catch (Exception e) {
			log.error("Frame lost", e);
		}
	}

	/** Back to kick-off: a fresh reader, and an empty event log. Runs on the clock thread. */
	private void rewind() throws IOException {
		reader.close();
		reader = openReader();
		nextEvent = 0;
		eventsSoFar.clear();
		eventLog = buildEventLog();
	}

	private void publish(Frame frame) {
		releaseEventsUpTo(frame.id());

		// Each format is produced once per frame, here, and not once per client: with a
		// hundred clients, we still encode 25 times per second, not 2,500. The binary
		// format is encoded even if no client asks for it: 135 bytes is simpler than
		// keeping track of the formats in use.
		double sentAt = epochMillis(Instant.now());
		broadcaster.publish(new EncodedFrame(
				json.writeValueAsString(new FrameMessage(sentAt, frame)),
				binaryEncoder.encode(sentAt, frame),
				eventLog));

		if (frame.id() % (25 * 60) == 0) {
			log.info("Minute {} of the match", frame.id() / (25 * 60));
		}
	}

	/** Moves the events that happened up to this frame into the log, and re-encodes it if it changed. */
	private void releaseEventsUpTo(int frameId) {
		int before = eventsSoFar.size();
		while (nextEvent < events.size() && events.get(nextEvent).frame() <= frameId) {
			MatchEvent event = events.get(nextEvent++);
			eventsSoFar.add(event);
			log.info("{} for {} ({}) at frame {}", event.kind(), event.player(), event.team(), event.frame());
		}
		if (eventsSoFar.size() != before) {
			eventLog = buildEventLog();
		}
	}

	/** Encodes the log once, for every client, with a new version number. */
	private EventLog buildEventLog() {
		Score score = new Score(goals(Team.HOME), goals(Team.AWAY));
		return new EventLog(++eventLogVersion, json.writeValueAsString(new EventsMessage(score, eventsSoFar)));
	}

	private int goals(Team team) {
		return (int) eventsSoFar.stream().filter(event -> event.kind() == Kind.GOAL && event.team() == team).count();
	}

	/** The events are optional: without the file, the match plays without score or events. */
	private List<MatchEvent> loadEvents() throws IOException {
		Path file = Path.of(properties.eventsFile());
		if (!Files.exists(file)) {
			log.warn("No event file at {}: no score or events (run make data)", file);
			return List.of();
		}
		List<MatchEvent> loaded = EventReader.read(file);
		log.info("{} goals and cards loaded", loaded.size());
		return loaded;
	}

	/** Milliseconds since 1970, with decimals: the scale of Date.now() in the browser. */
	private static double epochMillis(Instant instant) {
		return instant.getEpochSecond() * 1000.0 + instant.getNano() / 1_000_000.0;
	}

	private TrackingReader openReader() throws IOException {
		return new TrackingReader(Path.of(properties.homeFile()), Path.of(properties.awayFile()));
	}
}
