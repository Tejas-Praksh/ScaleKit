#!/bin/bash
# =============================================================================
# Quick Smoke Test — verifies all endpoints respond before full benchmark
# =============================================================================

set -euo pipefail

HOST="${HOST:-localhost}"
PORT="${PORT:-8080}"
BASE="http://$HOST:$PORT"
PASSED=0
FAILED=0

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

check() {
    local name="$1"
    local url="$2"
    local method="${3:-GET}"
    local data="${4:-}"

    if [ -n "$data" ]; then
        status=$(curl -sf -o /dev/null -w "%{http_code}" -X "$method" -H "Content-Type: application/json" -d "$data" "$url" 2>/dev/null || echo "000")
    else
        status=$(curl -sf -o /dev/null -w "%{http_code}" -X "$method" "$url" 2>/dev/null || echo "000")
    fi

    if [[ "$status" =~ ^(200|201|302|429)$ ]]; then
        echo -e "  ${GREEN}✓${NC} $name (HTTP $status)"
        ((PASSED++))
    else
        echo -e "  ${RED}✗${NC} $name (HTTP $status)"
        ((FAILED++))
    fi
}

echo "=== ScaleKit Smoke Test ==="
echo "Target: $BASE"
echo

echo "Health:"
check "Actuator Health" "$BASE/actuator/health"
check "Actuator Info"   "$BASE/actuator/info"

echo
echo "URL Shortener:"
check "Create Short URL" "$BASE/api/url/shorten" "POST" '{"longUrl":"https://example.com/smoke-test"}'
check "List URLs"        "$BASE/api/url/list"

echo
echo "Rate Limiter:"
check "Token Bucket"    "$BASE/api/ratelimit/check/smoke-test?algorithm=TOKEN_BUCKET"
check "Sliding Window"  "$BASE/api/ratelimit/check/smoke-test?algorithm=SLIDING_WINDOW"
check "Fixed Window"    "$BASE/api/ratelimit/check/smoke-test?algorithm=FIXED_WINDOW"

echo
echo "Cache:"
check "LRU Put"  "$BASE/api/cache/lru/put"  "POST" '{"key":"smoke","value":"test"}'
check "LRU Get"  "$BASE/api/cache/lru/get/smoke"
check "LFU Put"  "$BASE/api/cache/lfu/put"  "POST" '{"key":"smoke","value":"test"}'

echo
echo "Bloom Filter:"
check "Add Element"   "$BASE/api/cache/bloom/add"   "POST" '{"element":"smoke-test"}'
check "Check Element" "$BASE/api/cache/bloom/check?element=smoke-test"

echo
echo "Consistent Hashing:"
check "Add Node"    "$BASE/api/cache/hash-ring/nodes"    "POST" '{"nodeName":"smoke-node"}'
check "Lookup Key"  "$BASE/api/cache/hash-ring/lookup?key=smoke"

echo
echo "══════════════════════════════"
echo "  Passed: $PASSED  Failed: $FAILED"
echo "══════════════════════════════"

[ $FAILED -eq 0 ] && exit 0 || exit 1
