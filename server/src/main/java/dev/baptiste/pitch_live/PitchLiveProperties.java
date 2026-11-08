package dev.baptiste.pitch_live;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The pitch-live settings, read from application.properties (the keys starting
 * with pitch-live.).
 *
 * A record is enough: Spring passes the values to the constructor, and the settings
 * can no longer change once the application has started.
 *
 * Paths stay strings: to build a Path, Spring goes through its Resource abstraction,
 * which rejects relative paths starting with ../
 */
@ConfigurationProperties("pitch-live")
public record PitchLiveProperties(String homeFile, String awayFile, String eventsFile, List<String> allowedOrigins) {
}
