#!/bin/bash
# Bring Vistaks TV to the front on the SHIELD and print its WebView DevTools websocket URL
# (also written to $VISTAKS_TV_WS_FILE, default: the temp folder). Git Bash on Windows.
# The SHIELD's network debugging must be on and this PC authorised once (accept the prompt on the TV).
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
DEV="${VISTAKS_TV_ADB:?set VISTAKS_TV_ADB to <tv-ip>:5555}"
OUT="${VISTAKS_TV_WS_FILE:-${TEMP:-/tmp}/vistaks-tv-ws.txt}"
"$ADB" connect "$DEV" >/dev/null 2>&1
MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" shell input keyevent WAKEUP
for i in 1 2 3; do
  top=$(MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" shell dumpsys activity activities | grep -a "mResumedActivity" | head -1)
  case "$top" in *com.vistaks.tv*) break;; esac
  MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" shell am start -W -n com.vistaks.tv/.MainActivity >/dev/null 2>&1; sleep 6
done
sock=$(MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" shell "cat /proc/net/unix | grep -a -o 'webview_devtools_remote_[0-9]*'" | sort -u | tail -1)
"$ADB" -s "$DEV" forward --remove-all; "$ADB" -s "$DEV" forward tcp:9333 "localabstract:$sock" >/dev/null
WS=$(curl -s http://127.0.0.1:9333/json | grep -o 'ws://[^"]*' | head -1); echo "$WS" > "$OUT"
echo "top: ${top:0:90}"
echo "ws: $WS"
