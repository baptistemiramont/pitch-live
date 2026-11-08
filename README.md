# pitch-live

Rejoue un vrai match de football à 25 images par seconde depuis un serveur Java (Spring Boot), le diffuse en WebSocket et l'affiche en 2D dans le navigateur. La conception est décrite dans [docs/conception.md](docs/conception.md).

## Prérequis

- JDK 25 (`brew install --cask temurin@25`)
- [Bun](https://bun.sh)
- make

## Lancer le projet

```bash
make data     # télécharge les données du match 1 dans data/
make server   # serveur sur http://localhost:8080
make client   # client sur http://localhost:5173
make test     # tests du serveur et vérification des types du client
```

Le serveur et le client se lancent chacun dans son terminal. Pour viser un autre serveur que le port 8080 de la machine, lancer le client avec `VITE_SERVER_URL=ws://hôte:port/match`.

Dans la page, le bandeau du haut affiche les images reçues et sautées, la taille moyenne d'une image et deux latences : à la réception, et l'âge de ce qui est à l'écran. Il permet aussi de :

- **demander moins d'images** au serveur, de 25 à 1 par seconde : ouvrir deux onglets à des fréquences différentes montre qu'un client lent ne retarde pas l'autre ;
- **choisir le format** des images, JSON ou binaire (`?format=binary` dans l'adresse de la page) ;
- **lisser le mouvement** entre deux images, au prix d'une image de retard ;
- **simuler un client lent** : chaque image occupe la page 60 ms, plus que les 40 ms qui les séparent.

## Les échanges entre le serveur et le navigateur

Trois messages en JSON, chacun avec sa version (`v`). Le contrat est décrit en miroir dans [Protocol.java](server/src/main/java/dev/baptiste/pitch_live/Protocol.java) et [protocol.ts](client/src/protocol.ts).

| Message | Sens | Quand | Contenu |
| --- | --- | --- | --- |
| `match` | serveur vers navigateur | une fois, à la connexion | les dimensions du terrain et les 28 joueurs (identifiant, équipe, numéro) |
| `frame` | serveur vers navigateur | à chaque image | numéro, période, temps de jeu, joueurs présents (identifiant et position en mètres), ballon ou `null`, heure d'envoi `sentAt` |
| `rate` | navigateur vers serveur | quand l'analyste change de fréquence | le nombre d'images par seconde voulu, de 1 à 25 |

Un client qui se connecte avec `?format=binary` reçoit la présentation en JSON, puis les images en binaire : 135 octets au lieu de 1 240, avec les joueurs désignés par leur rang dans la présentation. La disposition octet par octet est décrite dans [BinaryFrameEncoder.java](server/src/main/java/dev/baptiste/pitch_live/BinaryFrameEncoder.java).

```mermaid
sequenceDiagram
    participant N as Navigateur
    participant S as Serveur

    N->>S: ouverture de la WebSocket sur /match
    S-->>N: match, le terrain et la feuille de match
    S-->>N: frame, la dernière image publiée, sans attendre la suivante
    loop toutes les 40 ms
        S-->>N: frame, les positions et l'heure d'envoi
    end
    N->>S: rate, 5 images par seconde
    loop toutes les 200 ms
        S-->>N: frame, toujours la plus récente, les autres sont sautées
    end
    Note over N,S: la connexion tombe
    N->>S: reconnexion après 250 ms, puis un délai doublé à chaque échec, 5 s au plus
    S-->>N: match puis frame, comme à la première connexion
    N->>S: rate, 5 images par seconde, car le serveur a tout oublié
```

## À l'intérieur

Côté serveur, un client lent ne ralentit personne : l'horloge dépose chaque image dans la boîte aux lettres de chaque client sans jamais attendre, et chaque client a son propre thread d'envoi. Côté navigateur, la réception et le dessin sont séparés : la réception garde la dernière image, le dessin la relit au rythme de l'écran.

```mermaid
flowchart TB
    csv[("CSV de Metrica<br/>domicile et extérieur")]
    subgraph serveur["Serveur Spring Boot"]
        reader["TrackingReader<br/>une ligne de chaque fichier à la fois"]
        clock["Thread d'horloge<br/>une image toutes les 40 ms"]
        broadcaster["FrameBroadcaster<br/>le JSON est produit une seule fois"]
        mailboxA["Boîte aux lettres A<br/>une place, l'image en attente est remplacée"]
        mailboxB["Boîte aux lettres B<br/>une place, l'image en attente est remplacée"]
        senderA["Thread virtuel A<br/>envoie au rythme de son client"]
        senderB["Thread virtuel B<br/>envoie au rythme de son client"]
        reader --> clock --> broadcaster
        broadcaster --> mailboxA --> senderA
        broadcaster --> mailboxB --> senderB
    end
    subgraph navigateurA["Navigateur A"]
        reception["Réception<br/>garde la dernière image"]
        draw["Dessin dans le canvas<br/>au rythme de l'écran"]
        reception --> draw
    end
    navigateurB["Navigateur B"]
    csv --> reader
    senderA -- WebSocket --> reception
    senderB -- WebSocket --> navigateurB
    reception -. "rate" .-> senderA
```

## Ce que montrent les mesures

Mesuré en local, sur les premières secondes du match 1 : le serveur et le navigateur partagent la même horloge, la latence mesurée est donc juste. Entre deux machines, l'écart entre leurs horloges s'ajouterait à la mesure.

| Mesure | Résultat |
| --- | --- |
| Taille d'une image | 1 240 octets en JSON, 135 en binaire. Sur le réseau, la compression permessage-deflate, négociée par défaut, les ramène à environ 176 et 126 octets. |
| Latence à la réception | Moins de 2 ms à 25 images par seconde. De 11 à 27 ms quand le client demande moins d'images, le temps que l'image attende dans sa boîte aux lettres. |
| Âge de ce qui est à l'écran | 20 ms en moyenne à 25 images par seconde (42 au plus). 41 ms avec le lissage, mais régulier. À 1 image par seconde : 530 ms sans lissage, 1 010 ms avec. |
| Client lent | Un retard qui grandit sans limite : 32 s au bout de 95 s, sans qu'aucune image soit sautée côté serveur. À 10 images par seconde, le même client suit. |

La dernière ligne est la plus instructive. La boîte aux lettres d'une place protège des envois qui bloquent : réseau lent, client qui ne lit plus. Elle ne protège pas d'une page trop lente à traiter ce qu'elle reçoit, car les images s'entassent d'abord dans les tampons du système et du navigateur, qui font plusieurs mégaoctets, et le serveur ne ressent rien. Il faut alors que le client demande lui-même moins d'images, ou un contrôle de flux dans le protocole : le client accuse réception de chaque image, et le serveur n'en a jamais plus d'une en route vers lui.

## Les données

Les données de suivi viennent des [données d'exemple de Metrica Sports](https://github.com/metrica-sports/sample-data) : des matchs réels et anonymisés, avec la position de chaque joueur et du ballon 25 fois par seconde. Elles ne sont pas versionnées dans ce dépôt, `make data` les télécharge.
