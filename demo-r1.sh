#!/usr/bin/env bash
# R1 demo: two marketplaces, never mixed.
# Run from Git Bash:   bash demo-r1.sh
# Or keep the variables in your terminal afterwards:   source demo-r1.sh

API=http://localhost:8080
MP_A=11111111-1111-1111-1111-111111111111
MP_B=22222222-2222-2222-2222-222222222222
NIVEA=a1000000-0000-0000-0000-000000000001
LAC=a2000000-0000-0000-0000-000000000001
MARTIN=b2000000-0000-0000-0000-000000000001

AS_NIVEA=(-H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: SUPPLIER" -H "X-Actor-Id: $NIVEA")
AS_LAC=(-H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: RETAILER" -H "X-Actor-Id: $LAC")
AS_MARTIN=(-H "X-Marketplace-Id: $MP_B" -H "X-Actor-Role: RETAILER" -H "X-Actor-Id: $MARTIN")

echo "=== Pharmacie du Lac (marketplace A): expect Nivea offers only ==="
curl -sS "$API/api/catalogue" "${AS_LAC[@]}"
echo
echo
echo "=== Epicerie Martin (marketplace B): expect the Danone offer only ==="
curl -sS "$API/api/catalogue" "${AS_MARTIN[@]}"
echo
