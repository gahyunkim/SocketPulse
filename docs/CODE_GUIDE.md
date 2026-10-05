# SocketPulse 코드 안내

이 문서는 현재 코드의 동작을 설명합니다. 기능 구현 파일에는 한국어 KDoc을 추가했습니다.
IntelliJ/Android Studio에서 선언이나 사용 지점에 마우스를 올리거나 Quick Documentation을 실행하면 설명을 볼 수 있습니다.
호버 문서가 표시되지 않으면 IDE 설정에서 마우스 호버 시 Quick Documentation 표시를 활성화합니다.
Markdown 문서는 전체 구조 안내이고, 심벌별 호버 설명은 소스의 `/** ... */` 주석이 제공합니다.

## 실행과 진입점

Java 21 툴체인을 사용하며 `./gradlew runIde` 또는 IDE의 `Run Plugin` 구성으로 샌드박스 Android Studio를 실행합니다.
실행 대상은 `build.gradle.kts`에 지정된 Android Studio 2025.3.1.1입니다.
`gradle.properties`의 `platformVersion` 값은 현재 실행 대상 선택에 사용되지 않습니다.
일반 앱의 main 함수는 없으며 `plugin.xml`의 toolWindow 등록을 통해 `MyToolWindowFactory`가 호출됩니다.
샌드박스 IDE에서 프로젝트를 열고 SocketPulse 툴윈도우를 선택합니다.

## 소스 파일

경로는 저장소 루트 기준입니다. Kotlin 패키지 루트는 `src/main/kotlin/com/github/gahyunkim/socketpulse/`입니다.

| 파일 | 역할 |
| --- | --- |
| `toolWindow/MyToolWindowFactory.kt` | 연결 UI, POST 로그 수집 서버, 이벤트 목록, JSON 송신, 로그 검색·상세 보기 |
| `toolWindow/ByteRateStore.kt` | 기본 30초 원형 버퍼에 초별 RX/TX 바이트 누적 및 배열 스냅샷 |
| `toolWindow/ThroughputChart.kt` | RX/TX 선 그래프, 현재 초의 B/s 표시, 드래그 시간 범위 콜백 |
| `toolWindow/EventTimelineStore.kt` | 기본 30초·이벤트별 5,000개 한도의 발생 시각 보관 및 빈도 집계 |
| `toolWindow/TimelineChart.kt` | 이벤트별 발생 시각을 행별 세로선으로 표시하는 별도 컴포넌트 |
| `MyBundle.kt` | properties 메시지의 즉시·지연 조회 |
| `services/MyProjectService.kt` | 프로젝트 단위 템플릿 서비스 및 난수 예제 |
| `startup/MyProjectActivity.kt` | 템플릿 정리 안내를 기록하는 시작 활동. 현재 확장 등록 없음 |
| `src/test/kotlin/com/github/gahyunkim/socketpulse/MyPluginTest.kt` | XML PSI, 이름 변경, 서비스의 템플릿 테스트 |
| `src/test/testData/rename/foo.xml` | 이름 변경 전 XML 및 테스트 caret 위치 |
| `src/test/testData/rename/foo_after.xml` | 이름 변경 후 기대 XML |
| `src/main/resources/META-INF/plugin.xml` | 플러그인 ID, 의존성 및 하단 툴윈도우 팩토리 등록 |
| `src/main/resources/messages/MyBundle.properties` | 템플릿 메시지 키와 자리표시자 |

## 데이터 흐름

### 앱 모니터링

Start App Monitoring은 기본 9999 포트에 HTTP 서버를 엽니다. 앱은 다음 형태의 JSON을 `POST /log`로 보냅니다.

```json
{"event":"chat:message","data":"{\"text\":\"hello\"}"}
```

요청 본문 전체 바이트 수가 RX에 누적되고 event의 발생 시각이 기록됩니다.
일반 이벤트는 감지 목록에 추가되고, 로그와 상태 표시 갱신은 Swing EDT에 예약됩니다.
`events:list:result`의 data는 `{"events":[{"name":"chat:message","direction":"incoming","desc":"메시지"}]}` 형식으로 이벤트 목록을 교체합니다.
서버는 루프백 전용 바인딩이 아니며 인증이나 요청 크기 제한은 구현되어 있지 않습니다.

### 직접 연결과 송신

