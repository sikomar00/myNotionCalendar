# 노션 보기·속성 → 위젯 앱: 기술·개념 조사

작성 2026-10-30 · 기준 문서: 현재 앱(`docs/PRD.md`) · 대상: 새 안드로이드 앱(가칭 "노션 위젯 스튜디오")

표기: ✔ 공식 문서 발췌로 확인 · △ 정황·2차 출처, 구현 전에 실제 응답으로 확인 · ✖ 확인 못 함.
`developers.notion.com`이 이 조사 세션에서 직접 열리지 않아(네트워크 정책) 문서 발췌는 검색 결과로만 확인했다. 그래서 §7의 확인 목록을 Claude Code가 가장 먼저 처리한다.

## 0. 한 장 요약

앱 정의: 노션 데이터베이스의 **보기(표·보드·캘린더·타임라인·리스트·갤러리·차트)를 읽어 같은 모양의 앱 화면과 홈 화면 위젯으로 번역**하고, 속성 22종을 유형별로 보여주고 고칠 수 있게 한다. 데이터는 로컬 DB가 원본이고, 쓰기는 아웃박스를 거친다(현재 앱의 원칙 그대로).

설계를 바꾸는 발견 5가지

1. **노션 API에 Views API가 있다**(8개 엔드포인트, `2026-03-11`). 보기의 필터·정렬·그룹·표시 속성·유형별 설정을 읽을 수 있다. 그래서 "보기를 위젯으로 컴파일"하는 구조가 가능하다. ✔
2. **속성 22종 중 쓰기 가능은 13종, 읽기 전용은 8종, 장소 1종은 응답 모양을 확인하지 못했다.** 읽기 전용 8종은 수식·롤업·생성 일시·생성자·최종 편집 일시·최종 편집자·버튼·ID이다. ✔(장소 △)
3. **버튼 속성은 API로 누를 수 없고** 값도 `{}`만 온다. 위젯에서 버튼을 재현할 수 없다. 대신 앱 안의 "위젯 동작"(날짜 +1일, 체크, 숫자 +1)으로 같은 목적을 이룬다(§4.5). ✔
4. **위젯은 RemoteViews라 그리기 캔버스가 없고, 비트맵 메모리 상한이 있다.** 차트·갤러리·타임라인은 "비중(weight) 기반 구조 그리기"와 "예산이 있는 비트맵"으로 만든다(§4.2). ✔
5. **관계형·롤업·사람은 ID나 부분 데이터만 온다.** 제목·이름을 풀어 캐시하는 층이 따로 필요하고, 25개를 넘으면 속성 항목 엔드포인트로 이어받아야 한다. ✔

## 1. 개념과 용어

| 용어 | 뜻 |
| --- | --- |
| 데이터베이스 / 데이터 소스 | 2025-09-03부터 DB 하나가 데이터 소스 여러 개를 가진다. 조회·생성은 `data_source_id`, **보기는 DB에 속한다**. 보기는 `data_source_id`를 가지며 대시보드 보기는 없을 수 있다 ✔ |
| 보기(View) | 데이터 소스에 대한 저장된 필터·정렬·그룹·표시 속성·유형별 설정. 유형: table, board, list, calendar, timeline, gallery, form, chart, map, dashboard ✔ |
| 속성(Property) | 스키마(유형·옵션·서식)와 페이지별 값이 나뉜다. 매핑은 이름이 아니라 **속성 ID**로 저장한다(현재 앱 규칙) |
| 위젯 레시피 | 이 앱의 개념. 위젯 하나 = (데이터 소스, 보기 또는 직접 구성, 템플릿, 보여줄 속성과 역할, 상호작용 목록, 테마). 노션 보기에서 만들어지지만 위젯별로 속성을 취사선택·덮어쓴다 |
| 보기 구독 | 위젯·화면이 쓰는 보기만 동기화한다. 구독이 없는 보기는 받지 않는다 |
| 보기 스냅샷 | 보기 결과(순서·그룹)를 로컬에 구체화해 둔 표. 위젯은 이것만 읽는다 |
| 뷰 엔진 | 보기 설정 + 로컬 행 + 위젯 크기를 받아 화면 모델(UiModel)을 만드는 순수 Kotlin 계층 |

흐름: 노션(스키마·보기·페이지) → 동기화 → Room(원본 캐시) → 뷰 엔진(필터·정렬·그룹·집계) → UiModel → 앱 화면(Compose) / 위젯(Glance). 쓰기: 화면·위젯 → 로컬 낙관 반영 + 아웃박스 → 노션.

## 2. 노션 API 조사

