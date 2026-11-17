#!/bin/sh
# Lance VTLO 303 dans le navigateur (serveur local sur le port 8303). Ctrl+C pour arrêter.
cd "$(dirname "$0")" || exit 1
URL=http://127.0.0.1:8303/
( sleep 1; xdg-open "$URL" >/dev/null 2>&1 || echo "Ouvrez $URL dans votre navigateur." ) &
exec python3 -m http.server 8303 --bind 127.0.0.1
