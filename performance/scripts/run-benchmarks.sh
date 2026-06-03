#!/bin/bash
# =============================================================================
# ScaleKit Performance Benchmark Runner
# =============================================================================
# Runs all JMeter load tests and collects results.
# Prerequisites: JMeter 5.6.3+, Java 21, running ScaleKit instance
# =============================================================================

set -euo pipefail

# ── Configuration ────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$(dirname "$SCRIPT_DIR")")"
JMETER_DIR="$SCRIPT_DIR/../jmeter"
RESULTS_DIR="$PROJECT_ROOT/performance/results/$(date +%Y%m%d_%H%M%S)"
HOST="${HOST:-localhost}"
PORT="${PORT:-8080}"
JMETER_HOME="${JMETER_HOME:-}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# ── Functions ────────────────────────────────────────────────────────────────
log_info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

check_prerequisites() {
    log_info "Checking prerequisites..."

    if [ -z "$JMETER_HOME" ]; then
        if command -v jmeter &>/dev/null; then
            JMETER_CMD="jmeter"
        else
            log_error "JMeter not found. Set JMETER_HOME or add jmeter to PATH."
            exit 1
        fi
    else
        JMETER_CMD="$JMETER_HOME/bin/jmeter"
        if [ ! -f "$JMETER_CMD" ]; then
            log_error "JMeter not found at $JMETER_CMD"
            exit 1
        fi
    fi

    # Check if app is running
    if ! curl -sf "http://$HOST:$PORT/actuator/health" > /dev/null 2>&1; then
        log_warn "ScaleKit not responding at http://$HOST:$PORT"
        log_warn "Make sure the application is running before benchmarks."
        read -p "Continue anyway? (y/N) " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    else
        log_ok "ScaleKit is running at http://$HOST:$PORT"
    fi
}

run_jmeter_test() {
    local test_name="$1"
    local jmx_file="$2"
    local result_file="$RESULTS_DIR/${test_name}.csv"
    local log_file="$RESULTS_DIR/${test_name}.log"

    log_info "Running $test_name..."

    $JMETER_CMD -n \
        -t "$jmx_file" \
        -l "$result_file" \
        -j "$log_file" \
        -JHOST="$HOST" \
        -JPORT="$PORT" \
        -e -o "$RESULTS_DIR/${test_name}-report" \
        2>&1 | tail -5

    if [ $? -eq 0 ]; then
        log_ok "$test_name completed. Results: $result_file"
    else
        log_error "$test_name failed. Check log: $log_file"
    fi
}

generate_summary() {
    local summary_file="$RESULTS_DIR/SUMMARY.md"
    log_info "Generating summary report..."

    cat > "$summary_file" << 'HEADER'
# ScaleKit Performance Test Summary

| Test | Samples | Avg (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Error % |
|------|---------|----------|----------|----------|--------------------|---------|
HEADER

    for csv_file in "$RESULTS_DIR"/*.csv; do
        if [ -f "$csv_file" ]; then
            local test_name=$(basename "$csv_file" .csv)
            local samples=$(tail -n +2 "$csv_file" | wc -l)
            if [ "$samples" -gt 0 ]; then
                local avg=$(tail -n +2 "$csv_file" | awk -F',' '{sum+=$2} END {printf "%.1f", sum/NR}')
                echo "| $test_name | $samples | $avg | - | - | - | - |" >> "$summary_file"
            fi
        fi
    done

    log_ok "Summary report: $summary_file"
}

# ── Main ─────────────────────────────────────────────────────────────────────
main() {
    echo "=================================================================="
    echo "  ScaleKit Performance Benchmark Suite"
    echo "  $(date)"
    echo "=================================================================="
    echo

    check_prerequisites
    mkdir -p "$RESULTS_DIR"

    log_info "Results directory: $RESULTS_DIR"
    echo

    # Run all test plans
    run_jmeter_test "url-shortener"      "$JMETER_DIR/url-shortener-test.jmx"
    run_jmeter_test "rate-limiter"        "$JMETER_DIR/rate-limiter-test.jmx"
    run_jmeter_test "cache"               "$JMETER_DIR/cache-test.jmx"
    run_jmeter_test "bloom-filter"        "$JMETER_DIR/bloom-filter-test.jmx"
    run_jmeter_test "consistent-hashing"  "$JMETER_DIR/consistent-hashing-test.jmx"

    echo
    generate_summary

    echo
    echo "=================================================================="
    echo "  All benchmarks complete!"
    echo "  Results: $RESULTS_DIR"
    echo "=================================================================="
}

main "$@"