### 2.1 버전·구조
- `Notion-Version: 2026-03-11`(최신, 문서 기준). Views API는 `2025-09-03` 이상이 필요하다 ✔
- 토큰: 개인용 내부 연결 토큰 하나(현재 앱과 같음). 공개(OAuth) 연동은 client secret 교환 때문에 서버가 필요하므로 비목표.
- 연결에 **기능(capability)**이 필요하다: 콘텐츠 읽기·업데이트·삽입, 그리고 사람 속성 이름·이메일을 읽으려면 **사용자 정보 읽기** ✔(보기 조회는 콘텐츠 읽기 ✔). 페이지를 연결에 공유하지 않으면 읽을 수 없다.
- 속도 제한: 평균 초당 3회(분당 180회, 현재 앱 규칙). 429는 `Retry-After`를 따른다.

### 2.2 Views API ✔
| 목적 | 호출 |
| --- | --- |
| 목록 | `GET /v1/views?database_id=…` 또는 `data_source_id=…` |
| 조회 | `GET /v1/views/{view_id}` — 필터, 표시 설정, 노션 딥링크 |
| 생성 | `POST /v1/views` — 대상 데이터 소스·이름·유형, 필터·정렬·`configuration` 선택 |
| 수정 | `PATCH /v1/views/{view_id}` — 이름·필터·정렬·설정(`null`이면 필터 해제) |
| 삭제 | `DELETE /v1/views/{view_id}` |
| 결과 질의 | `POST /v1/views/{view_id}/queries` → 첫 페이지 + `query_id`, 이어받기, 질의 삭제 |

- `filter`·`sorts`는 데이터 소스 질의와 **같은 모양**이다. 즉 기존 `QueryFilters` 모양을 재사용한다 ✔
- `configuration`은 `type`으로 나뉘는 합집합이다(board면 `"board"` 등). 보드의 `group_by`는 null일 수 있고 그룹 순서를 위한 `sort` 객체가 필요하다 ✔
- 질의 결과는 **15분 캐시, 최대 1만 행** ✔. 문서는 "보기 정렬 순서가 앱에서 그대로 재현된다고 보장하지 않으니 필요하면 `view.sorts`를 앱에서 다시 적용하라"고 적는다 ✔. 이어받기 중 필터 적용 관련 주의 문구가 있어 실제 동작을 확인해야 한다 △.
- 유형별 설정 JSON의 정확한 키는 공식 발췌로 확인하지 못했다. 한 SDK는 "API가 아직 늘어나는 중이라 보기 설정을 원본 JSON으로 노출한다"고 적었다 △. **설정은 `JsonObject`로 받고 알려진 키만 타입 접근자로 읽는다. 모르는 키는 무시하고, 모르는 보기 유형은 리스트 보기로 대체한다.**
- 참고로 노션 연결 도구의 보기 DSL이 가진 설정 항목(의미 단위로 API 키와 대응할 가능성이 높음 △): 필터, 빠른 필터, 정렬, 그룹(옵션/그룹 단위, 빈 그룹 표시), 하위 그룹(보드만), 카드 배치(default|compact), 캘린더 기준 속성(date·생성/최종 편집 일시·날짜 수식), 타임라인 시작·종료 속성, 표시/숨김 속성, 커버(속성·크기 small|medium|large·aspect cover|contain), 셀 줄바꿈, 고정 열, 차트(column|bar|line|donut|number, 집계, 색, 높이, 정렬, 쌓기 기준, 캡션), 폼, 지도.

### 2.3 속성 22종 능력표
읽기 모양은 페이지 속성 값 기준. 쓰기는 `PATCH /v1/pages` 기준.

