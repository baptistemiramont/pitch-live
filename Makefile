.PHONY: help data server client test

help:
	@echo "make data     downloads match 1 from Metrica"
	@echo "make server   runs the Spring Boot server on port 8080"
	@echo "make client   runs the Vite client on port 5173"
	@echo "make test     runs the server tests and type-checks the client"

data:
	./scripts/download-data.sh

server:
	cd server && ./mvnw spring-boot:run

client: client/node_modules
	cd client && bun run dev

test: data client/node_modules
	cd server && ./mvnw test
	cd client && bunx tsc

client/node_modules: client/package.json client/bun.lock
	cd client && bun install
	@touch $@
