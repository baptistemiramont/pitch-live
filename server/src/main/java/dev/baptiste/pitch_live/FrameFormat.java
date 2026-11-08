package dev.baptiste.pitch_live;

/**
 * Le format des images pour un client, choisi à la connexion : ws://…/match pour du
 * JSON, ws://…/match?format=binary pour le binaire. Le message de présentation reste
 * en JSON dans les deux cas : il ne part qu'une fois, autant qu'il reste lisible.
 */
public enum FrameFormat {
	JSON,
	BINARY;

	static FrameFormat fromQuery(String query) {
		return query != null && query.contains("format=binary") ? BINARY : JSON;
	}
}
