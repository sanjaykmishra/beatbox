.PHONY: up down build logs ps db test api-test web-test render-test clean

up:
	docker compose up -d --build

down:
	docker compose down

build:
	docker compose build

logs:
	docker compose logs -f

ps:
	docker compose ps

# psql shell against the running postgres container
db:
	docker compose exec postgres psql -U beat -d beat

# Run all backend tests (requires Docker for Testcontainers — already present here)
api-test:
	cd api && ./gradlew --no-daemon test

web-test:
	cd web && npm run typecheck && npm run lint && npm run build

render-test:
	cd render && npm run typecheck && npm run build

test: api-test web-test render-test

# Wipe containers AND postgres volume (drops all local data)
clean:
	docker compose down -v
