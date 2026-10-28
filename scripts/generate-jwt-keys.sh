#!/usr/bin/env bash
# Generates the RS256 key pair for JWTs.
#   keys/jwt_private.pem  -> auth-service only (signs access tokens)
#   keys/jwt_public.pem   -> wallet-service, payment-service (verify only)
# Usage: ./scripts/generate-jwt-keys.sh [output-dir]   (default: keys)
set -euo pipefail

DIR="${1:-keys}"
PRIVATE="$DIR/jwt_private.pem"
PUBLIC="$DIR/jwt_public.pem"

command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

if [[ -f "$PRIVATE" ]]; then
  echo "$PRIVATE already exists. Delete it first to rotate keys (all users must log in again)." >&2
  exit 1
fi

mkdir -p "$DIR"
# PKCS#8 private key ("BEGIN PRIVATE KEY") + X.509 SubjectPublicKeyInfo public key ("BEGIN PUBLIC KEY")
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIVATE" 2>/dev/null
openssl pkey -in "$PRIVATE" -pubout -out "$PUBLIC"

# Containers run as a non-root user and docker compose bind-mounts these files as-is,
# so they must be readable by "others". Dev only: use a real secret store in production.
chmod 644 "$PRIVATE" "$PUBLIC"

echo "Generated $PRIVATE and $PUBLIC"
