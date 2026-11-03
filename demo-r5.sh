#!/usr/bin/env bash
# R5 demo: publish offers and watch the partner retailers being informed.
# Run from Git Bash:   bash demo-r5.sh
# Then open http://localhost:8025 (Mailpit) and wait ~30 seconds.
#
# Note: this works fully only once per database. Running it again gives "alreadySubmitted":true for the
# file and an error for the publication (the draft is already published). Reset with: docker compose down -v

cd "$(dirname "$0")" || exit 1   # so that samples/ is found wherever you run it from

API=http://localhost:8080
MP_A=11111111-1111-1111-1111-111111111111
NIVEA=a1000000-0000-0000-0000-000000000001
AS_NIVEA=(-H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: SUPPLIER" -H "X-Actor-Id: $NIVEA")

echo "=== 1. Import a file that publishes 3 offers (publish=true) ==="
curl -sS "$API/api/imports" "${AS_NIVEA[@]}" -F "file=@samples/offers-valid.csv"
echo; echo

echo "=== 2. Publish the seeded draft NIV-DRAFT-03 through the API ==="
curl -sS -X POST "$API/api/offers/a4000000-0000-0000-0000-000000000003/publication" "${AS_NIVEA[@]}"
echo; echo

echo "Now open http://localhost:8025"
echo "Within ~30 seconds, ONE digest e-mail per partner retailer arrives (Pharmacie du Lac and PharmaOnline),"
echo "listing all the new offers together. Retailers of marketplace B receive nothing."
