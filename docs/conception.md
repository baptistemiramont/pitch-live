# pitch-live : conception

Rejouer un vrai match de football à 25 images par seconde, le diffuser en direct depuis un serveur Java, et l'afficher en 2D dans un navigateur.

## Le contexte

**Le problème.** Afficher en direct, pour des analystes, les positions des joueurs produites par un système de suivi : un flux à 25 images par seconde, affiché en 2D, avec des clients plus ou moins rapides.

**Ce qui compte.** Côté serveur, la concurrence : un service qui tourne en continu et gère des connexions en parallèle. Côté navigateur, un rendu fluide d'un flux à haute fréquence.

## Les objectifs et les limites

- **Objectifs** : un serveur Java qui rejoue un match et le diffuse à plusieurs navigateurs ; un client qui l'affiche de façon fluide ; une mesure de la latence de bout en bout.
- **Hors périmètre** : authentification, persistance, déploiement, plusieurs matchs à la fois, mobile, React, PixiJS.

## Les données

Les données d'exemple de Metrica Sports, publiées sur GitHub (`metrica-sports/sample-data`) : des matchs réels et anonymisés, avec la position de chaque joueur et du ballon, 25 fois par seconde. Les matchs 1 et 2 sont en CSV, un fichier par équipe. Les coordonnées ne sont pas en mètres et doivent être converties ; les tutoriels de Laurie Shaw montrent comment. Les données ne sont pas versionnées dans le dépôt : un script les télécharge, et le README cite la source, comme Metrica le demande.

## L'architecture, dans les grandes lignes

```
fichiers CSV ──► serveur Java : rejoue le match, une image toutes les 40 ms
                     │
                     └──► diffusion WebSocket ──► navigateurs : réception, puis rendu 2D
```

Deux principes guident la conception :

1. **Côté serveur, un client lent ne ralentit personne.** Il reçoit la dernière image disponible, pas un retard qui s'accumule.
2. **Côté navigateur, la réception et le dessin sont séparés.** Les messages arrivent quand ils arrivent ; l'écran se redessine à son propre rythme, à partir de la dernière image reçue.

## Le monorepo

| Dossier | Contenu |
|---|---|
| `server/` | Le serveur Spring Boot, en Maven |
| `client/` | Le client Vite et TypeScript, sans framework, dessiné dans un canvas |
| `docs/` | Ce document, et les décisions si elles grossissent |
| `scripts/` | Le téléchargement des données |
| `data/` | Les CSV de Metrica, ignorés par git |

À la racine, un README et un Makefile pour lancer les données, le serveur, le client et les tests. Pas d'outil de monorepo : deux projets indépendants, reliés par un contrat de messages documenté.

## Le contrat entre serveur et client

Trois messages, en JSON pour commencer :

- **à la connexion**, le serveur présente le match : dimensions du terrain, joueurs et équipes ;
- **à chaque image**, il envoie les positions, avec l'heure d'envoi pour mesurer la latence ;
- **dans l'autre sens**, le client peut demander une fréquence plus basse.

Chaque message porte un numéro de version. Un changement incompatible crée une nouvelle version, et le serveur accepte les deux pendant la transition.

## Les questions à résoudre en chemin

- Comment rejouer deux fichiers en parallèle sans charger tout le match en mémoire ?
- Quels threads tournent dans le serveur, et quel état partagent-ils ?
- Comment éviter qu'un client lent retarde les autres ? Pistes : les collections concurrentes et les threads virtuels de Java, le décorateur de session WebSocket de Spring, et ce qu'il ne règle pas.
- Comment un analyste qui arrive en cours de match voit-il tout de suite l'état du terrain ?
- Comment adapter la fréquence à chaque client ?
- Comment mesurer la latence, et quelle est la limite de cette mesure ?
- Comment dessiner 25 fois par seconde sans passer par l'état d'un framework ?

## Les décisions

| Décision | Pourquoi | Ce que ça coûte |
|---|---|---|
| Rejouer un CSV plutôt qu'utiliser une API publique | Maîtriser la fréquence et travailler le côté serveur | Ce n'est pas du vrai direct |
| WebSocket plutôt que SSE | Le client peut demander une autre fréquence, et le binaire reste possible | La reconnexion est à gérer soi-même |
| JSON d'abord | Lisible dans les outils du navigateur | Plus lourd ; le binaire attendra d'avoir mesuré la taille |
| Ne garder que la dernière image pour chaque client | Un client lent reste en direct au lieu de prendre du retard | Il saute des images |
| Canvas sans moteur de rendu ni React | 23 points se dessinent sans outil lourd | À revoir avec des trajectoires, des zones ou des cartes de chaleur |
| Pas d'outil de monorepo | Deux projets simples | Le contrat se tient à la main entre Java et TypeScript |

## Les étapes

| Étape | Résultat attendu |
|---|---|
| 0. Mise en place | Les deux projets démarrent, les données sont là |
| 1. Rejouer et diffuser | Le navigateur reçoit les images à 25 par seconde |
| 2. Le terrain | Les joueurs et le ballon bougent à l'écran |
| 3. Les clients lents | Une fréquence par client ; deux onglets à des rythmes différents |
| 4. La latence | La latence de bout en bout s'affiche |
| 5. Pour aller plus loin | Simuler un vrai client lent, lisser le mouvement, passer en binaire |

## Sources

- [Metrica Sports : sample data](https://github.com/metrica-sports/sample-data)
- [LaurieOnTracking (Laurie Shaw)](https://www.github.com/Friends-of-Tracking-Data-FoTD/LaurieOnTracking), pour la lecture des données et la conversion en mètres
