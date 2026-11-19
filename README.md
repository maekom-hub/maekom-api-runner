# 🌶️ Maekom API Runner

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](./LICENSE)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ_IDEA-2024.3%2B-orange.svg)](https://www.jetbrains.com/idea/)
[![JVM](https://img.shields.io/badge/JVM-21-brightgreen.svg)](https://adoptium.net/)

<p align="center">
  <b><a href="#-한국어-korean">🇰🇷 한국어</a></b> &nbsp;|&nbsp;
  <b><a href="#-english">🇺🇸 English</a></b> &nbsp;|&nbsp;
  <b><a href="#-버전별-다운로드-및-릴리즈-이력--release-history">📦 Release History</a></b> &nbsp;|&nbsp;
  <b><a href="https://github.com/maekom-hub/maekom-api-runner/releases/latest">⬇️ Latest Release</a></b>
</p>

---

## 🇰🇷 한국어 (Korean)

**Maekom API Runner**는 Spring Boot 개발자를 위한 IntelliJ IDEA Community / Ultimate (`2024.3+`) 전용 오픈소스 API 시나리오 러너 및 PSI 자동 샘플러 플러그인입니다.

### ✨ 주요 기능

- **🌶️ 고추(Chili) Gutter 아이콘으로 컨트롤러에서 바로 실행**
  - `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`, `@RequestMapping`이 선언된 Spring 컨트롤러 메서드 옆에 빨간 고추 아이콘이 자동 표시됩니다.
  - 클릭 한 번으로 **단건 바로 세팅 및 열기** 또는 **현재 시나리오에 스텝 추가**를 실행할 수 있습니다.
- **🤖 `@ModelAttribute` / POJO & `@RequestBody` 자동 샘플링**
  - `@RequestParam`, `@ModelAttribute` 및 매핑 어노테이션이 생략된 커스텀 DTO / Java `record`의 필드(상속·중첩 필드 포함)를 재귀 탐색해 **`Params` 전용 탭**(`전송 여부 | Key | Value | Description`)으로 자동 추출하며, Javadoc / `@Schema` / `@Parameter` 설명도 함께 채워 넣습니다.
  - `@RequestBody` DTO 및 Java `record`는 순환 참조를 방지하며 포맷팅된 JSON 샘플 본문을 자동 생성하고, `application.yml` / `application.properties`에서 `server.port`를 읽어 `BaseURL`을 자동 세팅합니다.
- **🗂️ 네이티브 ToolWindow 타이틀 바 통합, 시나리오 관리, 환경 프로필(`Local/DEV/STG/PRD`), 공통 헤더 가져오기 & 스마트 자동 저장**
  - **인텔리제이 네이티브 ToolWindow 타이틀 바 직접 통합:** 중복 커스텀 헤더 행을 제거하고, IntelliJ 네이티브 `Maekom API` 타이틀 바로 우측에 **`[업데이트 확인]`** + **`[ 한국어 | English ]` 2단 세그먼트 스위치**를, 우측 `⋮` / `—` 창 제어 아이콘 바로 왼쪽에 **실행 상태(`200 OK`) | 소요 시간(`ms`) | 저장 상태(`● 미저장 / 저장됨 / 자동 저장됨 HH:mm`)**를 직접 배치해 세로 작업 공간을 극대화했습니다.
  - 글로벌 툴바를 **`시나리오 → 저장 → 시나리오 관리 ▾(생성/이름변경/복제/삭제/불러오기/내보내기 통합) → 공통 헤더 관리 → 변수 관리 → cURL 복사 → 전체 실행 → 중지`** 순으로 깔끔하게 정렬했습니다.
  - 요청 편집 영역 최상단에 **`[Local ▾] → BaseURL 입력창 → [SSL 무시] → [⚙]`(아이콘 전용 환경 관리 버튼)**을 배치해 `.maekom/environments.json`에 프로젝트별 환경 BaseURL을 영속 저장합니다. `Local` 선택 시 `application.yml`/`properties` 포트를 자동 감지하며, 시나리오를 전환해도 환경 프로필이 그대로 유지됩니다.
  - 공통 헤더 관리 창(`공통 헤더 | 시나리오 변수 | 로컬 시크릿`)의 **`[📥 다른 시나리오에서 가져오기]`** 및 **`[📥 JSON 일괄 입력]`** 기능으로 공통 헤더 병합/덮어쓰기와 시나리오 변수·로컬 시크릿 대량 등록을 지원합니다.
  - 경량 `Alarm` 기반 **스마트 자동 저장**이 변경(Dirty) 상태일 때만 마지막 수정 후 30초 디바운스로 조용히 저장(유휴 시 디스크 I/O 0)하며, 상태 라벨에 마지막 자동 저장 시각(`자동 저장됨 HH:mm`)을 표시합니다.
- **🌐 3탭 변수 관리 (`변수 관리`), JSON 일괄 입력 & 변수명 중복 검증**
  - **`[변수 관리]`** 창에서 **① 프로젝트 영속 전역 변수(`.maekom/global-variables.json`, Secret 마스킹 + JSON 일괄 입력 지원, 시나리오 전환 시 유지)** CRUD, **② 8종 내장 동적 변수**, **③ 현재 시나리오 추출 변수**를 한 번에 조회·복사할 수 있습니다.
  - 전역 변수·시나리오 변수·로컬 시크릿 JSON 일괄 입력(객체 `{...}` / 배열 `[{...}]` 모두 지원) 및 응답 추출 변수 입력 시 **공통 변수명 중복 검증기(`VariableNameValidator`)**가 충돌을 자동 검사하고 추천 변수명을 제안·적용합니다.
  - URL·Params(Value)·Headers(Value)·Body(JSON) 입력창에서 우클릭(**`🌶️ 동적/추출 변수 삽입 ▶`**)으로 내장 동적 변수·추출 변수·전역 변수를 커서 위치에 즉시 삽입할 수 있습니다.
- **🔗 멀티 스텝 시나리오 체이닝, 원클릭 JsonPath 추출 & Fail-Fast**
  - Jayway `JsonPath`로 응답 값을 추출해 다음 스텝으로 전달하며, **`[? 가이드]`** 팝업과 우측 `응답` 탭 우측 정렬 **`[응답 변수 추출]`** 버튼 / 응답 JSON 우클릭(**`[🌶️ 이 필드를 추출 변수로 추가]`**)으로 JsonPath를 자동 계산해 등록합니다.
  - 네트워크 오류, 기대 상태 코드 불일치, 필수 추출 실패 시 즉시 `ABORTED`로 중단(Fail-Fast)하고 요청/응답 전문과 상세 로그를 출력합니다.
- **📋 cURL 복사, 반응형 툴바, `KO / EN` 전환 & 🚀 새 버전 자동 알림**
  - **`[cURL 복사 ▾]`**로 현재 스텝 단건 cURL 또는 전체 시나리오 순차 실행 스크립트(활성 `Params`·동적/추출/전역 변수 치환 반영)를 즉시 복사할 수 있습니다.
  - `WrapLayout` 반응형 툴바와 재시작 없는 **`[KO / EN]`** 언어 전환을 제공하며, IDE 시작 5초 후 백그라운드로 최신 버전을 자동 확인해 우측 하단 알림 및 상단 **`[🔄 업데이트 확인]` → `[🚀 vX.Y.Z 업데이트]`** 배지 버튼으로 IntelliJ 내장 업데이터를 즉시 호출합니다.

### ⏱️ 내장 동적 변수 레퍼런스 (`{{$...}}`)

URL, `Params`, `Headers`, `Body (JSON)` 어디서나 사용할 수 있으며, 요청 실행 및 cURL 내보내기 직전마다 실시간으로 평가됩니다.

| 변수명 | 설명 | 실시간 출력 예시 |
| --- | --- | --- |
| `{{$isoTimestamp}}` | UTC 기준 현재 ISO-8601 시각 | `2026-11-19T05:56:00.123Z` |
| `{{$timestamp}}` | 현재 Unix Epoch 초 (10자리) | `1795067760` |
| `{{$timestampMs}}` | 현재 Unix Epoch 밀리초 (13자리) | `1795067760123` |
| `{{$kstNow}}` | KST(`Asia/Seoul`) 기준 `yyyy-MM-dd HH:mm:ss` | `2026-11-19 14:56:00` |
| `{{$kstDate}}` | KST 기준 날짜 `yyyy-MM-dd` | `2026-11-19` |
| `{{$kstCompact}}` | KST 기준 숫자 타임스탬프 `yyyyMMddHHmmss` (거래/주문번호용) | `20261119145600` |
| `{{$kstIso}}` | KST 기준 ISO-8601 오프셋 포맷 (`+09:00`) | `2026-11-19T14:56:00+09:00` |
| `{{$uuid}}` | 무작위 UUID v4 문자열 | `550e8400-e29b-41d4-a716-446655440000` |

> **변수 치환 우선순위:** 1순위 내장 동적 변수(`{{$...}}`) > 2순위 현재 시나리오 추출 변수(실행 컨텍스트) > 3순위 로컬 시크릿(`.maekom/secrets.local.json`) & 프로젝트 전역 변수(`.maekom/global-variables.json`)

### 🛠️ 설치 및 사용 가이드 (한국어)

1. [Latest Release](https://github.com/maekom-hub/maekom-api-runner/releases/latest) 또는 아래 버전 히스토리에서 `.zip` 파일을 다운로드합니다.
2. IntelliJ IDEA에서 **`Settings / Preferences` → `Plugins` → 톱니바퀴(⚙️) → `Install Plugin from Disk...`** 를 선택해 `.zip` 파일을 설치하고 IDE를 재시작합니다.
3. 재시작 시 플러그인이 Custom Plugin Repository URL을 자동 등록하므로, 이후 새 버전 출시 시 우측 하단 알림 또는 상단 **`[🚀 vX.Y.Z 업데이트]`** 버튼을 클릭해 `Plugins` 메뉴의 **`[Update]`** 버튼만으로 간편하게 업데이트할 수 있습니다.
4. Spring 컨트롤러 메서드 옆의 **🌶️ 빨간 고추 아이콘**을 클릭해 단건 실행하거나 시나리오에 스텝을 추가해 바로 테스트를 시작하세요.

---

## 🇺🇸 English

**Maekom API Runner** is an open-source IntelliJ IDEA Community / Ultimate (`2024.3+`) plugin designed for Spring Boot developers — combining PSI-based automatic endpoint sampling with multi-step API scenario chaining.

### ✨ Key Features

- **🌶️ One-Click Chili Gutter Marker on Spring Controllers**
  - Automatically displays a crisp red chili icon next to Spring Web handler methods (`@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`, `@RequestMapping`).
  - Click the gutter icon to **Quick Single Run & Open** or **Add Step to Active Scenario** immediately.
- **🤖 Spring `@ModelAttribute` / POJO & `@RequestBody` Auto-Sampler**
  - Recursively inspects `@RequestParam`, `@ModelAttribute`, and unannotated custom DTO / Java `record` parameters (including inherited & nested fields while excluding framework types such as `HttpServletRequest`, `Principal`, `Pageable`, `BindingResult`) into a dedicated **`Params`** tab (`Send | Key | Value | Description`), automatically pulling descriptions from Javadoc, `@Schema`, and `@Parameter`.
  - Generates pretty-printed JSON request body samples for `@RequestBody` DTOs & Java `record`s (max depth 4, cycle-safe) and auto-detects `server.port` from `application.yml` / `application.properties` to configure `BaseURL`.
- **🗂️ Native ToolWindow Title Bar Integration, Unified Scenario Management, Persistent Environment Profiles (`Local/DEV/STG/PRD`) & Smart Auto-Save**
  - **Native ToolWindow Title Bar:** Eliminates duplicate header rows by mounting **`[Check Updates]`** and the one-click **`[ 한국어 | English ]` pill switch** directly next to IntelliJ's native `Maekom API` title (`setTabActions`), and real-time **Execution Status | Duration | Save State** directly to the left of the `⋮` / `—` window icons (`setTitleActions`) for maximum vertical workspace.
  - Streamlined toolbar ordered logically: **`Scenario → Save → Scenarios ▾ (New/Rename/Duplicate/Delete/Import/Export) → Common Headers → Variables → Copy cURL → Run Scenario → Stop`**.
  - Persistent **Environment Profiles** (`.maekom/environments.json`) placed directly above the HTTP Method/URL bar (`[Local ▾] → BaseURL → [Ignore SSL] → [⚙]`) let you switch `Local` (auto-detected `server.port`) ↔ `DEV` / `STG` / `PRD` instantly without resetting across scenario switches.
  - **`[📥 Import from Another Scenario]`** in Common Headers (Merge/Overwrite preview) and **`[📥 Bulk JSON Import]`** (supports both JSON objects `{...}` and arrays `[{...}]`) across **Project Global Variables**, **Scenario Variables**, and **Local Secrets** with automatic duplicate validation.
  - Lightweight `Alarm`-based **Smart Auto-Save** debounces 30 seconds after edits only when dirty (zero disk I/O while idle) and displays the last auto-saved timestamp (`Auto-saved HH:mm`).
- **🌐 3-Tab Variables Hub (`{{$}} Variables`), Global Variables CRUD & Duplicate Validator**
  - Manage **① Project-wide Persistent Global Variables (`.maekom/global-variables.json` with Secret masking, preserved across scenario switches)**, **② 8 Built-in Live Dynamic Variables**, and **③ Current Scenario Extracted Variables** in one non-modal dialog.
  - A shared **`VariableNameValidator`** guards against collisions across Global Variables, all scenario extract rules, and reserved dynamic prefixes (`$...`), automatically recommending safe names (parent JSON key prefix or `_2`, `_3` suffix).
  - Right-click in URL, `Params` Value, `Headers` Value, or `Body (JSON)` to insert dynamic, extracted, or global variables directly at the caret position.
- **🔗 Multi-Step Chaining, One-Click JsonPath Extraction & Fail-Fast**
  - Extracts response fields using Jayway `JsonPath` into runtime variables (`{{varName}}`), featuring a built-in **`[? Guide]`** popup and right-click **`[🌶️ Add Field to Extract Rules]`** / **`[+ Extract from Response]`** reverse-tracing the exact JsonPath automatically.
  - Enforces a strict **Fail-Fast** policy on network errors, status mismatches, or failed required extractions (`ABORTED` + `SKIPPED` remaining steps) with safe non-JSON (HTML/Plain Text) rendering.
- **📋 Copy as cURL, Responsive Toolbar, Built-in `KO / EN` Switch & 🚀 Update Notifications**
  - **`[Copy cURL ▾]`** exports either the active step or the entire multi-step scenario as runnable POSIX `curl` commands with live-resolved variables and enabled `Params`.
  - Uses `WrapLayout` for narrow ToolWindows, switches **Korean ↔ English** instantly without IDE restart, and checks GitHub Releases asynchronously 5 seconds after startup to surface balloon notifications and a highlighted **`[🚀 Update vX.Y.Z]`** toolbar button linked directly to IntelliJ's built-in `UpdateChecker`.

### ⏱️ Built-in Dynamic Variables Reference (`{{$...}}`)

Usable anywhere in URL, `Params`, `Headers`, or `Body (JSON)` and evaluated live immediately before execution or cURL export:

| Variable Token | Description | Live Output Example |
| --- | --- | --- |
| `{{$isoTimestamp}}` | Current UTC ISO-8601 timestamp | `2026-11-19T05:56:00.123Z` |
| `{{$timestamp}}` | Current Unix Epoch seconds (10 digits) | `1795067760` |
| `{{$timestampMs}}` | Current Unix Epoch milliseconds (13 digits) | `1795067760123` |
| `{{$kstNow}}` | KST (`Asia/Seoul`) `yyyy-MM-dd HH:mm:ss` | `2026-11-19 14:56:00` |
| `{{$kstDate}}` | KST date `yyyy-MM-dd` | `2026-11-19` |
| `{{$kstCompact}}` | KST compact digits `yyyyMMddHHmmss` (for order/deal IDs) | `20261119145600` |
| `{{$kstIso}}` | KST ISO-8601 with offset (`+09:00`) | `2026-11-19T14:56:00+09:00` |
| `{{$uuid}}` | Random UUID v4 string | `550e8400-e29b-41d4-a716-446655440000` |

> **Resolution Priority:** 1. Built-in Dynamic (`{{$...}}`) > 2. Scenario Extracted Context > 3. Local Secrets (`.maekom/secrets.local.json`) & Project Global Variables (`.maekom/global-variables.json`)

### 🛠️ Installation & Quick Start (English)

1. Download the `.zip` distribution from [Latest Release](https://github.com/maekom-hub/maekom-api-runner/releases/latest) or the release table below.
2. In IntelliJ IDEA, go to **`Settings / Preferences` → `Plugins` → ⚙️ → `Install Plugin from Disk...`**, select the downloaded `.zip`, and restart the IDE.
3. On startup, the plugin automatically registers its Custom Plugin Repository URL and notifies you whenever a newer release is available — click **`[🚀 Update vX.Y.Z]`** to trigger IntelliJ's built-in **`Update`** action immediately.
4. Click the **🌶️ red chili icon** in the gutter next to any Spring Web controller method to run or chain API requests right inside IntelliJ IDEA.

---

## 📦 버전별 다운로드 및 릴리즈 이력 / Release History

- **최신 버전 바로 다운로드 / Latest Release:** [https://github.com/maekom-hub/maekom-api-runner/releases/latest](https://github.com/maekom-hub/maekom-api-runner/releases/latest)
- **전체 버전 목록 / All Releases:** [https://github.com/maekom-hub/maekom-api-runner/releases](https://github.com/maekom-hub/maekom-api-runner/releases)

<!-- VERSION_HISTORY_START -->
| 버전 | 릴리즈 날짜 | 변경 요약 | 다운로드 |
| --- | --- | --- | --- |
| `v0.2.2` | 2026-11-19 | 중복 커스텀 헤더 행 제거 및 IntelliJ 네이티브 ToolWindow 타이틀 바(`setTabActions`/`setTitleActions`) 직접 통합(`업데이트 확인` + `한국어 \| English` 세그먼트 스위치 / 우측 실시간 실행 상태·소요 시간·저장 상태) / Remove duplicate custom header row and mount Updates, `한국어 \| English` switch, and live execution/save status directly on IntelliJ's native ToolWindow title bar | [maekom-api-runner-0.2.2.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.2.2/maekom-api-runner-0.2.2.zip) |
| `v0.2.1` | 2026-11-19 | 최상단 헤더 바 재구성(`Maekom API` + 업데이트 확인 + `한국어 \| English` 세그먼트 스위치 / 우측 상태·소요·저장 표시줄), `BaseURL` 환경 바 `[⚙]` 톱니바퀴 아이콘 전용화 및 `응답 변수 추출` 버튼 우측 정렬 / Top header bar (`한국어 \| English` pill switch, updates, right-aligned status/duration/save state), compact `[⚙]` env button & right-aligned `Extract Variables` button | [maekom-api-runner-0.2.1.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.2.1/maekom-api-runner-0.2.1.zip) |
| `v0.2.0` | 2026-11-19 | 상단 툴바 전면 재정렬(Import/Export 통합·언어선택 콤보박스), `Local/DEV/STG/PRD` 환경 프로필 영속 관리(`.maekom/environments.json`), 전역 변수·시나리오 변수·로컬 시크릿 JSON 일괄 입력, 전체 실행 완료 상태 멈춤 수정 및 단건 실행 선택 유지·버튼 스타일 통일 / Reordered global toolbar & Language selector, persistent `Local/DEV/STG/PRD` environment profiles, Bulk JSON Import across Global/Scenario/Secret tabs, fix stuck Running status & preserve step selection on single run | [maekom-api-runner-0.2.0.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.2.0/maekom-api-runner-0.2.0.zip) |
| `v0.1.5` | 2026-11-19 | `BaseURL`/`SSL 무시` 요청 편집기 최상단 배치, 우측 결과 패널 3단 탭(`응답` / `추출 변수 (N)` / `실행 및 에러 로그` + 로그 지우기) 분리 및 실행 로그 실시간 갱신·자동 스크롤 수정 / Relocate `BaseURL`+`Ignore SSL` above Method/URL, split right pane into 3 tabs (`Response` / `Extracted Vars (N)` / `Execution & Error Logs`), and fix live log auto-refresh | [maekom-api-runner-0.1.5.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.5/maekom-api-runner-0.1.5.zip) |
| `v0.1.4` | 2026-11-19 | 응답 변수 추출 `[? 가이드]` 팝업 하단 `%s` 노출 버그 수정, `BaseURL` 및 `SSL 무시` 옵션을 요청 편집 영역 최상단(Method/URL 바로 위)으로 재배치 / Fix raw `%s` in `[? Guide]` popup & relocate `BaseURL` + `Ignore SSL` bar directly above Method/URL line | [maekom-api-runner-0.1.4.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.4/maekom-api-runner-0.1.4.zip) |
| `v0.1.3` | 2026-11-19 | 시나리오 관리 드롭다운(생성/이름변경/복제/삭제), 타 시나리오 공통 헤더 가져오기, 30초 스마트 자동 저장, 프로젝트 전역 변수 CRUD, 변수명 중복 검증/추천, 즉시 업데이트 알림 & UI 레이아웃 수정 / Scenario menu, Import common headers, Smart Auto-Save, Global Variables CRUD, duplicate validator, update checker & UI fixes | [maekom-api-runner-0.1.3.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.3/maekom-api-runner-0.1.3.zip) |
| `v0.1.2` | 2026-11-19 | 반응형 툴바(`WrapLayout`), 시나리오 이름 변경(`Rename`), 내장 동적 시간·랜덤 변수(KST/UTC/UUID), 변수 치트시트/우클릭 삽입, 응답 JSON 원클릭 JsonPath 변수 추출 및 단건/전체 시나리오 cURL 복사 / Responsive toolbar, Scenario Rename, Built-in KST/UTC/UUID dynamic vars, one-click Response JSON extraction & Copy as cURL | [maekom-api-runner-0.1.2.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.2/maekom-api-runner-0.1.2.zip) |
| `v0.1.1` | 2026-11-19 | Spring `@ModelAttribute`/POJO 자동 추출 & `Params` 전용 탭, 시나리오 전환 Dirty Check, `KO / EN` 언어 전환 스위치, Custom Plugin Repository 자동 등록 / Spring `@ModelAttribute`/POJO auto-extraction & `Params` tab, Dirty Check, built-in `KO/EN` switch & auto repository registrar | [maekom-api-runner-0.1.1.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.1/maekom-api-runner-0.1.1.zip) |
| `v0.1.0` | 2026-11-19 | 최초 릴리즈 (고추 Gutter 마커, `@RequestBody` 자동 샘플링, 시나리오 체이닝 & Fail-Fast, 3단 분할 ToolWindow UI) / Initial release (Chili Gutter marker, `@RequestBody` auto-sampling, scenario chaining & Fail-Fast, 3-pane ToolWindow UI) | [maekom-api-runner-0.1.0.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.0/maekom-api-runner-0.1.0.zip) |
<!-- VERSION_HISTORY_END -->

---

## 🛠️ 로컬 빌드 방법 / Local Build

JDK 21 환경에서 Gradle Wrapper를 사용해 플러그인 배포 아카이브(`.zip`)를 직접 빌드할 수 있습니다. (Requires JDK 21)

```bash
./gradlew buildPlugin
# Output: build/distributions/maekom-api-runner-<version>.zip
```

---

## 📄 License

This project is licensed under the **Apache License 2.0** — see the [LICENSE](./LICENSE) file for details.

```text
Copyright 2026 maekom-hub
Licensed under the Apache License, Version 2.0
```
