#!/usr/bin/env bash
# Verifies the live Overpass endpoint accepts the queries the code emits after stage 2a.
#
# The URLs below are copied from what OverPassTollRouteProvider and OverpassSpeedLimitProvider
# actually build - same accuracy values, same [timeout:N], same unencoded [ ] ( ) ; and the same
# %20 in "out tags". They are pinned in OverpassQueryUrlTest; this is the part that test cannot
# do, because it must not touch the network.
#
# Read-only GETs, three of them. Safe to run against production.
#
# -g (--globoff) is REQUIRED: without it curl treats the [ ] in [out:json] as a glob range and
# fails with "bad range in URL" before sending anything. That is a curl artifact, not a server
# rejection - easy to misread as the endpoint refusing the query.
#
# Coordinates: field-drive position 14 (43.644752, -79.730620) - on the 407 ETR, where the
# recorded enrichment was isToll=true, tollRef="407 ETR". So a correct response is not just
# HTTP 200, it is a response containing a tolled way.

set -uo pipefail

ENDPOINT="http://roadinfo.iotrides.com/api/interpreter"
LAT="43.644752"
LON="-79.730620"

TOLL="${ENDPOINT}?data=[out:json][timeout:10];(way(around:10,${LAT},${LON});node(around:100,${LAT},${LON}););out%20tags;"
SPEED="${ENDPOINT}?data=[out:json][timeout:10];way[maxspeed](around:100,${LAT},${LON});out%20tags;"
CONTROL="${ENDPOINT}?data=[out:json];(way(around:10,${LAT},${LON});node(around:100,${LAT},${LON}););out%20tags;"

run() {
  local name="$1" url="$2" out="$3"
  printf '\n=== %s ===\n%s\n' "$name" "$url"
  curl -gsS -o "$out" -w 'HTTP %{http_code}   %{time_total}s   %{size_download} bytes\n' "$url" \
    || { echo "CURL FAILED"; return 1; }
  if command -v jq >/dev/null 2>&1; then
    echo "elements: $(jq '.elements | length' "$out" 2>/dev/null || echo '(not JSON)')"
    jq -r '.remark // empty' "$out" 2>/dev/null | sed 's/^/REMARK: /'
  else
    head -c 200 "$out"; echo
  fi
}

run "1. toll query (2a - with [timeout:10])"        "$TOLL"    /tmp/overpass_toll.json
run "2. speed-limit query (2a - ungated path)"      "$SPEED"   /tmp/overpass_speed.json
run "3. control: toll query WITHOUT [timeout:10]"   "$CONTROL" /tmp/overpass_control.json

cat <<'NOTES'

--- how to read this ---
HTTP 200 on 1 and 2            the change is accepted; this is the check you wanted.
HTTP 400 on 1 but 200 on 3     [timeout:10] is the problem - do not deploy the query change.
HTTP 400 on all three          the endpoint rejects the unencoded query entirely, which would
                               be pre-existing and would mean enrichment has never worked from
                               this host. Far more interesting than the gate.
A "remark" field               Overpass reports errors in-band with HTTP 200. Read it; a
                               timeout or memory remark still means the query was understood.
elements > 0 on query 1        the extract covers this coordinate and returns a tolled way -
                               confirms both syntax and data coverage.
elements == 0 on query 1       syntax fine, but the extract has nothing here. Worth knowing
                               separately; it is a data question, not a code one.

To check tolled data came back:
  jq '.elements[] | select(.tags.toll) | {type, id, tags}' /tmp/overpass_toll.json
NOTES