Connect는 기본 `http://localhost:3000`에 Socket.IO 연결을 만듭니다. 기존 소켓은 닫습니다.
직접 수신은 로그에만 들어가며 RX 누적·이벤트 목록·타임라인 저장소는 갱신하지 않습니다.
Send Emit은 JSON 객체를 연결된 소켓으로 보내고 해당 JSON 문자열의 바이트 수를 TX에 더합니다.
HTTP 앱 모니터링만 실행한 상태에서는 수동 송신할 Socket.IO 연결이 없습니다.
차트 수치는 애플리케이션에서 집계한 바이트이며 프로토콜 오버헤드를 포함한 네트워크 전체 사용량이 아닙니다.

### 로그와 차트

로그 선택 시 상세 영역이 열리며 JSON 객체를 들여쓰기해 보여줍니다.
검색창 Enter는 전체 메시지에 부분 문자열 필터를 적용합니다. Aa 체크박스는 대소문자 구분을 제어합니다.
정규식 체크박스와 findNext 함수는 현재 검색 동작에 연결되지 않았습니다.
Reset View는 전체 로그를 표시하지만 활성 필터 상태는 해제하지 않아 이후 로그에는 이전 필터가 적용됩니다.
Clear Logs는 로그와 상세 보기를 지우며 차트 저장소나 이벤트 목록까지 초기화하지는 않습니다.
200ms 타이머가 화면을 갱신합니다. TimelineChart와 ThroughputChart의 범위 선택 콜백은 현재 툴윈도우에 연결되지 않았습니다.

## 설정·자동화 파일

| 파일 | 역할 |
| --- | --- |
| `build.gradle.kts` | Java/Kotlin, IDE 플랫폼, Socket.IO/JSON 의존성, 검증·커버리지·UI 테스트용 실행 설정 |
| `settings.gradle.kts` | 프로젝트 이름과 Java 툴체인 해결 플러그인 |
| `gradle.properties` | 플러그인 식별자·버전·호환 빌드와 Gradle 캐시 설정 |
| `gradle/libs.versions.toml` | 테스트 라이브러리와 Gradle 플러그인 버전 카탈로그 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 배포 URL 및 wrapper 설정 |
| `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` | Gradle wrapper 바이너리와 OS별 실행 스크립트. 생성 도구 파일은 주석 수정 대상에서 제외 |
| `.run/Run Plugin.run.xml` | runIde 실행 및 IDE 로그 표시 |
| `.run/Run Tests.run.xml` | check 실행 |
| `.run/Run Verifications.run.xml` | verifyPlugin 실행 |
| `.github/workflows/build.yml` | 빌드, check, Qodana, 플러그인 검증 및 main의 릴리스 초안 생성 |
| `.github/workflows/release.yml` | 릴리스 이벤트에 따른 Marketplace 게시, 배포 파일 업로드 및 변경 로그 PR |
| `.github/workflows/run-ui-tests.yml` | 수동 실행하는 OS별 IDE/Robot 서버 시작과 test 실행 |
| `.github/dependabot.yml` | Gradle·Actions 의존성의 일별 업데이트 검사 |
| `qodana.yml` | Qodana JVM 검사기·Java 버전·검사 프로필 |
| `codecov.yml` | 커버리지 상태를 정보성으로 표시하는 정책 |
| `.gitignore` | 캐시·빌드 결과·IDE 생성 디렉터리 제외 |
| `.idea/gradle.xml` | Git 추적 중인 IDE Gradle 연동 설정 |
| `README.md` | 프로젝트 소개·설치 안내 및 템플릿 체크리스트 |
| `CHANGELOG.md` | 배포 변경 기록. 현재 템플릿 초기 항목 포함 |

캐시와 `build/` 결과물은 코드 문서화 대상이 아닙니다. 설정 파일의 의미는 이 표에서 설명합니다.

## 현재 구현의 주의점과 검증

- allLogs는 파일 단위 공유 가변 목록으로 동기화되지 않습니다. 여러 인스턴스 간 격리나 로그 개수 제한은 없습니다.
- ByteRateStore의 snapshot은 동기화된 복사지만 getRawBuckets는 동기화되지 않은 내부 배열 접근입니다.
- EventTimelineStore는 동시성 컬렉션을 사용하지만 기록·정리·집계 전체가 원자적이지는 않습니다.
- 타이머·HTTP 서버·소켓을 프로젝트 종료에 연결하는 dispose 처리는 현재 없습니다.
- plugin.xml은 선택 Android 의존성의 android.xml을 참조하지만 현재 추적 파일에는 해당 파일이 없습니다.
- 템플릿 서비스 테스트는 두 난수값이 다를 것을 기대해 우연히 실패할 수 있습니다.

검증 명령은 `./gradlew compileKotlin compileTestKotlin`, `./gradlew check`, `./gradlew verifyPlugin`입니다.
이번 문서화는 기존 동작을 설명하며 위 제한 사항을 수정하지 않습니다.
