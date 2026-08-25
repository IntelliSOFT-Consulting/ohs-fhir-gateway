#!/usr/bin/env bash
#
# Copyright 2021-2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Verify NGSA FHIR data alignment with the FHIR gateway location plugin.
# Run when HAPI and gateway containers are up.
#
# Usage:
#   FHIR=http://localhost:8086/fhir GATEWAY=http://localhost:8090/fhir \
#     ROOT_LOCATION_ID=kenya CHA_TOKEN=... ./scripts/verify-ngsa-fhir-alignment.sh

set -euo pipefail

FHIR="${FHIR:-http://localhost:8086/fhir}"
GATEWAY="${GATEWAY:-http://localhost:8090/fhir}"
ROOT_LOCATION_ID="${ROOT_LOCATION_ID:-}"
SUPERVISION_TAG_SYSTEM="${SUPERVISION_TAG_SYSTEM:-https://www.example.com/CodeSystem/supervision-location}"

echo "=== NGSA FHIR / Gateway alignment checks ==="
echo "FHIR:    $FHIR"
echo "Gateway: $GATEWAY"
echo

check_http() {
  local url="$1"
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" "$url" || echo "000")
  echo "$code"
}

hapi_code=$(check_http "$FHIR/metadata")
gw_code=$(check_http "$GATEWAY/metadata")
echo "1. Connectivity"
echo "   HAPI metadata:    HTTP $hapi_code"
echo "   Gateway metadata: HTTP $gw_code"
if [[ "$hapi_code" != "200" ]]; then
  echo "   WARN: HAPI not reachable; remaining checks may fail."
fi
echo

echo "2. Location hierarchy sample"
curl -sf "$FHIR/Location?_count=5" | jq -r '.entry[]?.resource | "- \(.id): type=\(.type[0].coding[0].code // "?") partOf=\(.partOf.reference // "none")"' 2>/dev/null || echo "   (failed)"
echo

if [[ -n "$ROOT_LOCATION_ID" ]]; then
  echo "3. Country root ($ROOT_LOCATION_ID)"
  curl -sf "$FHIR/Location/$ROOT_LOCATION_ID" | jq '{id, type: .type[0].coding[0].code, partOf: .partOf.reference}' 2>/dev/null || echo "   (not found)"
else
  echo "3. Country root: ROOT_LOCATION_ID not set — skip"
fi
echo

echo "4. Supervision tags on Patients"
tagged_total=$(curl -sf "$FHIR/Patient?_tag=${SUPERVISION_TAG_SYSTEM}|&_summary=count" | jq -r '.total // 0' 2>/dev/null || echo "?")
echo "   Patients with supervision-location tag: $tagged_total"
curl -sf "$FHIR/Patient?_count=2" | jq -r '.entry[]?.resource.meta.tag[]? | select(.system | contains("supervision")) | "   sample: \(.system)|\(.code)"' 2>/dev/null || true
echo

echo "5. PractitionerRole codes (portal users)"
curl -sf "$FHIR/PractitionerRole?_count=5" | jq -r '.entry[]?.resource | "- \(.id): code=\(.code[0].coding[0].code) location=\(.location[0].reference)"' 2>/dev/null || echo "   (failed)"
echo

echo "6. Multi-CU CHAs (duplicate practitioner references)"
curl -sf "$FHIR/PractitionerRole?code=community-health-assistant&_count=200" | \
  jq '[.entry[]?.resource.practitioner.reference] | group_by(.) | map(select(length > 1)) | length' 2>/dev/null || echo "   (failed)"
echo "   practitioners with >1 PractitionerRole (count above)"
echo

echo "7. Counties missing partOf"
if [[ -n "$ROOT_LOCATION_ID" ]]; then
  curl -sf "$FHIR/Location?type=county&_count=100" | \
    jq --arg root "Location/$ROOT_LOCATION_ID" '[.entry[]?.resource | select(.partOf.reference != $root) | .id]' 2>/dev/null || echo "   (failed)"
else
  curl -sf "$FHIR/Location?type=county&_count=50" | \
    jq '[.entry[]?.resource | select(.partOf == null) | .id]' 2>/dev/null || echo "   (failed)"
fi
echo

if [[ -n "${CHA_TOKEN:-}" ]]; then
  echo "8. Gateway smoke (CHA JWT)"
  gw_patient=$(curl -sf -H "Authorization: Bearer $CHA_TOKEN" "$GATEWAY/Patient?_count=5" | jq -r '.total // "error"' 2>/dev/null || echo "error")
  echo "   Gateway Patient search total: $gw_patient"
else
  echo "8. Gateway smoke: CHA_TOKEN not set — skip"
fi

echo
echo "=== Done ==="
