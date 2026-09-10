#!/usr/bin/env sh
# RS256 서명용 개인키를 만들어 JWT_PRIVATE_KEY 에 넣을 값(Base64 PKCS#8 DER, 한 줄)을 출력한다.
#
#   사용법: scripts/gen-jwt-key.sh >> .env      # 이후 .env 의 마지막 줄 앞에 JWT_PRIVATE_KEY= 를 붙인다
#   또는:   JWT_PRIVATE_KEY=$(scripts/gen-jwt-key.sh)
#
# genpkey 의 DER 출력은 PKCS#1 이라 Java 의 PKCS8EncodedKeySpec 이 못 읽는다. pkcs8 로 한 번 더 감싼다.
# 결과가 MIIEv... 로 시작하면 PKCS#8, MIIEp.../MIIEo... 로 시작하면 PKCS#1 이다.
set -eu

command -v openssl >/dev/null 2>&1 || { echo "openssl 이 필요합니다" >&2; exit 1; }

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null \
  | openssl pkcs8 -topk8 -nocrypt -outform DER \
  | openssl base64 -A
echo
