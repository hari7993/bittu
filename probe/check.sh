#!/usr/bin/env bash
# Bittu feature check. Runs each of Bittu's real actions on the connected phone, silently, and reads the
# phone's own state back to see whether it really happened. Every setting it touches is put back.
# Needs: a test build of Bittu open on the phone, the helper running, USB debugging.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
D=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')
[ -z "$D" ] && { echo "No phone connected."; exit 1; }
cd "$(dirname "$0")"
sh() { "$ADB" -s "$D" shell "$1" 2>/dev/null | tr -d '\r'; }
PID=$(sh "pidof app.bittu")
[ -z "$PID" ] && { sh "am start -n app.bittu/.MainActivity >/dev/null 2>&1; sleep 4"; PID=$(sh "pidof app.bittu"); }
"$ADB" -s "$D" forward tcp:9222 localabstract:webview_devtools_remote_$PID >/dev/null
tool() { node cdp.mjs "return BittuNative.testTool('$1', JSON.stringify($2))" 2>&1 | sed 's/^"//; s/"$//'; }
pass=0; fail=0
row() { if [ "$2" = "ok" ]; then pass=$((pass+1)); printf "  PASS  %-34s %s\n" "$1" "$3"; else fail=$((fail+1)); printf "  FAIL  %-34s %s\n" "$1" "$3"; fi; }

echo "Phone: $(sh 'getprop ro.product.marketname'), helper pid [$(sh 'pidof bittu_helper')]"
ST=$(node cdp.mjs "return JSON.parse(BittuNative.status())" 2>&1)
for k in helper paired access notifAccess mic contacts phone sms; do echo "$ST" | grep -q "\"$k\":true" && row "setup: $k" ok "on" || row "setup: $k" no "OFF"; done

echo "--- thinking (your Anthropic key)"
R=$(node cdp.mjs "return BittuNative.testThink('Self-test. Reply with exactly one word: working')" 2>&1)
echo "$R" | grep -qi "working" && row "brain answers" ok "$R" || row "brain answers" no "$R"
R=$(node cdp.mjs "return BittuNative.testThink('Self-test: turn the torch on, then turn it off again. Then reply with exactly: torch tested')" 2>&1)
echo "$R" | grep -qi "torch tested" && row "brain uses a tool (torch on, off)" ok "$R" || row "brain uses a tool (torch on, off)" no "$R"

echo "--- switches (each is put back)"
B0=$(sh "settings get global bluetooth_on"); T=$([ "$B0" = "1" ] && echo false || echo true)
R=$(tool phone_switch "{name:'bluetooth',on:$T}"); sleep 2; B1=$(sh "settings get global bluetooth_on")
[ "$B1" != "$B0" ] && row "Bluetooth flips" ok "$B0 -> $B1  ($R)" || row "Bluetooth flips" no "stayed $B1  ($R)"
tool phone_switch "{name:'bluetooth',on:$([ "$B0" = "1" ] && echo true || echo false)}" >/dev/null; sleep 2
[ "$(sh 'settings get global bluetooth_on')" = "$B0" ] && row "Bluetooth put back" ok "$B0" || row "Bluetooth put back" no "now $(sh 'settings get global bluetooth_on')"
Z0=$(sh "settings get global zen_mode"); R=$(tool phone_switch "{name:'silent',on:true}"); sleep 1; Z1=$(sh "settings get global zen_mode")
[ "$Z1" != "0" ] && row "Silent mode on" ok "zen $Z0 -> $Z1" || row "Silent mode on" no "$R"
tool phone_switch "{name:'silent',on:$([ "$Z0" = "0" ] && echo false || echo true)}" >/dev/null; sleep 1
[ "$(sh 'settings get global zen_mode')" = "$Z0" ] && row "Silent mode put back" ok "$Z0" || row "Silent mode put back" no "now $(sh 'settings get global zen_mode')"
R=$(tool phone_switch "{name:'torch',on:true}"); tool phone_switch "{name:'torch',on:false}" >/dev/null
echo "$R" | grep -q "torch is on" && row "Torch" ok "$R" || row "Torch" no "$R"