| # | 속성 | 읽기 | 쓰기 | 위젯 셀 | 상호작용 | 로컬 정렬·필터·그룹 | 주의 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 텍스트(rich_text, 제목 title 포함) | 조각 배열(plain_text·서식·링크·멘션) | ✔ 조각당 2,000자 | 한 줄 말줄임 | 입력 시트 | 문자열 비교·포함 | 멘션 편집 시 글자로 바뀜(현재 앱 `titleHasMention` 처리 재사용) |
| 2 | 숫자 | number\|null, 서식은 스키마 | ✔ | 서식 적용(원·%·콤마) | −/+ 스텝퍼, 입력 시트 | 숫자 | `number_format` 값이 많음(스키마에서 읽음) |
| 3 | 선택 | {id,name,color} | ✔ 이름 또는 id | 색 칩 | 선택 시트 | 옵션 순서 정렬 | 없는 이름을 쓰면 새 옵션이 생길 수 있음 △. 색 이름 10종→팔레트 |
| 4 | 다중 선택 | 배열 | ✔ | 칩 + "+N" | 다중 선택 시트 | 포함 | |
| 5 | 상태 | {id,name,color} + 스키마 그룹(to_do·in_progress·complete) | ✔ 기존 옵션만 | 색 칩 | 선택 시트, 보드 열 이동 | 옵션/그룹 단위 그룹 | 완료 판정은 그룹으로(현재 앱 로직 재사용) |
| 6 | 날짜 | start·end·time_zone | ✔ | M/D·시간·기간 | 날짜 선택, ±1일 | 범위 비교 | 날짜만/시간 포함 구분, 시간대(NS-07 재사용) |
| 7 | 사람 | 사용자 객체 배열 | ✔ `[{id}]` | 이니셜·아바타 | 선택 시트 | `me`는 봇이라 쓸 수 없음 | 사용자 정보 읽기 권한 없으면 id뿐 → 이름 폴백. 사용자 목록 API 필요 △ |
| 8 | 파일과 미디어 | 배열: file(url, expiry_time)·external·file_upload | △ 외부 URL, 또는 File Upload(생성→전송→첨부) | 썸네일·아이콘 | 탭해서 열기 | 비어 있음만 | **서명 URL은 1시간 후 만료** ✔, 외부 URL은 만료 없음 ✔ |
| 9 | 체크박스 | bool | ✔ | ☑ | 위젯에서 바로 토글 | 불리언 | 현재 앱에 구현됨 |
| 10 | URL | string | ✔ | 도메인만 | 탭→브라우저 | 문자열 | |
| 11 | 이메일 | string | ✔ | 주소 | 탭→`mailto:` | 문자열 | |
| 12 | 전화번호 | string | ✔ | 번호 | 탭→전화 걸기 화면(`ACTION_DIAL`, 권한 불필요) | 문자열 | |
| 13 | 수식 | {type: string\|number\|boolean\|date, value} | ✖ 읽기 전용 | 결과 유형대로 | 없음 | 값만 비교, 식 재계산 안 함 | `now()` 같은 식은 캐시가 낡음 → 동기화마다 갱신하고 "N분 전" 표시. 날짜 수식은 캘린더 기준이 될 수 있음 |
| 14 | 관계형 | `[{id}]` + has_more. **제목 없음** | ✔ `[{id}]` | 대상 제목 칩 | 선택 시트(대상 DB 조회) | 포함 | 25개 초과는 속성 항목 엔드포인트로 이어받기 ✔. 제목 해석 조회·캐시 필요. 대상 DB도 연결에 공유돼야 함 |
| 15 | 롤업 | {type: number\|date\|array\|incomplete\|unsupported…} | ✖ | 함수 결과 | 없음 | 값만 | 관계가 25개를 넘으면 부분 값 → 속성 항목으로 완성 ✔ |
| 16 | 생성 일시 | ISO | ✖ | 상대 시간 | 없음 | 정렬, 캘린더 기준 ✔ | |
| 17 | 생성자 | 사용자 객체(부분) | ✖ | 이름 | 없음 | 포함 | |
| 18 | 최종 편집 일시 | ISO(분 단위 내림) | ✖ | 상대 시간 | 없음 | 정렬, 증분 동기화 키 ✔ | 2분 겹침 조회(현재 앱 규칙) |
| 19 | 최종 편집자 | 사용자 객체(부분) | ✖ | 이름 | 없음 | 포함 | |
| 20 | 버튼 | `button: {}` 뿐 ✔ | ✖ 누를 수 없음 ✔ | 라벨(스키마 이름) | "노션에서 열기"만. 대체는 §4.5 위젯 동작 | — | 현재 앱의 `toTMR` 버튼도 같은 처지 |
| 21 | 장소 | △ 모양 미확인 | △ | 주소 텍스트 | 지도 열기(`geo:`) | — | 실제 JSON을 받아 확인하기 전에는 읽기 전용 + 원본 JSON 표시 |
| 22 | ID(unique_id) | {number, prefix} | ✖ 노션이 부여 ✔ | `TASK-12` | 없음 | 정렬 | |

목록 밖 속성: `verification`(위키 DB, 쓰기는 verified/unverified만) ✔ → 읽기 전용 표시. **알 수 없는 유형은 `Unsupported(raw)`로 받아 회색 읽기 전용 셀로 표시하고 절대 죽지 않는다**(현재 앱 NS-09 원칙의 일반화).

쓰기 가능 13: 텍스트·숫자·선택·다중 선택·상태·날짜·사람·파일·체크박스·URL·이메일·전화번호·관계형. 읽기 전용 8: 수식·롤업·생성 일시·생성자·최종 편집 일시·최종 편집자·버튼·ID. 미확인 1: 장소.

