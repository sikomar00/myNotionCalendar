#!/usr/bin/env bash
# PF-02 오프라인 E2E (Maestro 대신 adb). S25를 USB로 연결하고 홈 화면에 월간 위젯을 둔 상태에서 실행한다.
#
#  1. 비행기 모드 + Wi-Fi 끄기(이 기기는 비행기 모드에서도 Wi-Fi가 남는다)
#  2. 위젯 '+'로 일정 추가 → 로컬 DB에 바로 보이고 아웃박스에 1건 쌓이는지
#  3. 연결 복구 → 아웃박스가 비고 임시 ID(local-)가 실제 page_id로 바뀌는지
#  4. 만든 일정을 앱에서 삭제(노션 휴지통)해 정리
#
# 사용: scripts/gate/offline_e2e.sh [패키지 이름]   (기본 com.ysdoing.notioncal.debug)
# 필요: Git Bash, python(표준 sqlite3), adb는 %LOCALAPPDATA%/Android/Sdk/platform-tools
set -euo pipefail
export MSYS_NO_PATHCONV=1

PKG="${1:-com.ysdoing.notioncal.debug}"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
# Windows용 adb·python은 /tmp 같은 MSYS 경로를 모른다. C:/... 형식으로 바꿔 모두 같은 경로를 쓴다.
WORK="$(mktemp -d)"
command -v cygpath >/dev/null && WORK="$(cygpath -m "$WORK")"
TITLE="$(date +%H%M%S)"          # 한글 입력기 때문에 숫자만 입력된다
UI="$WORK/ui.xml"
trap 'rm -rf "$WORK"' EXIT

dump() { "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null; "$ADB" pull /sdcard/ui.xml "$UI" >/dev/null 2>&1; }

# 정규식에 맞는 첫(또는 마지막) 노드의 가운데 좌표
center() {
  python - "$UI" "$1" "${2:-first}" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
nodes = [n for n in re.findall(r"<node [^>]*>", xml) if re.search(sys.argv[2], n)]
if not nodes:
    sys.exit(1)
node = nodes[0] if sys.argv[3] == "first" else nodes[-1]
x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r'bounds="([^"]+)"', node).group(1)))
print((x1 + x2) // 2, (y1 + y2) // 2)
PY
}

tap() { local p; p="$(center "$@")" || { echo "화면에서 찾지 못함: $1" >&2; exit 1; }; "$ADB" shell input tap $p; }

# 로컬 DB 조회: 결과는 "pageId|pendingOps|outbox건수"
query() {
  for f in notioncal.db notioncal.db-wal notioncal.db-shm; do
    "$ADB" exec-out run-as "$PKG" cat "databases/$f" > "$WORK/$f" 2>/dev/null || true
  done
  {
  python - "$WORK/notioncal.db" "$TITLE" <<'PY'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
row = db.execute("SELECT pageId, pendingOps FROM events WHERE title = ?", (sys.argv[2],)).fetchone()
outbox = db.execute("SELECT COUNT(*) FROM outbox").fetchone()[0]
print(f"{row[0] if row else ''}|{row[1] if row else ''}|{outbox}")
PY
  } | tr -d '\r'   # Windows python은 줄 끝에 \r을 붙인다
}

network() {
  if [ "$1" = off ]; then
    "$ADB" shell cmd connectivity airplane-mode enable
    "$ADB" shell svc wifi disable
  else
    "$ADB" shell cmd connectivity airplane-mode disable
    "$ADB" shell svc wifi enable
  fi
}

restore() { network on; }
trap 'restore; rm -rf "$WORK"' EXIT

echo "[1] 네트워크 끄기"
network off
sleep 3

echo "[2] 위젯 + → '$TITLE' 추가"
"$ADB" shell input keyevent KEYCODE_HOME
sleep 1.5
dump
tap 'content-desc="일정 추가"'
sleep 1.5
"$ADB" shell input text "$TITLE"
"$ADB" shell input keyevent KEYCODE_ENTER
sleep 2
IFS='|' read -r PAGE PENDING OUTBOX < <(query)
echo "    로컬: pageId=$PAGE pendingOps=$PENDING outbox=$OUTBOX"
[[ "$PAGE" == local-* && "$PENDING" -ge 1 && "$OUTBOX" -ge 1 ]] || { echo "실패: 오프라인 추가가 로컬에 즉시 반영되지 않음"; exit 1; }

echo "[3] 네트워크 복구 → 아웃박스 비우기 대기(최대 90초)"
network on
for _ in $(seq 1 30); do
  sleep 3
  IFS='|' read -r PAGE PENDING OUTBOX < <(query)
  [[ "$OUTBOX" == 0 && "$PAGE" != local-* && -n "$PAGE" ]] && break
done
echo "    로컬: pageId=$PAGE pendingOps=$PENDING outbox=$OUTBOX"
[[ "$OUTBOX" == 0 && "$PAGE" != local-* ]] || { echo "실패: 연결 후 노션에 반영되지 않음"; exit 1; }

echo "[4] 정리: 앱에서 삭제(노션 휴지통)"
"$ADB" shell input keyevent KEYCODE_HOME
sleep 1.5
dump
TODAY_DESC="content-desc=\"$(date +%-m)월 $(date +%-d)일"
tap "$TODAY_DESC"
sleep 1.5
dump
tap "text=\"$TITLE\""
sleep 1.2
dump
tap 'text="삭제"'
sleep 0.8
dump
tap 'text="삭제"' last
sleep 4
IFS='|' read -r PAGE PENDING OUTBOX < <(query)
[[ -z "$PAGE" && "$OUTBOX" == 0 ]] || { echo "실패: 정리 삭제가 반영되지 않음(pageId=$PAGE outbox=$OUTBOX)"; exit 1; }

echo "통과: 오프라인 추가 → 즉시 표시 → 연결 후 노션 반영 → 정리 완료"
