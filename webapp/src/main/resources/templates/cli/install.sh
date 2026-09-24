#!/usr/bin/env bash -xeu

# Script to install the latest CLI with a bash wrapper
#
# If the script is hosted, the install can be done with:
#
# bash 4:
# source <(curl -L -N -s {{scheme}}://{{host}}:{{port}}/cli/install.sh)
#
# bash 3 (mac):
# source /dev/stdin <<< "$(curl -L -N -s {{scheme}}://{{host}}:{{port}}/cli/install.sh)"
#
# if not sourcing:
# bash <(curl -L -N -s {{scheme}}://{{host}}:{{port}}/cli/install.sh)
#
# Optional: specify the install directory: ?installDirectory=mydirectory

# Prep the install dir
mkdir -p {{installDirectory}}

# Create the bash wrapper for the CLI
cat > {{installDirectory}}/mojito-tmp << 'EOF'
#!/usr/bin/env bash
{{#hasHeaders}}
{{#headers}}
if [ -z "{{{envVarPresenceCheck}}}" ]; then
  echo "Environment variable {{envVar}} must be set before running this command."
fi
{{/headers}}
{{#authenticationMode}}
export L10N_RESTTEMPLATE_AUTHENTICATION_MODE={{authenticationMode}}
{{/authenticationMode}}
{{/hasHeaders}}
java -Dl10n.resttemplate.host={{host}} \
     -Dl10n.resttemplate.scheme={{scheme}} \
     -Dl10n.resttemplate.port={{port}} \
     -Dlogging.file.path={{installDirectory}} \
     -jar {{installDirectory}}/mojito-cli.jar "$@" ;
EOF

_ESC="$(printf '%s' "${PWD}" | sed 's/[\/&]/\\&/g')"
sed "s|\${PWD}|$_ESC|g" {{installDirectory}}/mojito-tmp > {{installDirectory}}/mojito
rm {{installDirectory}}/mojito-tmp

# Make the wrapper executable
chmod +x {{installDirectory}}/mojito

# Export the PATH to have access to the bash wrapper once installation is done
export PATH={{installDirectory}}:${PATH}
{{#hasHeaders}}
# Ensure Cloudflare Zero Trust headers are available for authenticated downloads
{{#headers}}
if [ -z "{{{envVarPresenceCheck}}}" ]; then
  echo "Environment variable {{envVar}} must be set before running this installation script."
  exit 1
fi
{{/headers}}

{{#authenticationMode}}
export L10N_RESTTEMPLATE_AUTHENTICATION_MODE={{authenticationMode}}
{{/authenticationMode}}

CURL_HEADERS=(
{{#headers}}
  -H "{{name}}: ${{{envVar}}}"
{{/headers}}
)
{{/hasHeaders}}

# Download/Upgrade the jar file if needed to match server version.
if ! mojito --check-server-version 2>/dev/null; then
  (
    # Keep the installed JAR intact until its replacement has downloaded and runs.
    jar_download=$(mktemp "{{installDirectory}}/mojito-cli.jar.XXXXXX") || exit 1
    trap 'rm -f "$jar_download"' EXIT

    # Randomize the start, then let curl back off and honor Retry-After. At most
    # 182 seconds: 2 seconds of jitter + 150 retry seconds + a final 30-second try.
    sleep "$((RANDOM % 3))"
    if curl --fail --location --show-error --no-progress-meter \
      --connect-timeout 10 --max-time 30 \
      --retry 8 --retry-max-time 150 --retry-connrefused{{#hasHeaders}} "${CURL_HEADERS[@]}"{{/hasHeaders}} \
      --output "$jar_download" "{{scheme}}://{{host}}:{{port}}/cli/mojito-cli.jar?v={{cliFileCacheKey}}"; then
      if ! java -jar "$jar_download" --version >/dev/null; then
        echo "Downloaded Mojito CLI JAR is invalid; keeping the existing installation." >&2
        exit 1
      fi
      chmod 644 "$jar_download" && mv -f "$jar_download" "{{installDirectory}}/mojito-cli.jar"
    else
      download_status=$?
      echo "Mojito CLI download failed; keeping the existing installation." >&2
      exit "$download_status"
    fi
  )
fi
