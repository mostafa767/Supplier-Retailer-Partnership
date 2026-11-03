API=http://localhost:8080
MP_A=11111111-1111-1111-1111-111111111111
LAC="a2000000-0000-0000-0000-000000000001"
AS_LAC=( -H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: RETAILER" -H "X-Actor-Id: $LAC")

# Terminate the partnership (R6): subscriptions cancelled, offers disappear from the catalogue
curl -s -X POST $API/api/partnerships/a3000000-0000-0000-0000-000000000001/termination \
     -H "X-Marketplace-Id: $MP_A" -H "X-Actor-Role: MARKETPLACE_ADMIN" -H "X-Actor-Id: 00000000-0000-0000-0000-0000000000ad"
curl -s $API/api/catalogue "${AS_LAC[@]}"   # -> empty

# Errors are consistent Problem Details, with a correlation id to find the logs
curl -s -i $API/api/catalogue