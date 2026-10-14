# 성능·안정성 측정 기록

PRD §3 성공 지표와 §6 PF·§9 S25 항목의 실측값. 기기는 Galaxy S25(SM-S931N, Android 17, 120Hz), 앱 데이터는 사용자 실제 📅 Calendar DB(약 90건).

## 측정 방법
| 지표 | 도구 | 명령 |
|---|---|---|
| 콜드 스타트 (PF-01) | Macrobenchmark `StartupBenchmark`, StartupTimingMetric, COLD 10회 | `gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` |
| Baseline Profile | `BaselineProfileGenerator` | `gradlew :app:generateBaselineProfile -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` |
| 월 스와이프 프레임 (S25-05) | 앱 안 JankStats(`adb logcat -s NotionCalJank`) + Macrobenchmark `MonthSwipeBenchmark`(FrameTimingMetric) | 위와 같음 |
| 월 전환 조회 (PF-08) | `LargeDataQueryTest`(Robolectric, DB 2개 × 2,000건) | `gradlew :core:data:testDebugUnitTest` |
| 위젯 메모리 (PF-07·S25-02) | `dumpsys meminfo`, logcat IllegalArgumentException | — |

- 측정 앱은 `benchmarkRelease`(release와 같은 ID `com.ysdoing.notioncal`, R8 적용, debug 키 서명)이다. 실제 토큰·DB를 등록해 두고 잰다.
- `leaveApksInstalledAfterRun=true`를 빼면 Gradle이 측정 뒤 앱을 지워 등록한 토큰도 사라진다.

## 결과 (2026-10-14, 게이트 4)

### 콜드 스타트 — PF-01, §3 목표 1.0초 이하 ✅
| 컴파일 | timeToInitialDisplay (첫 프레임) | timeToFullDisplay (이번 달 일정 표시) |
|---|---|---|
| Baseline Profile 없음 | 중앙값 142.7ms (134.5~153.0) | 중앙값 318.3ms (302.0~353.9) |
| Baseline Profile 적용 | 중앙값 137.8ms (124.0~148.1) | **중앙값 232.2ms** (218.7~241.3) |

- 목표 판단은 timeToFullDisplay(월 화면에 이번 달 일정을 그린 시점, `ReportDrawnWhen`)로 한다. Profile 적용으로 27% 줄었다.
- Profile: `app/src/release/generated/baselineProfiles/` (규칙 27,444줄, 앱 클래스 1,551줄).

### 월 스와이프 프레임 — S25-05, §3 목표 느린 프레임 5% 미만 ✅
| 기준 | 프레임 | 느린 프레임 |
|---|---|---|
| JankStats(앱 안, 기본 판정: 프레임 시간이 정상의 2배 초과), 스와이프 12회 | 456 | **0 (0.0%)** |
| FrameTimingMetric `frameOverrunMs > 8.3ms`(120Hz에서 한 프레임 이상 밀림), 5회 × 6스와이프 | 853 | 14 (1.6%) |
| 참고: `frameOverrunMs > 0`(마감을 조금이라도 넘김) | 853 | 151 (17.7%) |

- frameDurationCpuMs P50 5.3ms, P90 7.4ms, P99 17.7ms.
- 마감을 1ms 안팎으로 넘기는 프레임이 17.7% 있다. 눈에 띄는 끊김(한 프레임 이상 밀림)은 1.6%다. 개선 여지로 남긴다(그리드 재구성 비용).

### 그 밖
| 항목 | 결과 |
|---|---|
| PF-08 월 전환 조회(4,000건) | 중앙값 20.9ms, 최대 37.2ms (PC Robolectric, 목표 100ms) ✅ |
| 위젯 반영(WG-05, §3 0.5초) | 요청 → 갱신 완료 307~435ms(300ms 묶음 대기 포함) ✅ |
| 퀵 시트 표시(WI-01) | 26~51ms |
| 앱 메모리(debug 빌드, 월 화면) | TOTAL PSS 124MB, Graphics 5MB |
| 위젯 비트맵(PF-07·S25-02) | 위젯 소스에 비트맵 API 0(`WidgetNoBitmapTest`), 글꼴·화면 확대 9개 조합 동안 IllegalArgumentException 0 ✅ |
| StrictMode(PF-03) | 위반 0 ✅ |

### S25-06 글꼴 × 화면 확대 조합
글꼴 0.8·1.0·2.0 × 밀도 420(사용자 설정)·480(기본)·540(확대). 스크린샷: `docs/shots/s25-06/`.
- 검증 중 고친 것:
  - 위젯이 기기 글꼴 변경을 모르고 예전 배율로 줄 높이를 계산하던 문제를 고쳤다.
  - 줄 높이 비율을 실측값(1.65배)으로 바꿨다.
  - 위젯 글자가 기기 글꼴을 1.3배까지만 따르게 했다.
- 9개 조합 모두 잘림이 없다. 칸에 다 들어가지 않는 일정은 "+N"으로 줄인다.
- 미검증: 홈 화면 격자 설정 변경, 위젯 크기 최소~최대 조합(손 조작 필요).

### S25-09 메모리 한도 종료
- 이 기기는 `am memory-limiter status`가 `disabled`라 수동 한도(`manual <PID> 1`)도 적용되지 않았다.
- 메모리 한도로 종료되는 상황은 미검증이다. 진단 화면의 종료 사유 표시는 실제 기록(강제 종료, 앱 업데이트, 메모리 부족 등)으로 확인했다.