### 2.4 공통 제약
- **속성 키는 이름으로 보낸다**: 퍼센트 인코딩된 ID를 키로 보내면 노션이 오류 없이 무시한다(현재 앱에서 실제로 겪음). 보낼 때마다 스키마에서 ID→이름을 찾는다.
- 쓰기 payload에는 모르거나 읽기 전용인 값을 복사하지 않는다 ✔.
- 읽기 전용 속성·알 수 없는 속성은 변경 목록에 절대 들어가지 않게 타입으로 막는다(컴파일 단계).
- 파일 서명 URL은 시간당 갱신 → 썸네일은 만료 전에 내려받아 디스크에 두고, 원본을 열 때는 최신 URL을 다시 받는다.
- 휴지통(`in_trash`)은 삭제 대신 쓴다(현재 앱 NS-04).
- 필터에서 `me`(사람)는 연결이 봇이므로 의미가 다르다 → 로컬 평가에서 해석 불가로 표시하고 서버 질의 결과를 우선한다.

### 2.5 페이지 속성 항목 엔드포인트 ✔
`GET /v1/pages/{id}/properties/{prop_id}`. 제목·텍스트·관계형·사람은 페이지네이션 목록, 롤업은 "원본 표시"면 평탄화 목록·집계면 관계 목록을 모두 받은 뒤 최종 값. 요청은 `start_cursor`·`page_size`. 응답의 다음 커서 필드 이름이 문서 사이에 다르게 적혀 있어 실제 응답으로 확인한다 △.

### 2.6 파일 업로드·웹훅(선택)
- 파일 쓰기는 외부 URL 또는 File Upload API(업로드 객체 생성 → 전송 → 속성에 첨부). 큰 파일은 분할 전송. P2.
- 웹훅(`page.properties_updated` 등)은 "신호"이고 내용은 다시 조회해야 한다 ✔. 받으려면 공개 HTTPS 서버가 필요 → 비목표(현재 앱 §14 C와 같은 결정).

## 3. 보기 7종 → 앱 화면 · 위젯 템플릿

| 보기 | 노션 설정 | 앱 화면(Compose) | 위젯 템플릿(Glance) | 상호작용 | 어려운 점 |
| --- | --- | --- | --- | --- | --- |
| 표 | 표시 속성·순서, 줄바꿈, 고정 열, 필터·정렬 | 스티키 머리글 표, 가로 스크롤, 셀 탭 편집 | 행 목록 + 열 N개(너비·글꼴 배율로 계산), 머리줄은 고정 Row, 열이 넘치면 열 페이지 ◀▶ 버튼 | 체크 토글, 선택·상태 변경 시트, 숫자 ±, 행 탭→상세 | 위젯은 가로 스크롤이 없다 |
| 보드 | 그룹(상태·선택, 옵션/그룹 단위), 하위 그룹, 카드 배치, 커버 | 열 가로 스크롤, 카드 **드래그로 열 이동**→상태 변경 | 폭 1~3열을 나란히, 좁으면 한 열 + 열 전환 칩. 카드=제목+속성 ≤2 | 카드 탭→상태 변경 시트, 체크 토글 | 위젯 안 드래그 불가 → 시트로 대체 |
| 캘린더 | 캘린더 기준(날짜·생성/최종 편집 일시·날짜 수식) | **현재 앱의 월·주·목록을 포팅** | 월간·달력+일정·아젠다(현재 위젯 3종 일반화) | 날짜 탭→퀵 시트, 체크, 추가 | 기준 속성이 날짜 수식이면 읽기 전용 → 날짜 변경 불가 |
| 타임라인 | 시작·종료 날짜 속성, 그룹 | Canvas 간트, 막대 **끌어 날짜 변경**, 확대·축소 | 1~3주 줄 막대: 한 줄=Row(앞 간격·막대·뒤 간격을 비중으로) 자식 3개 | 막대 탭→상세, ±1일 이동 | 위젯은 절대 좌표가 없어 비중 기반으로만 |
| 리스트 | 표시 속성, 필터·정렬 | 제목 + 속성 1~2, 하위 항목 접기 | 제목 + 속성 ≤2 + 체크박스 | 체크, 행 탭 | 가장 단순 → **모르는 보기 유형의 대체 템플릿** |
| 갤러리 | 커버 속성(크기·aspect), 카드 배치 | 스태거드 그리드 + Coil | `LazyVerticalGrid` 2~3열, 커버 썸네일 + 제목 | 카드 탭→상세 | 비트맵 예산, 서명 URL 만료 |
| 차트 | 유형(column·bar·line·donut·number), 집계, 그룹(x축), 쌓기, 색, 높이 | **로컬에서 직접 집계**해 Canvas로 그림(노션은 차트 결과를 API로 주지 않음 △) | number=텍스트, column·bar=비중 막대(≤8~12), line·donut=예산 있는 비트맵 | 탭→앱의 차트 화면 | 집계 의미(날짜 구간 묶기 등)를 노션과 맞춰야 함 |

범위 밖: 폼·지도·대시보드 보기(리스트로 대체 표시), 페이지 본문 편집.

## 4. 안드로이드 기술

