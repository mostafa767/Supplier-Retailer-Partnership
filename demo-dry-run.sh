API=http://localhost:8080
MP_A=11111111-1111-1111-1111-111111111111
NIVEA=a1000000-0000-0000-0000-000000000001
AS_NIVEA=(-H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: SUPPLIER" -H "X-Actor-Id: $NIVEA")

# # Check a file BEFORE importing it: what would be created or updated, problems grouped by code. Nothing is stored.
curl -s $API/api/imports/dry-run "${AS_NIVEA[@]}" -F "file=@samples/offers-with-errors.csv"

