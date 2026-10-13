"""
노션 캘린더(Android, com.cron.calendar) Google Play 리뷰 전수 수집 + 불편 유형 집계

설치:
    pip install google-play-scraper pandas

실행 (맥북 터미널):
    python notion_calendar_reviews.py                     # 12개 언어 전체 수집 + 집계
    python notion_calendar_reviews.py --locales ko,en     # 일부 언어만
    python notion_calendar_reviews.py --skip-fetch        # 저장된 CSV로 집계만 다시
    python notion_calendar_reviews.py --skip-fetch --since 2026-01-01   # 기간 한정 집계

결과 (out/ 폴더):
    reviews_raw.csv          수집 원본 (reviewId 기준 중복 제거)
    reviews_tagged.csv       유형별 True/False 열이 붙은 표
    summary.md               평점 분포, 유형별 건수, 월별 추이, 유형별 '도움됨' 상위 리뷰
    untagged_low_star.jsonl  어떤 유형에도 안 걸린 1~2점 리뷰 (LLM/수작업 분류용)

주의:
    - 유형 분류는 키워드 규칙이라 오분류가 있다. summary.md의 상위 리뷰를 읽어 검수할 것.
    - Google Play 비공식 수집 라이브러리다. Play 페이지 구조가 바뀌면 동작이 멈출 수 있다.
    - 요청 사이에 쉬는 시간을 둔다(--sleep). 줄이지 말 것.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

import pandas as pd

APP_ID = "com.cron.calendar"

# Play 설명에 적힌 노션 캘린더 지원 언어 12개
LOCALES = [
    ("ko", "kr"), ("en", "us"), ("ja", "jp"), ("es", "es"), ("de", "de"), ("pt", "br"),
    ("fr", "fr"), ("nl", "nl"), ("sv", "se"), ("fi", "fi"), ("da", "dk"), ("no", "no"),
]

# 유형별 키워드(정규식, 대소문자 무시). PRD §2의 분류와 같은 이름을 쓴다.
CATEGORIES: dict[str, list[str]] = {
    "실행 불가·크래시·멈춤": [
        r"튕", r"꺼지|꺼짐|꺼져", r"강제\s*종료", r"안\s*열|열리지|실행\s*(이\s*)?안", r"멈춤|멈춰|먹통",
        r"검은\s*화면|검정\s*화면|로고에서", r"크래시",
        r"crash", r"won'?t (open|start|launch|load)", r"does(n'?t| not) (open|start|launch|work)",
        r"black screen", r"freez", r"stuck on (the )?logo", r"force clos", r"keeps closing",
        r"落ち|開かない|起動しない|フリーズ|固ま",
        r"no (abre|inicia)|se cierra|pantalla negra|tela preta|não abre|fecha sozinho|trava",
        r"stürzt|abstürz|öffnet nicht|startet nicht|schwarzer bildschirm",
        r"plante|ne s'ouvre|écran noir|ne démarre",
        r"crasht|opent niet|kraschar|startar inte|kaatuu|musta näyttö",
    ],
    "로그인 풀림·로그인 루프": [
        r"로그인|로그아웃|재로그인|인증",
        r"log ?in|log ?out|logged out|sign ?in|signed out|redirect",
        r"ログイン|ログアウト",
        r"iniciar sesión|cerrar sesión|sesión|fazer login",
        r"anmeld|abgemeldet|einlogg",
        r"connexion|déconnect|se connecter",
        r"inloggen|logga in|kirjaudu",
    ],
    "월·주 보기 없음·빈약": [
        r"월\s*간?\s*보기|월간|한\s*달|주\s*간?\s*보기|주간|3일",
        r"month(ly)? view|week(ly)? view|monthly|3[- ]day|three[- ]day",
        r"月表示|週表示|月間|週間|3日",
        r"vista (mensual|semanal)|visualização (mensal|semanal)|mensal|semanal|mensual",
        r"monatsansicht|wochenansicht",
        r"vue (mensuelle|hebdomadaire)",
        r"maandweergave|weekweergave|månadsvy|veckovy|kuukausinäkymä|viikkonäkymä",
    ],
    "위젯": [
        r"위젯", r"widget", r"ウィジェット",
    ],
    "노션 DB·할 일 연동 한계": [
        r"데이터베이스|디비|\bDB\b|노션\s*(페이지|항목|일정|할\s*일)|속성",
        r"database|notion (task|page|item|db)|propert",
        r"データベース",
        r"base de datos|banco de dados",
        r"datenbank", r"base de données", r"tietokanta",
    ],
    "배율·색상 설정 부족": [
        r"배율|확대|축소|글씨|글자\s*크기|폰트|색상|색깔|테마",
        r"zoom|font|text size|colou?r|theme|customi[sz]",
        r"拡大|文字サイズ|色",
        r"tamaño|cores?\b|colore?s?\b", r"schrift|farbe", r"police|couleur", r"kleur|färg|väri",
    ],
    "알림 제약": [
        r"알림|리마인더|알람",
        r"notif|remind|alert|alarm",
        r"通知|リマインダー",
        r"notificaci|recordatorio|notificaç|lembrete", r"benachrichtig|erinnerung", r"rappel",
        r"melding|påminnelse|ilmoitus|muistutus",
    ],
    "로딩·위젯 지연": [
        r"느리|느림|느려|로딩|버벅|렉|지연",
        r"slow|lag|loading|takes (forever|long)|sluggish",
        r"遅い|重い|読み込み",
        r"lent[ao]|demora|carregamento", r"langsam|ladezeit", r"chargement",
        r"traag|långsam|hidas",
    ],
    "동기화 실패·일정 소실": [
        r"동기화|사라지|사라졌|없어지|지워져|삭제돼|반영\s*안|중복",
        r"sync|disappear|missing|duplicate|deleted|not show(ing)?|do(es)?n'?t show",
        r"同期|消え|削除され",
        r"sincroniz|desaparec|duplica|borrad|apagad",
        r"synchron|verschwind|doppelt|gelöscht", r"synchro|dispar|doublon|supprimé",
        r"synkron|försvinn|katoa",
    ],
    "입력·터치 오류": [
        r"입력|시간\s*설정|날짜\s*설정|추가(가|를)?\s*(할\s*수\s*)?없|편집이?\s*안",
        r"can'?t (add|create|edit)|time picker|enter (the )?time|tap(ping)? (does|did)n'?t",
        r"入力|追加できない",
        r"no (puedo|consigo) (añadir|crear|editar)|não consigo (adicionar|criar|editar)",
    ],
    "오프라인": [
        r"오프라인|인터넷\s*(이\s*)?없|비행기\s*모드",
        r"offline|no internet|airplane mode",
        r"オフライン", r"sin conexión|sem internet|hors ligne|ohne internet",
    ],
}

COMPILED = {cat: re.compile("|".join(f"(?:{p})" for p in pats), re.IGNORECASE) for cat, pats in CATEGORIES.items()}


def fetch_locale(lang: str, country: str, sleep_s: float, cap: int) -> list[dict]:
    """한 언어/국가 조합의 리뷰를 최신순으로 끝까지(또는 cap까지) 가져온다."""
    from google_play_scraper import Sort, reviews

    rows: list[dict] = []
    token = None
    while len(rows) < cap:
        try:
            if token is None:
                batch, token = reviews(APP_ID, lang=lang, country=country, sort=Sort.NEWEST, count=200)
            else:
                batch, token = reviews(APP_ID, continuation_token=token)
        except Exception as exc:  # 네트워크·파싱 오류는 이 언어만 중단하고 계속 진행
            print(f"  [{lang}-{country}] 중단: {type(exc).__name__}: {exc}", file=sys.stderr)
            break
        if not batch:
            break
        for r in batch:
            r["lang"], r["country"] = lang, country
        rows.extend(batch)
        print(f"  [{lang}-{country}] 누적 {len(rows)}건")
        if token is None or getattr(token, "token", None) is None:
            break
        time.sleep(sleep_s)
    return rows


def fetch_all(locales: list[tuple[str, str]], out_dir: Path, sleep_s: float, cap: int) -> pd.DataFrame:
    raw_path = out_dir / "reviews_raw.csv"
    frames = [pd.read_csv(raw_path)] if raw_path.exists() else []
    for lang, country in locales:
        print(f"수집: {lang}-{country}")
        rows = fetch_locale(lang, country, sleep_s, cap)
        if rows:
            frames.append(pd.DataFrame(rows))
        # 언어 하나 끝날 때마다 저장해 중간에 끊겨도 결과가 남게 한다
        merged = pd.concat(frames, ignore_index=True).drop_duplicates("reviewId", keep="last")
        merged.to_csv(raw_path, index=False)
        time.sleep(sleep_s)
    return pd.read_csv(raw_path)


def tag(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    text = df["content"].fillna("").astype(str)
    for cat, rx in COMPILED.items():
        df[cat] = text.map(lambda s, rx=rx: bool(rx.search(s)))
    df["유형 없음"] = ~df[list(COMPILED)].any(axis=1)
    return df


def md_table(frame: pd.DataFrame) -> str:
    cols = list(frame.columns)
    lines = ["| " + " | ".join(map(str, cols)) + " |", "| " + " | ".join("---" for _ in cols) + " |"]
    for row in frame.itertuples(index=False):  # itertuples는 열마다 자료형을 유지한다(정수가 5.0으로 안 바뀜)
        lines.append("| " + " | ".join(str(v).replace("|", "/").replace("\n", " ") for v in row) + " |")
    return "\n".join(lines)


def summarize(df: pd.DataFrame, out_dir: Path) -> None:
    cats = list(COMPILED)
    n = len(df)
    low = df[df["score"] <= 2]
    lines = [
        "# 노션 캘린더 Android 리뷰 집계",
        "",
        f"- 리뷰 수: {n:,}건 (1~2점 {len(low):,}건)",
        f"- 작성 기간: {df['at'].min():%Y-%m-%d} ~ {df['at'].max():%Y-%m-%d}",
        "- 분류: 키워드 규칙(한 리뷰가 여러 유형에 해당 가능). 상위 리뷰를 읽어 검수할 것.",
        "",
        "## 평점 분포",
        "",
    ]
    dist = df["score"].value_counts().reindex([5, 4, 3, 2, 1], fill_value=0)
    lines.append(md_table(pd.DataFrame({"별점": dist.index, "건수": dist.values,
                                        "비율": (dist.values / max(n, 1) * 100).round(1)})))

    lines += ["", "## 유형별 건수", ""]
    rows = []
    for cat in cats + ["유형 없음"]:
        k_all, k_low = int(df[cat].sum()), int(low[cat].sum())
        rows.append({"유형": cat, "전체 건수": k_all, "전체 대비 %": round(k_all / max(n, 1) * 100, 1),
                     "1~2점 건수": k_low, "1~2점 대비 %": round(k_low / max(len(low), 1) * 100, 1)})
    lines.append(md_table(pd.DataFrame(rows).sort_values("1~2점 건수", ascending=False)))

    lines += ["", "## 월별 1~2점 리뷰와 주요 유형", ""]
    monthly = low.assign(월=low["at"].dt.strftime("%Y-%m")).groupby("월")
    mrows = []
    for month, g in monthly:
        mrows.append({"월": month, "1~2점": len(g), "실행 불가": int(g["실행 불가·크래시·멈춤"].sum()),
                      "로그인": int(g["로그인 풀림·로그인 루프"].sum()), "월·주 보기": int(g["월·주 보기 없음·빈약"].sum())})
    if mrows:
        lines.append(md_table(pd.DataFrame(mrows).tail(24)))

    lines += ["", "## 유형별 '도움됨' 상위 리뷰 5건", ""]
    for cat in cats:
        top = df[df[cat]].sort_values("thumbsUpCount", ascending=False).head(5)
        if top.empty:
            continue
        lines += [f"### {cat}", ""]
        for _, r in top.iterrows():
            body = re.sub(r"\s+", " ", str(r["content"]))[:200]
            lines.append(f"- ({r['score']}점, {r['at']:%Y-%m-%d}, 도움됨 {r['thumbsUpCount']}, {r['lang']}) {body}")
        lines.append("")

    (out_dir / "summary.md").write_text("\n".join(lines), encoding="utf-8")

    with (out_dir / "untagged_low_star.jsonl").open("w", encoding="utf-8") as f:
        for _, r in low[low["유형 없음"]].iterrows():
            f.write(json.dumps({"reviewId": r["reviewId"], "score": int(r["score"]), "at": f"{r['at']:%Y-%m-%d}",
                                "lang": r["lang"], "content": r["content"]}, ensure_ascii=False) + "\n")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="out", help="결과 폴더 (기본 out)")
    ap.add_argument("--locales", default="", help="예: ko,en  (비우면 12개 언어 전체)")
    ap.add_argument("--skip-fetch", action="store_true", help="수집 없이 저장된 reviews_raw.csv로 집계")
    ap.add_argument("--since", default="", help="집계 시작일 YYYY-MM-DD (수집에는 영향 없음)")
    ap.add_argument("--sleep", type=float, default=1.0, help="요청 사이 대기(초)")
    ap.add_argument("--cap", type=int, default=30000, help="언어별 최대 수집 건수")
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.skip_fetch:
        raw_path = out_dir / "reviews_raw.csv"
        if not raw_path.exists():
            sys.exit(f"{raw_path} 가 없습니다. 먼저 --skip-fetch 없이 실행하세요.")
        df = pd.read_csv(raw_path)
    else:
        wanted = {s.strip() for s in args.locales.split(",") if s.strip()}
        locales = [lc for lc in LOCALES if not wanted or lc[0] in wanted]
        df = fetch_all(locales, out_dir, args.sleep, args.cap)

    df["at"] = pd.to_datetime(df["at"], errors="coerce")
    df["score"] = pd.to_numeric(df["score"], errors="coerce")
    df["thumbsUpCount"] = pd.to_numeric(df["thumbsUpCount"], errors="coerce").fillna(0).astype(int)
    df = df.dropna(subset=["at", "score"])
    df["score"] = df["score"].astype(int)
    if args.since:
        df = df[df["at"] >= pd.Timestamp(args.since)]
    if df.empty:
        sys.exit("집계할 리뷰가 없습니다.")

    tagged = tag(df)
    tagged.to_csv(out_dir / "reviews_tagged.csv", index=False)
    summarize(tagged, out_dir)
    print(f"완료: {len(tagged):,}건 → {out_dir / 'summary.md'}")


if __name__ == "__main__":
    main()
