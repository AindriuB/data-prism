# Generates the quickstart's throwaway TLS material into the `certs` volume
# and exits. Nothing this image builds is ever run outside `docker compose`;
# it exists only so keytool (bundled with the JRE) is available without a
# host install.
FROM eclipse-temurin:21-jre
COPY docker/certs-init/generate-certs.sh /generate-certs.sh
RUN chmod +x /generate-certs.sh
ENTRYPOINT ["/generate-certs.sh"]
