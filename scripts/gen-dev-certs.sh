#!/usr/bin/env bash
# Generate self-signed PEM material for local HTTPS development.
#
# Output (gitignored):
#   src/main/resources/certs/dev/server.crt        - inbound server leaf (SAN: localhost, 127.0.0.1)
#   src/main/resources/certs/dev/server.key        - inbound server private key (PKCS#8, unencrypted)
#   src/main/resources/certs/dev/downstream-ca.crt - CA cert trusted for outbound WebClient calls
#
# These files are DEV ONLY. Do not commit them and do not use them anywhere
# other than your local machine. CI and production must supply real material
# via spring.ssl.bundle.* referencing mounted secrets.
#
# Usage:
#   ./scripts/gen-dev-certs.sh

set -euo pipefail

OUT_DIR="${OUT_DIR:-src/main/resources/certs/dev}"
DAYS="${DAYS:-365}"
CN="${CN:-localhost}"

mkdir -p "${OUT_DIR}"

echo ">> Generating server key + self-signed cert in ${OUT_DIR}"
openssl req \
    -x509 \
    -newkey rsa:2048 \
    -nodes \
    -keyout "${OUT_DIR}/server.key" \
    -out "${OUT_DIR}/server.crt" \
    -days "${DAYS}" \
    -subj "/CN=${CN}" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

# Normalize to PKCS#8 for Spring Boot's PemSslBundle loader.
openssl pkcs8 -topk8 -nocrypt -in "${OUT_DIR}/server.key" -out "${OUT_DIR}/server.key.pk8"
mv "${OUT_DIR}/server.key.pk8" "${OUT_DIR}/server.key"

# Dev downstream trust: reuse the server cert as the trusted anchor so the aggregation
# service can call a downstream running with the same dev cert. Swap this out per-downstream
# in real environments.
cp "${OUT_DIR}/server.crt" "${OUT_DIR}/downstream-ca.crt"

chmod 600 "${OUT_DIR}/server.key"

echo ">> Done."
echo "   Files under ${OUT_DIR} are gitignored. Regenerate whenever they expire (${DAYS} days)."