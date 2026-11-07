.PHONY: help data server client test

help:
	@echo "make data     télécharge les données du match 1 de Metrica"
	@echo "make server   lance le serveur Spring Boot sur le port 8080"
	@echo "make client   lance le client Vite sur le port 5173"
	@echo "make test     lance les tests du serveur et vérifie les types du client"

data:
	./scripts/download-data.sh

server:
	cd server && ./mvnw spring-boot:run

client: client/node_modules
	cd client && bun run dev

test: client/node_modules
	cd server && ./mvnw test
	cd client && bunx tsc

client/node_modules: client/package.json client/bun.lock
	cd client && bun install
	@touch $@
