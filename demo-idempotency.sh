API="http://localhost:8080"
MP_A="11111111-1111-1111-1111-111111111111"
LAC="a2000000-0000-0000-0000-000000000001"
AS_LAC=( -H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: RETAILER" -H "X-Actor-Id: $LAC")
OFFER_ID="a4000000-0000-0000-0000-000000000001"

echo "First subscription (expect 201 Created):"

curl -s -i -X POST \
  "$API/api/offers/$OFFER_ID/subscriptions" \
  "${AS_LAC[@]}"

echo ""
echo "Waiting 2 seconds..."
sleep 2

echo ""
echo "Calling again (expect 200 OK):"

curl -s -i -X POST \
  "$API/api/offers/$OFFER_ID/subscriptions" \
  "${AS_LAC[@]}"

echo ""