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

Le serveur et le client se lancent chacun dans son terminal.

## Les données

Les données de suivi viennent des [données d'exemple de Metrica Sports](https://github.com/metrica-sports/sample-data) : des matchs réels et anonymisés, avec la position de chaque joueur et du ballon 25 fois par seconde. Elles ne sont pas versionnées dans ce dépôt, `make data` les télécharge.
