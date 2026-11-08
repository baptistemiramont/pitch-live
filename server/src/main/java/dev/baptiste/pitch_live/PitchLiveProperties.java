package dev.baptiste.pitch_live;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * La configuration propre à pitch-live, lue dans application.properties
 * (les clés pitch-live.home-file et pitch-live.away-file).
 *
 * Un record suffit : Spring passe les valeurs au constructeur, et la configuration
 * ne peut plus changer une fois l'application démarrée.
 *
 * Les chemins restent des chaînes : pour obtenir un Path, Spring passe par ses
 * Resource, qui refusent les chemins relatifs en ../
 */
@ConfigurationProperties("pitch-live")
public record PitchLiveProperties(String homeFile, String awayFile) {
}
