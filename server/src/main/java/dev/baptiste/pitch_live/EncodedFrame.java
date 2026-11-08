package dev.baptiste.pitch_live;

/**
 * A frame already encoded in both formats: once per frame, whatever the number of clients.
 *
 * Beware: an array inside a record stays mutable, the record only protects the reference.
 * This one is shared by the sender threads of every client; it is never modified after
 * encoding, and that is what makes sharing it safe.
 */
public record EncodedFrame(String json, byte[] binary) {
}
