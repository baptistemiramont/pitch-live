package dev.baptiste.pitch_live;

/**
 * Une image déjà encodée dans les deux formats, une seule fois par image, quel que
 * soit le nombre de clients.
 *
 * Attention : un tableau dans un record reste modifiable, le record ne protège que la
 * référence. Celui-ci est partagé entre les threads d'envoi de tous les clients ; il
 * n'est jamais modifié après l'encodage, et c'est ce qui le rend sûr.
 */
public record EncodedFrame(String json, byte[] binary) {
}