### 4.1 위젯 제약(Glance 1.2, RemoteViews)
- 그리기 캔버스·사용자 정의 뷰가 없고 레이아웃 복잡도·스타일(그라디언트 등)에 한계가 있다 ✔.
- `Row`·`Column` 자식은 10개 이하(현재 앱은 구조로 회피, 한 줄 8개) △. 격자·목록은 `LazyColumn`·`LazyVerticalGrid`를 쓴다. `LazyVerticalGrid`는 1.2.0-rc01 API 문서에 존재하나 실제 동작·제한은 실측 필요 △.
- **비트맵 메모리**: Android 17에서 직렬화된 이미지 메모리가 `6 × 너비 × 높이` 바이트를 넘으면 `IllegalArgumentException`으로 앱이 죽는다고 공식 Glance 가이드가 적는다 ✔. `SizeMode.Exact/Responsive`에서는 크기 버킷마다 비트맵이 따로 만들어지므로 `provideContent` 밖에서 한 번 줄여 디코드한다 ✔. 현재 앱 PF-07은 `폭×높이×4×1.5`를 쓰고 있어 두 규칙을 실측으로 맞춘다 △.
- Glance는 이미지 로더(Coil·Glide)를 직접 쓸 수 없다. 비트맵을 미리 로드해 `ImageProvider(bitmap)`로 넘긴다 ✔.
- 위젯 안에 텍스트 입력 칸이 없다 → 반투명 시트 Activity(현재 앱의 퀵 시트 패턴).
- 갱신: 변경 시 `updateAll`(300ms 디바운스), 주기는 WorkManager 최소 15분, 삼성 절전, 자정 알람(현재 앱 구조 재사용).
- Glance 1.2.0 안정판(2026-08-26 표기, 출처 간 상이 △). 1.3 알파의 Remote Compose는 알파라 쓰지 않는다(현재 앱 "알파·베타 금지").
- 위젯은 로컬 DB만 읽는다(WG-02). 네트워크·JSON 파싱·이미지 디코딩을 렌더 경로에서 하지 않는다 → **스냅샷 표·미리 줄인 썸네일**을 쓴다.

### 4.2 위젯 렌더링 전략
- **비중 기반 구조 그리기**: Row·Column의 `defaultWeight()`로 막대 비율을 만든다(세로 막대=`위 간격(1−v)` + `막대(v)`, 간트=`앞 간격` + `막대` + `뒤 간격`). 값 0은 최소 비중. 컨테이너당 자식 ≤10이라 막대 수를 8~12로 제한하고 넘으면 상위 N + "기타"로 묶는다.
- **예산 있는 비트맵**: 선·도넛은 Compose/Canvas로 오프스크린에 그려 한 장으로 만든다. 크기는 위젯 픽셀 이하로 제한하고 위젯 수·크기 버킷 수를 곱한 총량을 예산 안에서 관리한다. 비트맵은 동기화·갱신 작업에서 만들어 디스크에 두고 렌더에서는 읽기만 한다.
- **썸네일 파이프라인**: 동기화 중 커버 이미지를 내려받아 위젯 셀 크기로 줄여 디스크 캐시 → 위젯은 캐시만 읽는다. 만료된 서명 URL은 다음 동기화에서 새로 받는다.
- **밀도 계산**: 위젯 크기 × 시스템 글꼴 배율 × 화면 확대로 "보이는 행·열 수"를 계산하고 넘치면 한 단계 단순한 레이아웃으로 내린다(현재 앱 WS-05 로직 재사용).

### 4.3 앱 화면(Compose)
- Compose Material3, 적응형 레이아웃. 표=`LazyColumn` + 가로 스크롤, 갤러리=`LazyVerticalStaggeredGrid`, 큰 목록은 Paging 3.
- 드래그 이동(보드·타임라인): 재정렬 라이브러리 또는 `pointerInput` 직접 구현. 버전은 Claude Code가 그 시점 안정판을 확인한다.
- 간트·차트는 `Canvas`로 직접 그린다(의존성 최소). 차트 라이브러리(예: Vico)를 쓰려면 안정판과 위젯 비트맵 공유 가능 여부를 먼저 본다.
- 이미지: Coil 3(앱 화면), 위젯용 썸네일은 별도 디스크 캐시.
- 지도(장소): 키가 필요 없는 오픈 지도 후보(MapLibre·osmdroid)는 P2.
- 디자인: 현재 앱 `:core:designsystem`·`DESIGN.md`(노션 문서 색, 선택 색 10종 팔레트)를 그대로 가져온다.

