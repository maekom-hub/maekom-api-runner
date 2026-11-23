# 🌶️ Maekom API Runner

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](./LICENSE)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ_IDEA-2024.3%2B-orange.svg)](https://www.jetbrains.com/idea/)
[![JVM](https://img.shields.io/badge/JVM-21-brightgreen.svg)](https://adoptium.net/)

> **IntelliJ IDEA 내장 다단계 API 시나리오 체이닝 & 실행기** — Spring Boot PSI 자동 샘플링과 폴더 기반 시나리오 트리를 하나로 제공합니다.
> *Built-in multi-step API scenario chaining & runner for IntelliJ IDEA with Spring Boot PSI auto-sampling and folder-based scenario management.*

<p align="center">
  <b><a href="#-한국어-korean">🇰🇷 한국어</a></b> &nbsp;|&nbsp;
  <b><a href="#-english">🇺🇸 English</a></b> &nbsp;|&nbsp;
  <b><a href="#-릴리즈-이력--release-history">📦 Releases</a></b> &nbsp;|&nbsp;
  <b><a href="https://github.com/maekom-hub/maekom-api-runner/releases/latest">⬇️ Latest</a></b>
</p>

---

## 🇰🇷 한국어 (Korean)

### ✨ 핵심 기능

| 영역 | 주요 기능 |
| --- | --- |
| **다단계 API 시나리오 체이닝** | Spring 컨트롤러 고추(🌶️) Gutter 아이콘으로 `@RequestParam`/`@ModelAttribute`/`@RequestBody` 자동 샘플링 후 멀티 스텝 실행, Jayway `JsonPath` 원클릭 응답 변수 추출 및 Fail-Fast 중단 지원 |
| **환경 프로필 & SSL 무시** | `Local`(`application.yml`/`properties` 포트 자동 감지)과 `DEV`/`STG`/`PRD` 영속 프로필(`.maekom/environments.json`) 즉시 전환 및 로컬 자가서명 HTTPS `SSL 무시` 지원 |
| **계층형 변수 & 공통 헤더 관리** | 8종 내장 동적 변수(`{{$kstNow}}`, `{{$kstCompact}}`, `{{$uuid}}` 등), 응답 추출 변수, 프로젝트 전역 변수(`.maekom/global-variables.json`), 시나리오 변수, 로컬 시크릿(`.maekom/secrets.local.json`) 통합 관리 + **JSON 일괄 입력** 및 변수명 중복 검증 |
| **폴더 기반 시나리오 트리 관리** | `.maekom/scenarios/` 하위 폴더·시나리오 트리 팝업(`Scenario Manager`), **마우스 드래그 앤 드롭** 이동/정렬(폴더 자체 이동 및 빈 여백 루트 이동 지원), `Base Info`(`Name`/`Description`) 편집, 복제·삭제·Import/Export 및 30초 스마트 자동 저장 |
| **원클릭 cURL & 한/영 전환** | **`[cURL]`** 통합 다이얼로그(실시간 내보내기 미리보기·시크릿 마스킹 복사 + 멀티라인 스마트 가져오기), IntelliJ 네이티브 ToolWindow 타이틀 바의 **`[한국어 \| English]`** 무재시작 전환 및 **`[업데이트 확인]`**(`Settings > Plugins` 즉시 연결) |

### ⏱️ 주요 내장 동적 변수 (`{{$...}}`)

`{{$isoTimestamp}}` · `{{$timestamp}}` · `{{$timestampMs}}` · `{{$kstNow}}` · `{{$kstDate}}` · `{{$kstCompact}}` · `{{$kstIso}}` · `{{$uuid}}`
*(치환 우선순위: 내장 동적 변수 > 시나리오 추출 변수 > 로컬 시크릿 > 프로젝트 전역 변수)*

### 🚀 빠른 시작 (Quick Start)

1. **설치:** [Latest Release](https://github.com/maekom-hub/maekom-api-runner/releases/latest)에서 `.zip`을 받아 IntelliJ IDEA **`Settings → Plugins → ⚙️ → Install Plugin from Disk...`** 로 설치 후 재시작합니다.
2. **요청 추가:** Spring 컨트롤러 메서드 옆 **🌶️ 고추 아이콘**을 클릭해 단건 실행하거나 현재 시나리오에 스텝을 추가합니다.
3. **체이닝 & 관리:** 응답 JSON에서 필드를 추출(`{{변수명}}`)해 다음 스텝에 연결하고, **`[시나리오 관리]`** 팝업에서 폴더별로 시나리오를 정리하세요.

### 🆕 `v0.4.0` 최신 업데이트

- **`[cURL]` 통합 다이얼로그 (`CurlManagerDialog`):** 단건/전체 시나리오 실시간 cURL 미리보기(변수 치환·시크릿 마스킹 토글) 및 멀티라인 `cURL` 스마트 가져오기(Query String 분리, BaseURL 자동 제거, 중복 공통 헤더 자동 제외, 새 스텝 추가/현재 스텝 덮어쓰기) 지원
- **응답 본문 검증 (`No-Code Assertion`):** `응답 검증 (N)` 탭 및 규칙 추가 팝업으로 8종 JSONPath 연산자(`==`, `!=`, `contains`, `exists`, `not_exists`, `>`, `<`, `length > 0`)와 기대값 변수 치환 검증 지원
- **단건 실행 선행 변수 누락 보호 & `추출 변수` 수동 Override:** 미정의 `{{변수}}` 감지 시 `[1~N번 순차 실행 / 강제 실행 / 취소]` 안내 및 `추출 변수` 탭 `Value` 더블클릭 임시 수정 지원

---

## 🇺🇸 English

### ✨ Key Features

- **Multi-Step API Chaining & Auto-Sampling:** One-click chili (🌶️) gutter markers on Spring controllers auto-sample `@RequestParam`/`@ModelAttribute`/`@RequestBody` DTOs & records, chain steps with one-click `JsonPath` extraction, and enforce Fail-Fast execution.
- **Persistent Environment Profiles & Ignore SSL:** Switch `Local` (auto-detected `server.port`) and `DEV`/`STG`/`PRD` profiles (`.maekom/environments.json`) across scenarios with self-signed `Ignore SSL` support.
- **Layered Variables & Common Headers:** Manage 8 live dynamic variables (`{{$kstNow}}`, `{{$uuid}}`, etc.), extracted variables, project Global Variables (`.maekom/global-variables.json`), Scenario Variables, and Local Secrets (`.maekom/secrets.local.json`) with **Bulk JSON Import** and duplicate validation.
- **Folder-Based Scenario Tree Manager:** Organize scenarios in nested folders under `.maekom/scenarios/` (100% compatible with legacy root JSON files) with **Drag & Drop** moves/reordering (both folders & scenarios), `Base Info` (`Name`/`Description`) editing, Import/Export, and 30s Smart Auto-Save.
- **One-Click cURL & Instant KO/EN Switch:** Copy single-step or full-scenario `curl` scripts, toggle **`[한국어 | English]`** live on IntelliJ's native ToolWindow title bar, and jump directly to `Settings > Plugins` via **`[Check Updates]`**.

### 🚀 Quick Start

1. **Install:** Download `.zip` from [Latest Release](https://github.com/maekom-hub/maekom-api-runner/releases/latest) → IntelliJ **`Settings → Plugins → ⚙️ → Install Plugin from Disk...`** → Restart IDE.
2. **Capture Endpoints:** Click the **🌶️ chili icon** next to any Spring controller method to run immediately or append to your scenario.
3. **Chain & Organize:** Extract JSON response values into `{{variables}}` for subsequent steps and organize scenarios into folders via **`[Scenarios]`**.

### 🆕 What's New in `v0.4.0`

- **Unified `[cURL]` Dialog (`CurlManagerDialog`):** Live Export preview (step/scenario toggle, variable resolution & Secret masking options) + Smart Import parser (bash `\` & cmd `^` multiline, auto-split query params, auto-strip matching BaseURL, auto-exclude duplicate common headers).
- **No-Code Response Assertions (`Assertions (N)`):** Validate JSON responses with 8 JSONPath operators (`==`, `!=`, `contains`, `exists`, `not_exists`, `>`, `<`, `length > 0`) and variable substitution in expected values.
- **Missing Variable Guard & Manual Override:** Detects undefined `{{var}}` references before single-step runs (offering sequential `1..N` run or force run) and lets you double-click `Value` cells in `Extracted Vars` for memory-only debugging overrides.

---

## 📦 릴리즈 이력 / Release History

- **Latest Release:** [https://github.com/maekom-hub/maekom-api-runner/releases/latest](https://github.com/maekom-hub/maekom-api-runner/releases/latest)

<!-- VERSION_HISTORY_START -->
| 버전 | 릴리즈 날짜 | 변경 요약 | 다운로드 |
| --- | --- | --- | --- |
| `v0.4.0` | 2026-11-23 | `[cURL]` 통합 다이얼로그(실시간 내보내기 미리보기·마스킹 + 스마트 가져오기 파서), 응답 본문 검증(`No-Code Assertion` 8종 연산자), 단건 실행 선행 변수 누락 보호 및 `추출 변수` 수동 Override / Unified `[cURL]` Export/Smart Import dialog, No-Code JSONPath Assertions, Missing Variable Guard & Extracted Vars manual override | [maekom-api-runner-0.4.0.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.4.0/maekom-api-runner-0.4.0.zip) |
| `v0.3.3` | 2026-11-23 | 요청 바 2줄 재배치(1행: Method·URL·단건 실행 / 2행: 스텝 별칭·기대 상태·대기 ms)로 Path 입력창 가로 가독성 개선 / 2-row Request Bar layout (`Method`/`URL`/`Run Step` on row 1, `Alias`/`Expected`/`Delay(ms)` on row 2) | [maekom-api-runner-0.3.3.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.3.3/maekom-api-runner-0.3.3.zip) |
| `v0.3.2` | 2026-11-20 | `[Resolved Request]` 시크릿 마스킹 로그 출력, 스텝 별칭(`Alias`), 실행 전 대기 시간(`Delay ms`, 중지 시 즉시 취소) 및 `PRD` 환경 보호 모드(붉은 강조·실행 전 확인 팝업) / `[Resolved Request]` masked logging, Step `Alias`, cancellable `Delay (ms)` & `PRD` environment protection mode | [maekom-api-runner-0.3.2.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.3.2/maekom-api-runner-0.3.2.zip) |
| `v0.3.1` | 2026-11-20 | 개별 스텝 헤더 빈 값(`null`/공백) 시 활성 공통 헤더 자동 폴백 병합(단건/전체 실행·cURL 일관 적용) 및 `시나리오 관리` 확인 버튼명(`확인`/`OK`) 정리 / Auto-fallback to enabled Common Headers when step header value is blank across runner & cURL, and simplify Scenario Manager OK button label | [maekom-api-runner-0.3.1.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.3.1/maekom-api-runner-0.3.1.zip) |
| `v0.3.0` | 2026-11-20 | 폴더·시나리오 트리 관리(`Scenario Manager`), 폴더/시나리오 양방향 드래그 앤 드롭, `Base Info`(`Name`/`Description`) 편집, 기존 루트 JSON 100% 하위 호환 및 `업데이트 확인` → `Settings > Plugins` 열기 수정 / Folder-based Scenario Manager with Drag & Drop, `Base Info` editor, 100% legacy compatibility & `Settings > Plugins` update button fix | [maekom-api-runner-0.3.0.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.3.0/maekom-api-runner-0.3.0.zip) |
| `v0.2.2` | 2026-11-19 | 중복 커스텀 헤더 제거 및 IntelliJ 네이티브 ToolWindow 타이틀 바(`setTabActions`/`setTitleActions`) 직접 통합 / Mount title bar controls directly on IntelliJ native ToolWindow header | [maekom-api-runner-0.2.2.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.2.2/maekom-api-runner-0.2.2.zip) |
| `v0.2.1` | 2026-11-19 | `한국어 \| English` 세그먼트 스위치, `[⚙]` 환경 아이콘 버튼화 및 우측 정렬 `응답 변수 추출` 버튼 / Pill KO/EN switch, compact `[⚙]` env button & right-aligned Extract button | [maekom-api-runner-0.2.1.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.2.1/maekom-api-runner-0.2.1.zip) |
| `v0.2.0` | 2026-11-19 | `Local/DEV/STG/PRD` 환경 프로필 영속 관리, JSON 일괄 입력, 상단 툴바 재정렬 및 실행 상태/선택 유지 버그 수정 / Persistent env profiles, Bulk JSON Import, toolbar redesign & runner fixes | [maekom-api-runner-0.2.0.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.2.0/maekom-api-runner-0.2.0.zip) |
| `v0.1.5` | 2026-11-19 | 요청 편집기 상단 `BaseURL`/`SSL 무시` 배치, 결과 패널 3단 탭 분리 및 실시간 실행 로그 자동 스크롤 수정 / Relocate BaseURL/SSL bar, 3-tab result pane & live execution log refresh | [maekom-api-runner-0.1.5.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.5/maekom-api-runner-0.1.5.zip) |
| `v0.1.4` | 2026-11-19 | `[? 가이드]` 팝업 `%s` 노출 버그 수정 및 `BaseURL` 재배치 / Fix `%s` in Guide popup & relocate BaseURL | [maekom-api-runner-0.1.4.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.4/maekom-api-runner-0.1.4.zip) |
| `v0.1.3` | 2026-11-19 | 30초 스마트 자동 저장, 프로젝트 전역 변수 CRUD, 변수명 중복 검증기, 공통 헤더 가져오기 & 업데이트 알림 / Smart Auto-Save, Global Variables CRUD, duplicate validator & update alerts | [maekom-api-runner-0.1.3.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.3/maekom-api-runner-0.1.3.zip) |
| `v0.1.2` | 2026-11-19 | 내장 동적 변수 8종, 원클릭 응답 JsonPath 추출, cURL 복사 & 반응형 툴바 / Dynamic variables, one-click JsonPath extraction, Copy as cURL & responsive toolbar | [maekom-api-runner-0.1.2.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.2/maekom-api-runner-0.1.2.zip) |
| `v0.1.1` | 2026-11-19 | Spring `@ModelAttribute`/POJO 자동 추출 & `Params` 탭, Dirty Check & `KO/EN` 전환 / `@ModelAttribute`/POJO Params auto-extraction, Dirty Check & KO/EN switch | [maekom-api-runner-0.1.1.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.1/maekom-api-runner-0.1.1.zip) |
| `v0.1.0` | 2026-11-19 | 최초 릴리즈 (고추 Gutter 마커, `@RequestBody` 샘플링, 시나리오 체이닝 & 3단 분할 UI) / Initial release | [maekom-api-runner-0.1.0.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.0/maekom-api-runner-0.1.0.zip) |
<!-- VERSION_HISTORY_END -->

---

## 🛠️ Local Build & License

```bash
./gradlew buildPlugin
# Output: build/distributions/maekom-api-runner-<version>.zip (Requires JDK 21)
```

Licensed under the **Apache License 2.0** — see [LICENSE](./LICENSE) for details.
