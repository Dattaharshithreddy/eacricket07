#!/bin/sh
# Launches the app on the CI emulator and reports what happened as GitHub annotations
# (readable on the run page): crash trace, whether the process is alive, the setup screen's text.
set -u
PKG=com.harshith.cricket07
enc() { sed -e 's/%/%25/g' | awk 'BEGIN{ORS="%0A"} {print}'; }

adb install -r Cricket07.apk
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity
sleep 12
adb logcat -d -b crash > crash.txt
adb logcat -d AndroidRuntime:E Cricket07:V Cricket07Server:V '*:S' > app.txt
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell cat /sdcard/ui.xml > ui.xml 2>/dev/null
adb exec-out screencap -p > screen.png

if [ -s crash.txt ]; then
  echo "::error title=App crashed::$(head -80 crash.txt | enc)"
fi
if adb shell pidof $PKG >/dev/null; then alive=yes; else alive=no; fi
texts=$(grep -o 'text="[^"]*"' ui.xml | grep -v 'text=""' | head -20 | tr '\n' ' ')
echo "::notice title=Smoke test::process alive: $alive | screen: $texts"
echo "::notice title=App log::$(tail -40 app.txt | enc)"

# the loopback server answers inside the device
adb shell "echo -e 'GET / HTTP/1.0\r\n\r\n' | toybox nc 127.0.0.1 47107 | head -1" > server.txt 2>&1
echo "::notice title=Loopback server::$(cat server.txt | enc)"

[ "$alive" = yes ] && [ ! -s crash.txt ]
