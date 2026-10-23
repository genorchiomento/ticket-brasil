#!/usr/bin/env bash
# Fires N concurrent reservations (1 ticket each) at an event with CAPACITY seats,
# through the nginx load balancer (two API instances), then checks for oversell.
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
CAPACITY=${CAPACITY:-100}
REQUESTS=${REQUESTS:-1000}
PARALLELISM=${PARALLELISM:-100}

starts_at=$(date -u -v+30d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+30 days' +%Y-%m-%dT%H:%M:%SZ)
event_id=$(curl -sf -X POST "$BASE_URL/events" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Flash Sale\",\"startsAt\":\"$starts_at\",\"capacity\":$CAPACITY}" | sed -E 's/.*"id":"([^"]+)".*/\1/')
echo "Event $event_id with $CAPACITY seats; firing $REQUESTS requests ($PARALLELISM in parallel)..."

results=$(seq 1 "$REQUESTS" | xargs -P "$PARALLELISM" -I{} curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST "$BASE_URL/events/$event_id/reservations" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: flash-$event_id-{}" \
  -d '{"customerId":"customer-{}","quantity":1}')

echo "$results" | sort | uniq -c | awk '{print "  HTTP " $2 ": " $1}'
created=$(echo "$results" | grep -c '^201$' || true)
sleep 1.5 # availability is eventually consistent (1s cache per instance)
available=$(curl -sf "$BASE_URL/events/$event_id" | sed -E 's/.*"available":([0-9]+).*/\1/')
echo "Reservations created: $created | available now: $available"

if [ "$created" -le "$CAPACITY" ] && [ $((created + available)) -eq "$CAPACITY" ]; then
  echo "OK: no oversell"
else
  echo "FAIL: oversell detected" && exit 1
fi
