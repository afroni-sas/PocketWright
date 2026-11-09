# Aides pour piloter l'écran d'un téléphone en adb et le capturer : `. device-ui.sh`, puis shot / tap_ui.
A="${ADB:-$HOME/Android/Sdk/platform-tools/adb} -s ${ANDROID_SERIAL:?ANDROID_SERIAL manquant (adb devices)}"
O="$(dirname "${BASH_SOURCE[0]}")/out/ui"
mkdir -p "$O"
shot() { $A exec-out screencap -p > "$O/$1.png"; }
# tap_ui <texte> : touche le premier élément dont le texte ou la description correspond exactement.
tap_ui() {
  $A shell uiautomator dump /sdcard/pw-ui.xml >/dev/null 2>&1
  python3 -I - "$1" "$($A shell cat /sdcard/pw-ui.xml)" <<'PY' | { read x y && [ -n "$x" ] && $A shell input tap $x $y || echo "NON TROUVE: $1"; }
import re,sys
pat,xml=sys.argv[1],sys.argv[2]
# Le dernier trouvé : l'interface de l'app vient après le contenu de la page, qui peut porter les mêmes mots.
found=None
for n in re.findall(r'<node [^>]*>',xml):
    t=re.search(r' text="([^"]*)"',n).group(1); d=re.search(r'content-desc="([^"]*)"',n).group(1)
    if pat==t or pat==d: found=n
if found:
    b=list(map(int,re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',found).groups()))
    print((b[0]+b[2])//2,(b[1]+b[3])//2)
PY
}

# in_app : vrai si l'app est au premier plan (à vérifier avant de taper au clavier).
in_app() { $A shell dumpsys window | grep -q "mCurrentFocus=.*com.afroni.pocketwright"; }
# wait_ui <texte> [secondes] : attend qu'un élément apparaisse à l'écran.
wait_ui() {
  local end=$((SECONDS + ${2:-8}))
  while [ $SECONDS -lt $end ]; do
    $A shell uiautomator dump /sdcard/pw-ui.xml >/dev/null 2>&1
    $A shell cat /sdcard/pw-ui.xml | grep -qF "\"$1\"" && return 0
    sleep 0.4
  done
  echo "ABSENT: $1"; return 1
}
