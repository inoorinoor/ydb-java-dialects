#!/usr/bin/env bash
#
# Runs a Gatling load test scenario against Keycloak.
#
# Usage:
#   ./run.sh <scenario> <users-per-sec> [measurement-sec] [server-url]
#
# Examples:
#   ./run.sh CreateUsers 30
#   ./run.sh CreateUsers 30 60
#   ./run.sh CreateDeleteUsers 10 60 http://localhost:9090
#
# Available scenarios:
#   CreateUsers          - Create + List users
#   CreateDeleteUsers    - Create + List + Delete users
#   ClientSecret         - Client credentials grant
#   AuthorizationCode    - Authorization code flow
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIB_DIR="$SCRIPT_DIR/lib"
RESULTS_DIR="$SCRIPT_DIR/results"

if [ ! -d "$LIB_DIR" ] || [ -z "$(ls -A "$LIB_DIR" 2>/dev/null)" ]; then
    echo "ERROR: No JARs found in $LIB_DIR"
    echo "Run ./prepare.sh first"
    exit 1
fi

SCENARIO="${1:?Usage: ./run.sh <scenario> <users-per-sec> [measurement-sec] [server-url]}"
USERS_PER_SEC="${2:?Usage: ./run.sh <scenario> <users-per-sec> [measurement-sec] [server-url]}"
MEASUREMENT="${3:-60}"
SERVER_URL="${4:-http://localhost:9090}"
REALM="${5:-test-realm}"

# Resolve full scenario class name
case "$SCENARIO" in
    CreateUsers|CreateDeleteUsers|CreateClients|CreateDeleteClients|CreateRealms)
        SCENARIO_CLASS="keycloak.scenario.admin.$SCENARIO"
        ;;
    ClientSecret|AuthorizationCode)
        SCENARIO_CLASS="keycloak.scenario.authentication.$SCENARIO"
        ;;
    *)
        SCENARIO_CLASS="$SCENARIO"
        ;;
esac

CLASSPATH=$(find "$LIB_DIR" -type f -name '*.jar' | tr '\n' ':')

echo "============================================"
echo "  Scenario:     $SCENARIO_CLASS"
echo "  Users/sec:    $USERS_PER_SEC"
echo "  Measurement:  ${MEASUREMENT}s"
echo "  Server:       $SERVER_URL"
echo "  Realm:        $REALM"
echo "============================================"
echo

java -server -Xmx1G \
    -Dserver-url="$SERVER_URL" \
    -Drealm-name="$REALM" \
    -Dclient-id=gatling \
    -Dclient-secret=setup-for-benchmark \
    -Dusers-per-sec="$USERS_PER_SEC" \
    -Dmeasurement="$MEASUREMENT" \
    -cp "$CLASSPATH" \
    io.gatling.app.Gatling \
    -rf "$RESULTS_DIR" \
    -s "$SCENARIO_CLASS"