### 4.4 저장소·동기화
- Room 2.8 + AutoMigration + 스키마 내보내기 + 마이그레이션 테스트(현재 앱 PF-09 규칙). 검색은 FTS.
- 저장 구조 제안(§5): 값 원본 JSON은 `pages.raw`, 질의·정렬용으로 **유형별 칸을 가진 값 표**(`property_values`: 문자열·숫자·날짜 시작/끝·불리언·옵션 id 목록)를 따로 둔다. 행 수천 개에서도 보기 계산이 메인 스레드 밖에서 수십 ms 안에 끝나야 한다.
- **보기 구독 동기화**: (1) 스키마·보기 목록(앱 열 때·하루 1회) (2) 구독 보기의 행: 보기 질의(≤1만 행, 15분 캐시)로 ID 집합과 순서, 페이지 본문은 데이터 소스 증분 질의(최종 편집 일시 − 2분)로 (3) 관계 제목·사용자·파일 썸네일 해석 (4) 스냅샷 재계산 (5) 위젯 갱신. 삭제 감지는 ID 집합 비교.
- 로컬 평가가 필요한 이유: 위젯에서 체크한 항목이 "미완료만" 보기에서 **즉시** 사라져야 하고, 오프라인에서도 보기가 동작해야 한다. 그래서 필터·정렬·그룹·집계를 로컬에서도 평가하되, 평가 못 하는 연산자는 서버 질의 결과를 우선한다.
- 토큰: DataStore + Tink + Keystore(현재 앱 SEC-01). 캐시 DB는 자동 백업에서 제외.

### 4.5 위젯 상호작용
위젯에서 되는 것: 체크박스 토글, 선택·상태 변경(시트), 숫자 ±, 날짜 ±1일·오늘로, 빠른 추가(보기 필터 값으로 속성 미리 채움), URL·메일·전화·지도 열기, 노션에서 열기, 새로고침, 보기 전환 탭, 이전·다음 페이지·월.
위젯에서 안 되는 것: 텍스트 직접 입력, 드래그, 스와이프·핀치, 길게 눌러 메뉴, 애니메이션 → 시트 Activity나 앱 화면으로 넘긴다.
**위젯 동작(버튼 대체)**: 사용자가 "날짜 +1일", "상태를 Done으로", "숫자 +1", "체크 토글" 같은 한 동작을 이름 붙여 만들고 위젯 버튼으로 노출한다. 노션 버튼이 못 하는 일을 앱이 속성 직접 수정으로 대신한다(현재 앱 WI-06 '내일로'의 일반화).
모든 쓰기는 낙관 반영 + 아웃박스. 읽기 전용 속성·알 수 없는 속성은 쓰기 경로에 들어올 수 없게 타입으로 막는다.

## 5. 아키텍처 제안

모듈(현재 앱 규칙 계승: 화면·위젯은 Room만, 네트워크는 sync만, `:widget`은 `:core:notion` 의존 금지)

```
:app
:feature:browser      데이터 소스·보기 고르기
:feature:viewer       보기 7종 앱 화면
:feature:editor       속성 편집 시트
:feature:widgetconfig 위젯 레시피 만들기(미리보기)
:widget               템플릿 7종 + 통합 탭 + 액션
:core:viewengine      순수 Kotlin: 필터·정렬·그룹·집계·UiModel (JVM 테스트)
:core:model           PropValue·PropertySchema·ViewConfig·PropertyHandler 레지스트리
:core:data            Room·Repository·아웃박스
:core:sync            WorkManager: 스키마·보기·페이지·관계·썸네일
:core:notion          API: views·pages·property items·users·files
:core:designsystem
```

Room 표(안): `data_sources`, `properties`(스키마), `views`(유형·필터 JSON·정렬 JSON·설정 JSON), `pages`(pageId·dsId·제목·아이콘·커버·lastEdited·inTrash·raw), `property_values`(유형별 칸), `relation_titles`, `users`, `file_thumbs`, `view_snapshots`(viewId·rank·pageId·groupKey), `widget_recipes`, `outbox`, `conflicts`, `sync_log`.

핵심 추상화
```kotlin
sealed interface PropValue  // Text, Number, Select, MultiSelect, Status, Date, People, Files, Checkbox,
                            // Url, Email, Phone, Formula, Relation, Rollup, CreatedTime, CreatedBy,
                            // LastEditedTime, LastEditedBy, Button, Place, UniqueId, Unsupported(raw)
interface PropertyHandler<V : PropValue> {
    val type: String
    val caps: Caps   // readable, writable, filterable, sortable, groupable, aggregable
    fun parse(json: JsonElement, schema: PropertySchema): V
    fun toWrite(patch: PropPatch): JsonElement?   // 읽기 전용이면 null이 아니라 컴파일 단계에서 막힘
    fun text(v: V): String
    fun compare(a: V, b: V): Int
}
```
셀 렌더러(Compose용·Glance용)와 편집기는 유형별로 레지스트리에 등록한다. Glance와 Compose는 컴포저블을 공유할 수 없으므로 **공유 지점은 UiModel**이다.

