#!/usr/bin/env bash -xeu

# Script to install the latest CLI with a bash wrapper
#
# If the script is hosted, the install can be done with:
#
# bash 4:
# source <(curl -L -N -s http://localhost:8080/cli/install.sh)
#
# bash 3 (mac):
# source /dev/stdin <<< "$(curl -L -N -s http://localhost:8080/cli/install.sh)"
#
# if not sourcing:
# bash <(curl -L -N -s http://localhost:8080/cli/install.sh)
#
# Optional: specify the install directory: ?installDirectory=mydirectory

# Prep the install dir
mkdir -p ${PWD}/.mojito

# Create the bash wrapper for the CLI
cat > ${PWD}/.mojito/mojito-tmp << 'EOF'
#!/usr/bin/env bash
if [ -z "${L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_ID:-}" ]; then
  echo "Environment variable L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_ID must be set before running this command."
fi
if [ -z "${L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_SECRET:-}" ]; then
  echo "Environment variable L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_SECRET must be set before running this command."
fi
export L10N_RESTTEMPLATE_AUTHENTICATION_MODE=HEADER
java -Dl10n.resttemplate.host=localhost \
     -Dl10n.resttemplate.scheme=http \
     -Dl10n.resttemplate.port=8080 \
     -Dlogging.file.path=${PWD}/.mojito \
     -jar ${PWD}/.mojito/mojito-cli.jar "$@" ;
EOF

_ESC="$(printf '%s' "${PWD}" | sed 's/[\/&]/\\&/g')"
sed "s|\${PWD}|$_ESC|g" ${PWD}/.mojito/mojito-tmp > ${PWD}/.mojito/mojito
rm ${PWD}/.mojito/mojito-tmp

# Make the wrapper executable
chmod +x ${PWD}/.mojito/mojito

# Export the PATH to have access to the bash wrapper once installation is done
export PATH=${PWD}/.mojito:${PATH}
# Ensure Cloudflare Zero Trust headers are available for authenticated downloads
if [ -z "${L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_ID:-}" ]; then
  echo "Environment variable L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_ID must be set before running this installation script."
  exit 1
fi
if [ -z "${L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_SECRET:-}" ]; then
  echo "Environment variable L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_SECRET must be set before running this installation script."
  exit 1
fi

export L10N_RESTTEMPLATE_AUTHENTICATION_MODE=HEADER

CURL_HEADERS=(
  -H "CF-Access-Client-Id: $L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_ID"
  -H "CF-Access-Client-Secret: $L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_SECRET"
)

# Download/Upgrade the jar file if needed to match server version.
if ! mojito --check-server-version 2>/dev/null; then
  (
    # Keep the installed JAR intact until its replacement has downloaded and runs.
    jar_download=$(mktemp "${PWD}/.mojito/mojito-cli.jar.XXXXXX") || exit 1
    trap 'rm -f "$jar_download"' EXIT

    # Randomize the start, then let curl back off and honor Retry-After. At most
    # 182 seconds: 2 seconds of jitter + 150 retry seconds + a final 30-second try.
    sleep "$((RANDOM % 3))"
    if curl --fail --location --show-error --no-progress-meter \
      --connect-timeout 10 --max-time 30 \
      --retry 8 --retry-max-time 150 --retry-connrefused "${CURL_HEADERS[@]}" \
      --output "$jar_download" "http://localhost:8080/cli/mojito-cli.jar?v=test-git-commit"; then
      if ! java -jar "$jar_download" --version >/dev/null; then
        echo "Downloaded Mojito CLI JAR is invalid; keeping the existing installation." >&2
        exit 1
      fi
      chmod 644 "$jar_download" && mv -f "$jar_download" "${PWD}/.mojito/mojito-cli.jar"
    else
      download_status=$?
      echo "Mojito CLI download failed; keeping the existing installation." >&2
      exit "$download_status"
    fi
  )
fi
