API=http://localhost:8080
MP_A=11111111-1111-1111-1111-111111111111
NIVEA=a1000000-0000-0000-0000-000000000001
AS_NIVEA=(-H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: SUPPLIER" -H "X-Actor-Id: $NIVEA")

curl -s -i $API/api/imports "${AS_NIVEA[@]}" -F "file=@samples/offers-with-errors.csv"
# -> 202 Accepted, Location: /api/imports/{id}. Copy the id:
IMPORT= <id> ## 88c9d3df-5b9f-43fb-97fe-ab584e0a9426

curl -s $API/api/imports/$IMPORT "${AS_NIVEA[@]}"                 # COMPLETED: created 2, failed 5
curl -s "$API/api/imports/$IMPORT/errors" "${AS_NIVEA[@]}"         # every problem, with line numbers
curl -s "$API/api/imports/$IMPORT/errors.csv" "${AS_NIVEA[@]}"     # same report as CSV

### Send the SAME file again: 200 OK, same id, "alreadySubmitted": true - nothing imported twice.
curl -s $API/api/imports "${AS_NIVEA[@]}" -F "file=@samples/offers-with-errors.csv"


##---------------------

# curl -s -i $API/api/imports "${AS_NIVEA[@]}" -H "Content-Type: application/json" -d '{"offers": [
#   {"reference": "API-01", "title": "Hand cream", "discountType": "PERCENTAGE", "discountValue": 20,
#    "validFrom": "2026-11-01", "validTo": "2027-01-31", "publish": true},
#   {"reference": "API-02", "title": "Too generous", "discountType": "PERCENTAGE", "discountValue": 150,
#    "validFrom": "2026-11-01", "validTo": "2027-01-31"}
# ]}'
# 202 + Location. Same job, same report; problems point to the item: "itemIndex": 1, "field": "discountValue".

