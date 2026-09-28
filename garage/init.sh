#!/bin/sh
# One-shot bootstrap of the local Garage (docker-compose-local.yml, runs in alpine). Garage has no
# root user: give the single node a layout role, import a fixed access key and create the "ister"
# bucket for it. Idempotent, so it can run on every `up`.
set -eu
TOKEN=localdevtoken
ACCESS_KEY=GK00000000000000000000000a
SECRET_KEY=000000000000000000000000000000000000000000000000000000000000000a

apk add --no-cache curl jq >/dev/null
api() { curl -fsS -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "http://garage:3903/v2/$1" ${2:+-d "$2"}; }

until api GetClusterStatus >/dev/null 2>&1; do sleep 1; done

if [ "$(api GetClusterLayout | jq .version)" = 0 ]; then
  node=$(api GetClusterStatus | jq -r '.nodes[0].id')
  api UpdateClusterLayout "{\"roles\":[{\"id\":\"$node\",\"zone\":\"dc1\",\"capacity\":10000000000,\"tags\":[]}]}" >/dev/null
  api ApplyClusterLayout '{"version":1}' >/dev/null
fi

api "GetKeyInfo?id=$ACCESS_KEY" >/dev/null 2>&1 \
  || api ImportKey "{\"accessKeyId\":\"$ACCESS_KEY\",\"secretAccessKey\":\"$SECRET_KEY\",\"name\":\"ister\"}" >/dev/null

bucket=$(api "GetBucketInfo?globalAlias=ister" 2>/dev/null | jq -r .id || true)
[ -n "$bucket" ] || bucket=$(api CreateBucket '{"globalAlias":"ister"}' | jq -r .id)
api AllowBucketKey "{\"bucketId\":\"$bucket\",\"accessKeyId\":\"$ACCESS_KEY\",\"permissions\":{\"read\":true,\"write\":true,\"owner\":true}}" >/dev/null
echo "garage ready: bucket ister, access key $ACCESS_KEY"