위젯 레시피(안)
```json
{ "template": "TABLE|LIST|BOARD|CALENDAR|TIMELINE|GALLERY|CHART",
  "dataSourceId": "…", "viewId": "…|null",
  "properties": [{"propId":"…","role":"TITLE|COLUMN|BADGE|COVER|DATE|GROUP|VALUE|COLOR","order":1}],
  "filterOverride": null, "sortOverride": null, "limit": 50,
  "interactions": ["TOGGLE","PICK_STATUS","STEP_NUMBER","SHIFT_DATE","QUICK_ADD","OPEN_NOTION"],
  "actions": [{"label":"내일로","op":"SHIFT_DATE","propId":"…","days":1}],
  "theme": {}, "density": "AUTO" }
```

## 6. 현재 앱에서 가져올 것과 이번에 배운 함정

현재 앱의 상태(2026-10-30, `ccr-6810527c-es92oi` 브랜치 확인): Phase 6~8이 구현돼 있다. DB 버전 11, 모듈 `:feature:habit·ledger·dday`와 `:core:weather`, 위젯 8종 안팎(월간·달력+일정·아젠다·습관·통합 탭·가계부·디데이 3종). 아래 함정 중 위젯 종류 찾기는 이미 `widget/.../WidgetKinds.kt`(`AppWidgetManager`로 위젯 종류를 찾아 다시 그림), 위젯 수신자 모음은 `WidgetReceivers.kt`, 갱신 목록은 `AllWidgets.kt`에 반영돼 있다. **복사할 때는 이 반영본을 가져오고, 낡은 PRD 초기 문장이 아니라 코드를 기준으로 삼는다.** 습관·가계부의 도메인 로직(주 목표, 예산, 중복 정리)은 이 새 앱의 "위젯 동작·계산 속성"으로 일반화할 수 있는지 먼저 검토할 대상이다(PROMPTS §D-4).

가져올 자산(경로는 `myNotionCalendar` 기준)
- 노션 클라이언트·재시도·속도 제한: `core/notion/.../NotionClient.kt`, `RetryAndRateLimit` 테스트
- 날짜 처리: `mapping/DateCodec.kt`(날짜만/시간 포함, Asia/Seoul)
- 아웃박스·충돌·임시 ID: `core/data/.../EventWriter.kt`, `core/sync/.../OutboxProcessor.kt`
- 동기화 구조·스케줄러·진단: `NotionSyncEngine.kt`, `SyncWorker.kt`, `feature/diagnostics`
- 복구: `RecoveringOpenHelperFactory.kt`, 마이그레이션 테스트 패턴
- 보안: SecretStore(Tink), 백업 규칙
- 위젯: 갱신 디바운스 `GlanceWidgetRefresher.kt`, 종류 레지스트리 `WidgetKinds.kt`, 설정 저장소 `WidgetConfigRepository.kt`, 월 그리드·달력+일정·아젠다, 통합 탭(`TabbedWidget.kt`), `Glance` 단위 테스트 패턴(`*WidgetTest.kt`)
- 디자인: `core/designsystem`, `DESIGN.md`
- 개발 규율: StrictMode 설정(`oemFrames`), Sentry, Baseline Profile·JankStats, 빌드 컨벤션(`build-logic`)

이번 세션의 검증에서 나온 함정(새 앱에서는 처음부터 피한다)
1. 날짜 같은 필수 칸을 빈 문자열로 때우지 않는다. nullable로 두고 종류별로 매핑을 만든다(재연결·대조 기능으로 샌다).
2. **생성 전송 중 삭제 경쟁**: 아웃박스 CREATE가 전송 중일 때 사용자가 지우면 항목이 되살아난다. 생성 응답 시 아웃박스 행이 사라졌으면 취소로 보고 만든 페이지를 휴지통으로 보낸다.
3. 위젯 설정 저장이 전체 행을 덮어쓰므로 새 칸(선택 탭 등)이 월 이동·저장 때마다 초기화된다. 설정 모델·변환에 새 칸을 모두 넣고 테스트한다.
4. 위젯 액션이 위젯 클래스를 하드코딩하면 통합 위젯에서 엉뚱한 위젯이 그려진다. 위젯 종류는 `AppWidgetManager`에서 찾는다.
5. 연타하는 토글은 읽기·판단·쓰기를 뮤텍스로 묶는다.
6. 통계·동기화 범위를 UI에서 필요한 기간보다 짧게 잡지 않는다.
7. 위젯 종류가 늘 때 "배치된 위젯이 있는지" 판단 함수(자정 알람 유지)와 갱신 목록에 새 위젯을 빠뜨리지 않는다.
8. 앱 DB 파일을 자동 백업에서 제외한다(금액 같은 개인 데이터).
9. 테스트 대역(`FakeNotionStore`)이 데이터 소스 ID를 무시하면 다중 DB 테스트가 거짓 통과한다. 처음부터 DB별로 나눈다.
10. 노션 `button`처럼 API가 못 하는 일을 앱이 대신할 때는 "대신하는 것"임을 화면에 드러낸다.

