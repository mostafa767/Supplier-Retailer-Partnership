#!/usr/bin/env bash
# R7 demo: who changed what and when (audit trail).
# Run from Git Bash:   bash demo-r7.sh
#
# Uses the import of samples/offers-with-errors.csv. If demo-r3.sh already sent that file, the API returns
# the same import (nothing is imported twice), so this works whether or not you ran demo-r3.sh first.

cd "$(dirname "$0")" || exit 1   # so that samples/ is found wherever you run it from

API=http://localhost:8080
MP_A=11111111-1111-1111-1111-111111111111
NIVEA=a1000000-0000-0000-0000-000000000001
ADMIN=00000000-0000-0000-0000-0000000000ad
AS_NIVEA=(-H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: SUPPLIER" -H "X-Actor-Id: $NIVEA")
AS_ADMIN=(-H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: MARKETPLACE_ADMIN" -H "X-Actor-Id: $ADMIN")

echo "=== 1. Get the import id of samples/offers-with-errors.csv ==="
IMPORT=$(curl -sS "$API/api/imports" "${AS_NIVEA[@]}" -F "file=@samples/offers-with-errors.csv" \
         | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Import id: $IMPORT"
if [ -z "$IMPORT" ]; then echo "No import id received - is the API running?"; exit 1; fi
sleep 3   # if the import is new, give the worker a moment to process it
echo

echo "=== 2. Audit trail of this import, as marketplace admin ==="
echo "Expect IMPORT_SUBMITTED, then one OFFERS_IMPORTED per chunk: who, when, what, and the correlation id."
curl -sS "$API/api/audit-events?entityId=$IMPORT" "${AS_ADMIN[@]}"
echo