echo "--- levels and developer options (each is put back)"
V0=$(sh "settings get system volume_music_speaker"); R=$(tool set_level "{what:'volume',percent:40}"); sleep 1; V1=$(sh "settings get system volume_music_speaker")
[ -n "$V1" ] && [ "$V1" != "$V0" ] && row "Volume changes" ok "$V0 -> $V1  ($R)" || row "Volume changes" no "$V0 -> $V1  ($R)"
[ -n "$V0" ] && node cdp.mjs "return BittuNative.testTool('set_level', JSON.stringify({what:'volume',percent:Math.round($V0 * 100 / 150)}))" >/dev/null 2>&1; sleep 1; V2=$(sh "settings get system volume_music_speaker"); [ "$V2" = "$V0" ] && row "Volume put back" ok "$V2" || row "Volume put back" no "now $V2, was $V0"
BM=$(sh "settings get system screen_brightness_mode"); BR=$(sh "settings get system screen_brightness"); R=$(tool set_level "{what:'brightness',percent:35}"); sleep 1; BN=$(sh "settings get system screen_brightness")
[ "$BN" != "$BR" ] && row "Brightness changes" ok "$BR -> $BN" || row "Brightness changes" no "stayed $BN  ($R)"
sh "settings put system screen_brightness $BR; settings put system screen_brightness_mode $BM" >/dev/null
S0=$(sh "settings get system show_touches"); tool developer_option "{name:'show_taps',on:true}" >/dev/null; S1=$(sh "settings get system show_touches"); tool developer_option "{name:'show_taps',on:false}" >/dev/null
[ "$S1" = "1" ] && row "Developer option (show taps)" ok "$S0 -> 1 -> $(sh 'settings get system show_touches')" || row "Developer option (show taps)" no "got $S1"
sh "settings put system show_touches ${S0:-0}" >/dev/null

echo "--- call modes"
tool set_mode "{mode:'driving'}" >/dev/null; sleep 1; M=$(sh "cat /data/local/tmp/bittu/config.json" | grep -o '"mode":"[a-z]*"')
[ "$M" = '"mode":"driving"' ] && row "Mode reaches the call helper" ok "$M" || row "Mode reaches the call helper" no "helper has $M"
tool set_mode "{mode:'normal'}" >/dev/null; sleep 1
[ "$(sh 'cat /data/local/tmp/bittu/config.json' | grep -o '"mode":"[a-z]*"')" = '"mode":"normal"' ] && row "Mode put back to normal" ok "" || row "Mode put back to normal" no ""
sh "cat /data/local/tmp/bittu/config.json" | grep -q '"geminiKey":"[^"]' && row "Call brain has the Gemini key" ok "" || row "Call brain has the Gemini key" no "missing"

echo "--- apps and the screen"
R=$(tool open_app "{name:'Settings'}"); TOP=$(sh "dumpsys activity activities | grep -m1 topResumedActivity")
echo "$TOP" | grep -q "settings" && row "Open an app (Settings)" ok "$R" || row "Open an app (Settings)" no "$R"
N=$(node cdp.mjs "return BittuNative.testTool('read_screen', '{}').split(String.fromCharCode(10)).filter(Boolean).length" 2>&1)
[ "${N:-0}" -gt 3 ] 2>/dev/null && row "Read another app's screen" ok "$N items read" || row "Read another app's screen" no "$N"
R=$(tool press_button "{button:'home'}"); sleep 1; sh "dumpsys activity activities | grep -m1 topResumedActivity" | grep -q "home" && row "Press Home" ok "$R" || row "Press Home" no "$R"
R=$(tool open_app "{name:'An app that does not exist'}"); echo "$R" | grep -q "no app called" && row "Honest when an app is missing" ok "" || row "Honest when an app is missing" no "$R"

echo "--- people and notifications (nothing is sent, nothing is shown)"
R=$(tool call_person "{name:'zzqqxx nobody'}"); echo "$R" | grep -q "Nobody called" && row "Honest when a person is unknown" ok "" || row "Honest when a person is unknown" no "$R"
N=$(node cdp.mjs "const t = BittuNative.testTool('read_notifications', JSON.stringify({app:''})); return t.startsWith('#') ? t.split('\\n').length + ' notifications read' : t" 2>&1)
echo "$N" | grep -q -E "notifications read|no notifications" && row "Read notifications" ok "$N" || row "Read notifications" no "$N"

echo
echo "RESULT: $pass passed, $fail failed"