## 7. 확인 필요 항목 (Claude Code가 제일 먼저)

| # | 확인할 것 | 방법 |
| --- | --- | --- |
| 1 | 보기 유형별 `configuration` JSON의 실제 키(table·board·calendar·timeline·list·gallery·chart) | 사용자의 실제 DB에서 `GET /v1/views`로 받아 **개인정보를 지운 합성 픽스처**로 저장 |
| 2 | 장소(place) 속성의 읽기·쓰기 JSON | 실제 값이 있는 DB에서 받아 확인, 없으면 읽기 전용 |
| 3 | 보기 질의의 이어받기·필터 적용·정렬 보장 여부, 빠른 필터 반영 여부 | 실제 호출 + MockWebServer 픽스처 |
| 4 | 속성 항목 응답의 다음 커서 필드 이름 | 실제 응답 |
| 5 | 선택 쓰기에서 없는 이름이 새 옵션을 만드는지 | 테스트 DB에서 확인 |
| 6 | 사용자 정보 읽기 권한이 없을 때 사람 속성 응답 | 권한 끈 연결로 비교 |
| 7 | 관계형 대상 DB가 공유 안 됐을 때의 응답과 오류 | 실제 호출 |
| 8 | 차트 집계·날짜 묶기 의미 | 노션 차트 보기와 로컬 집계 결과를 나란히 비교 |
| 9 | Glance `LazyVerticalGrid`·비중 막대·자식 개수 제한 | 위젯 단위 테스트 + 실기기 |
| 10 | 비트맵 메모리 상한 규칙(`6×w×h` vs `폭×높이×4×1.5`) | S25 실측, `adb shell am memory-limiter` |
| 11 | Glance 1.2.0 안정판 여부와 현재 최신 안정판 | 릴리스 페이지 |
| 12 | 연결의 기능(capability)별로 실패하는 호출 | 각 호출의 오류 코드를 `NotionError`로 분류 |

## 8. 출처

노션(2026-10-30 검색으로 확인, 직접 열람은 못 함)
- [Working with views](https://developers.notion.com/guides/data-apis/working-with-views) · [Retrieve a view](https://developers.notion.com/reference/retrieve-a-view) · [Create a view](https://developers.notion.com/reference/create-view) · [Create view query](https://developers.notion.com/reference/create-view-query) · [Update a view](https://developers.notion.com/reference/update-a-view) · [View](https://developers.notion.com/reference/view)
- [Page property values](https://developers.notion.com/reference/page-property-values) · [Data source properties](https://developers.notion.com/reference/property-object) · [Page property items](https://developers.notion.com/reference/property-item-object) · [Retrieve a page property item](https://developers.notion.com/reference/retrieve-a-page-property) · [변경 공지: 속성 값 조회](https://developers.notion.com/changelog/retrieve-page-property-values)
- [Working with files and media](https://developers.notion.com/docs/working-with-files-and-media) · [Query large data sources](https://developers.notion.com/guides/data-apis/query-large-data-sources)
- [Webhook: page.properties_updated](https://developers.notion.com/reference/webhooks/page-properties-updated) · [Webhook 전달](https://developers.notion.com/reference/webhooks-events-delivery) · [Hookdeck 가이드(2차)](https://hookdeck.com/webhooks/platforms/guide-to-notion-webhooks-features-and-best-practices.md)
- [notion_dart_kit 변경 기록(보기 설정을 원본 JSON으로 노출한다는 언급, 2차)](https://pub.dev/packages/notion_dart_kit/changelog)
- 노션 연결 도구의 보기 DSL 명세(`notion://docs/view-dsl-spec`, 2026-10-30)

안드로이드
- [Create an app widget with Glance](https://developer.android.com/develop/ui/compose/glance/create-app-widget) · [Glance 릴리스](https://developer.android.com/jetpack/androidx/releases/glance) · [GridCells(1.2.0-rc01)](https://www.composables.com/docs/androidx.glance/glance-appwidget/1.2.0-rc01/classes/GridCells)
- [Demystifying Jetpack Glance](https://medium.com/androiddevelopers/demystifying-jetpack-glance-for-app-widgets-8fbc7041955c) · [The Things No One Tells You about Jetpack Glance](https://blog.boxbox.club/the-things-no-one-tells-you-about-jetpack-glance-d3a2993f51c9) · [Glance, RemoteViews, RemoteCompose](https://medium.com/@fioravanti.luka/glance-remoteviews-and-remotecompose-what-actually-changed-in-android-16-4afc4b63b0ad) · [Widgets with Glance: Displaying Images](https://proandroiddev.com/widgets-with-glance-displaying-images-fe6a1c63c128)
