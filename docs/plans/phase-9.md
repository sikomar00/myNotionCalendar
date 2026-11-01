# Phase 9 — 노션 보기 위젯 (VW-01~07)

승인된 계획(2026-10-21). 기준 요구사항은 `docs/PRD.md` §5.12.

## 결정 기록 (ADR)
**보기 위젯은 `events`가 아니라 표시용 스냅샷 테이블 `view_snapshots`에 저장한다.**
- 배경: 사용자가 "어떤 DB든 노션의 보기대로 위젯에 띄우는" 기능을 요구했다. 전면 범용 앱 전환·별도 앱도 검토했다.
- 선택: 기존 앱은 유지하고 범용 층을 옆에 추가한다(사용자 결정 2026-10-21). 전환은 종류 6개(CALENDAR·HABIT·HABIT_LOG·LEDGER·BUDGET·CATEGORY)마다 매퍼·DAO·위젯이 따로 있어 약 4,000줄 교체이고, 그동안 쓰는 기능이 불안정해진다.
- 이유: `events`는 `startDate`가 필수인 날짜 모델이라(`Entities.kt:83`) 날짜 없는 DB를 못 담는다. 읽기 전용이므로 값을 표시용 글자로 바꿔 저장하면 속성 타입마다 모델이 필요 없다.
- 결과: 위젯에서 앱이 정렬·필터를 다시 할 수 없다(보기 설정을 노션이 적용). 쓰기는 이번 범위 밖(VW-08).
- 되돌리는 법: 테이블·위젯·설정 항목만 지우면 되고 기존 kind 코드는 건드리지 않는다.

## 범위 밖
갤러리 커버 이미지, 보드 열 배치·타임라인 막대, 노션으로 쓰기, 대시보드·폼·차트·지도 보기, 배포·약관, 기존 kind·화면 변경, 새 라이브러리.

## 단계 (각 단계 끝: `gradlew.bat test lint assembleDebug`, 실기기 설치, adb 스크린샷, StrictMode 0줄, 문서 승인)
- **9-A 확인·문서:** PRD §5.12·이 문서 승인 → API 모양 확인(spike): 디버그 전용 진단 메뉴(`BuildConfig.DEBUG`)에서 실제 DB로 `GET /views?database_id=`·`GET /views/{id}`·`POST /views/{id}/queries`를 불러, 응답을 제목·값을 가린 JSON으로 저장해 시험 픽스처로 쓴다. 토큰은 코드·로그·픽스처에 넣지 않는다(`.env`/앱 설정에 사용자가 직접).
- **9-B 읽기 층:** `NotionApi` 엔드포인트 3개, View·ViewQuery DTO, `ViewReader`, `PropertyDisplay`(순수 함수), Room `view_snapshots`(DB v12), `ViewSync`(`PeriodicSync` 재사용). 시험: `PropertyDisplay` 타입별 표, `ViewReader` 페이지 반복·만료 재시도, `ViewSync` 교체·실패 시 유지, `FakeNotionStore`에 views 응답 추가.
- **9-C 화면·위젯:** 설정 '보기 추가', `ViewWidget`(+ receiver·위젯 정보 xml·매니페스트·`WidgetKinds` 한 줄), `WidgetConfigEntity.viewId`(DB v12 + 마이그레이션 + `schemas/12.json` + 마이그레이션 시험, PF-09).
- **9-D QA:** `rules/qa.md` — Claude QA(VW-xx별 기대값·실제값·Pass/Fail) + 사람 QA 목록(내 DB 3종 이상에서 보기와 위젯 비교, 홈 화면 체감, 오래됨 표시).

## 모듈 규칙
`:widget`은 `:core:data`·`:core:designsystem`만 의존(`:core:notion` 금지). 화면·위젯은 Room만 읽는다. 네트워크는 `:core:sync`의 `ViewSync`에서만. 파일 500줄·함수 30줄 이하.

## 위험
- 보기 설정은 `GET /views/{id}`(9-A에서 실기기로 확인). 값은 데이터 소스 쿼리로 받으므로 보기 쿼리의 15분 캐시·추가 필터 불가 제약은 해당 없다.
- 빠른 필터(`quick_filters`)가 보기 필터와 별개라 합쳐 넣어야 한다. 합친 결과가 보기 쿼리와 어긋나는 종류(board 1건)는 '목록으로 표시' 한계로 둔다.
- 필터에 쓰는 속성이 지워졌거나 타입이 바뀌면 데이터 소스 쿼리가 400(Validation)일 수 있다 → 그 보기는 '보기를 읽지 못함'으로 표시하고 이전 스냅샷을 유지한다.
- 큰 DB 보기는 노션 쪽 쿼리가 느릴 수 있다(9-A: 보기 쿼리 포함 9회 3.9초).

## 9-A 결과 (2026-10-21, 실기기 R3KYC03RGCD, 등록 DB 3개·보기 10개)
- **호출:** `GET /v1/views?data_source_id=`는 `{object, id}`만 주고(보기 이름·종류는 `GET /v1/views/{id}`로), 보기 설정은 `type`·`filter`·`sorts`·`quick_filters`·`configuration.properties[]`(`property_id`·`property_name`·`visible`·`width`)를 준다. 9회 호출(DB 3개 × 목록·보기·쿼리) 3.9초.
- **중요 — 보기 쿼리는 값을 주지 않는다.** `POST /views/{id}/queries`의 `results`는 `{object:"page", id}`뿐이다(문서도 동일). 값을 받으려면 행마다 `GET /pages/{id}`(50행이면 50요청)이거나 데이터 소스 쿼리가 필요하다.
- **결정 변경:** 값은 데이터 소스 쿼리에 보기의 `filter`와 `quick_filters`(`{속성 id: {타입: 조건}}`, 속성 필터 조건과 같은 모양이라 `{property, 타입: 조건}`으로 바꿔)를 `and`로 묶고 `sorts`를 그대로 넣어 한 번에 받는다. 보기 쿼리와 id·순서를 비교: **10개 중 9개 완전 일치**(table 3·list 1·calendar 3·gallery 1·timeline 1). `quick_filters`를 합치기 전에는 4개가 달랐다(빠른 필터가 `filter`에 없음).
- **불일치 1개:** board(빠른 필터 `relation is_empty`)는 보기 쿼리가 0행, 데이터 소스 쿼리가 100행. 같은 빠른 필터의 calendar는 일치했으므로 board 보기 쿼리 쪽 동작으로 보이지만 원인은 확인 못 했다. board는 어차피 '목록으로 표시'(VW-05)라 한계로만 적는다.
- **GET /views/{id}가 `Validation`(400)인 보기 2개:** 종류를 알 수 없다(목록 응답에 종류가 없음). 조회 실패는 '지원하지 않는 보기'로 건너뛴다(VW-05·06).
- **열 이름·순서:** `configuration.properties[]`가 `property_name`·`visible`을 직접 주므로 `dataSource(id)` 스키마 호출은 필요 없다(계획 변경).
- **표시 타입:** 시험 DB 행에서 본 속성 타입은 date·files·formula·last_edited_time·multi_select·number·relation·status·title·url(PropertyDisplay 시험 대상).
- **검증 방법:** 구조 파일 `core/notion/src/test/resources/fixtures/views_probe_shape.json`(값은 `"S"`로 가림, 토큰·내용 없음). 디버그 메뉴 '보기 API 시험(VW)'은 9-B에서 `ViewReader`로 대체하면서 지운다.
