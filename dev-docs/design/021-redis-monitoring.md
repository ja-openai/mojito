# Redis monitoring

## Scope

Redis is an optional operational dependency. Enabling it creates a Lettuce client and exposes
admin-only monitoring; it does not move blob storage, application sessions, caches, or Quartz
payloads into Redis.

## Local instance

Start the Redis service without starting MySQL or the application container:

```bash
docker compose -f docker/docker-compose.yml up -d redis
```

The container publishes Redis only on `127.0.0.1:6379`. Verify it with:

```bash
docker compose -f docker/docker-compose.yml exec redis redis-cli PING
```

For a locally running Mojito application using the `npm` profile, add the connection settings to
`~/.l10n/config/webapp/application-npm.properties`:

```properties
l10n.redis.enabled=true
l10n.redis.host=127.0.0.1
l10n.redis.port=6379
l10n.redis.database=0
```

The full Docker Compose application enables Redis automatically and connects to the `redis` service
hostname. Production deployments must opt in explicitly and can additionally configure
`l10n.redis.username`, `l10n.redis.password`, `l10n.redis.ssl`, and `l10n.redis.timeout`.

## Admin dashboard

Open **Redis** next to **Azure Storage** in the account menu, or visit `/monitoring/redis`. The
dashboard shows connectivity, endpoint and database, Redis version, uptime, memory consumption,
connected clients, and the selected database's key count.

**Run write/read/delete probe** creates a random monitoring key with a 60-second TTL, verifies its
value, and explicitly deletes it. The expiration bounds cleanup if the application disconnects
between writing and deleting the key.

The endpoints `/api/monitoring/redis` and `/api/monitoring/redis/probe` inherit the existing
admin-only `/api/monitoring/**` security rule. Credentials are never returned in the monitoring
response, and Redis connection failures do not change application readiness.
