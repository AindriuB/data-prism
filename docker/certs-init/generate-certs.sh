#!/bin/sh
# Generates one throwaway, self-signed PKCS12 keystore and a matching
# truststore, both into $CERT_DIR (a Docker-managed named volume, never a
# path inside this repository). Runs once, at first `docker compose up`: if
# the keystore already exists, this is a no-op, so restarting the stack does
# not rotate credentials under it.
#
# The certificate this generates is never committed, never leaves this
# machine's Docker storage, and identifies nothing real — it exists only so
# the standalone server (task 21) has an HTTPS JWKS location and HTTPS
# source base URLs to trust for this local demonstration, per CLAUDE.md
# rule 7.
set -eu

CERT_DIR="${CERT_DIR:-/certs}"
PASSWORD="${CERT_PASSWORD:?CERT_PASSWORD must be set}"
mkdir -p "$CERT_DIR"

if [ -f "$CERT_DIR/server.p12" ] && [ -f "$CERT_DIR/truststore.p12" ]; then
  echo "quickstart TLS material already present in $CERT_DIR; leaving it as is"
  exit 0
fi

keytool -genkeypair -alias quickstart -keyalg RSA -keysize 2048 -validity 2 \
  -keystore "$CERT_DIR/server.p12" -storetype PKCS12 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "CN=data-prism-quickstart" \
  -ext "san=dns:issuer,dns:fixtures,dns:localhost,ip:127.0.0.1"

keytool -exportcert -alias quickstart -keystore "$CERT_DIR/server.p12" \
  -storetype PKCS12 -storepass "$PASSWORD" -file "$CERT_DIR/server.cer"

keytool -importcert -alias quickstart -file "$CERT_DIR/server.cer" \
  -keystore "$CERT_DIR/truststore.p12" -storetype PKCS12 \
  -storepass "$PASSWORD" -noprompt

echo "generated quickstart-only TLS material in $CERT_DIR"
echo "(a Docker-managed volume; nothing here is committed or leaves this machine)"
