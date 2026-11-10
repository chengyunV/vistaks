#!/bin/bash
# The composition layers on the SHIELD, one line each with its buffer size and format.
# A WebView-only page shows one "com.vistaks.tv/...MainActivity" layer at 1920x1080 RGBA_8888, even in
# fullscreen video (2026-11-09). True 4K needs a separate "SurfaceView - com.vistaks.tv/..." layer at
# 3840x2160, like the official YouTube app's (ADR-0100).
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
DEV="${VISTAKS_TV_ADB:?set VISTAKS_TV_ADB to <tv-ip>:5555}"
MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" shell dumpsys SurfaceFlinger | grep -a -A1 -E "^\+? ?Buffer(Queue|State)Layer \(" | grep -a -E "Layer \(|activeBuffer" | sed -E 's/, tr=.*//'
