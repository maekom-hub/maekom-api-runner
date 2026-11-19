# 🌶️ Maekom API Runner

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](./LICENSE)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ_IDEA-2024.3%2B-orange.svg)](https://www.jetbrains.com/idea/)
[![JVM](https://img.shields.io/badge/JVM-21-brightgreen.svg)](https://adoptium.net/)

**Maekom API Runner**는 Spring Boot 개발자를 위한 IntelliJ IDEA Community / Ultimate (2024.3+) 전용 오픈소스 API 시나리오 러너 및 PSI 자동 샘플러 플러그인입니다.

---

## ✨ 주요 기능

- **🌶️ 고추(Chili) Gutter 아이콘으로 컨트롤러에서 바로 실행**
  - `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`, `@RequestMapping`이 선언된 Spring 컨트롤러 메서드 옆에 빨간 고추 아이콘이 자동 표시됩니다.
  - 클릭 한 번으로 **단건 바로 세팅 및 열기** 또는 **현재 시나리오에 스텝 추가**를 실행할 수 있습니다.
- **🤖 `@ModelAttribute` / POJO & `@RequestBody` 자동 샘플링**
  - `@RequestParam`, `@ModelAttribute` 및 매핑 어노테이션이 생략된 커스텀 DTO / Java `record`의 필드(상속·중첩 필드 포함)를 재귀 탐색해 **`Params` 전용 탭**(`전송 여부 | Key | Value | Description`)으로 자동 추출하며, Javadoc / `@Schema` / `@Parameter` 설명도 함께 채워 넣습니다.
  - `@RequestBody` DTO 및 Java `record`는 순환 참조를 방지하며 포맷팅된 JSON 샘플 본문을 자동 생성하고, `application.yml` / `application.properties`에서 `server.port`를 읽어 `BaseURL`을 자동 세팅합니다.
- **🗂️ 통합 시나리오 관리, 타 시나리오 공통 헤더 가져오기 & 스마트 자동 저장**
  - 상단 **`[시나리오 관리 ▾]`** 드롭다운에서 **새 시나리오 생성 / 이름 변경(중복 방지) / 복제 / 삭제**를 한 곳에서 깔끔하게 관리하고, 시나리오 전환 시 변경사항 자동 감지(Dirty Check) 저장 확인 팝업을 제공합니다.
  - 공통 헤더 설정 창(`활성 | Key | Value | Secret`)의 **`[📥 다른 시나리오에서 가져오기]`** 기능으로 프로젝트 내 다른 시나리오의 공통 헤더를 미리보기 후 **병합(Merge)** 또는 **덮어쓰기(Overwrite)**로 즉시 반영할 수 있습니다.
  - 경량 `Alarm` 기반 **스마트 자동 저장**이 변경(Dirty) 상태일 때만 마지막 수정 후 30초 디바운스로 조용히 저장(유휴 시 디스크 I/O 0)하며, 상태 라벨에 마지막 자동 저장 시각(`자동 저장됨 HH:mm`)을 표시합니다.
- **🌐 3탭 변수 허브 (`{{$}} 변수 도움말`), 프로젝트 전역 변수 CRUD & 변수명 중복 검증**
  - **`[{{$}} 변수 도움말]`** 창에서 **① 프로젝트 영속 전역 변수(`.maekom/global-variables.json`, Secret 마스킹 지원, 시나리오 전환 시에도 유지)** CRUD, **② 8종 내장 동적 변수**, **③ 현재 시나리오 추출 변수**를 한 번에 조회·복사할 수 있습니다.
  - 전역 변수 추가/수정 및 응답 추출 변수 입력(테이블 직접 편집·원클릭 추출 포함) 시 **공통 변수명 중복 검증기(`VariableNameValidator`)**가 전역 변수·시나리오 전체 스텝·내장 예약 접두사(`$...`) 충돌을 자동 검사하고, 부모 JSON 키 조합 또는 순번(`_2`, `_3`) 추천 변수명을 자동 제안·적용합니다.
  - URL·Params(Value)·Headers(Value)·Body(JSON) 입력창에서 우클릭(**`🌶️ 동적/추출 변수 삽입 ▶`**)으로 내장 동적 변수·추출 변수·전역 변수를 커서 위치에 즉시 삽입할 수 있습니다.
- **🔗 멀티 스텝 시나리오 체이닝, 원클릭 JsonPath 추출 & Fail-Fast**
  - Jayway `JsonPath`로 응답 값을 추출해 다음 스텝으로 전달하며, **`[? 가이드]`** 팝업과 우측 응답 JSON 우클릭(**`[🌶️ 이 필드를 추출 변수로 추가]`**) / **`[+ 응답에서 변수 추출]`** 버튼으로 JsonPath를 자동 계산해 등록합니다.
  - 네트워크 오류, 기대 상태 코드 불일치, 필수 추출 실패 시 즉시 `ABORTED`로 중단(Fail-Fast)하고 요청/응답 전문과 상세 로그를 출력합니다.
- **📋 cURL 복사, 반응형 툴바, `KO / EN` 전환 & 🚀 새 버전 자동 알림**
  - **`[cURL 복사 ▾]`**로 현재 스텝 단건 cURL 또는 전체 시나리오 순차 실행 스크립트(활성 `Params`·동적/추출/전역 변수 치환 반영)를 즉시 복사할 수 있습니다.
  - `WrapLayout` 반응형 툴바, 재시작 없는 **`[KO / EN]`** 언어 전환을 제공하며, IDE 시작 5초 후 백그라운드로 최신 버전을 자동 확인해 우측 하단 알림 및 상단 **`[🔄 업데이트 확인]` → `[🚀 vX.Y.Z 업데이트]`** 배지 버튼으로 IntelliJ 내장 업데이터를 즉시 호출합니다.

---

## ⏱️ 내장 동적 변수 레퍼런스 (`{{$...}}`)

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

---

## 📦 다운로드 및 설치

- **최신 버전 바로 다운로드:** [Latest Release](https://github.com/maekom-hub/maekom-api-runner/releases/latest)
- **전체 버전 목록 보기:** [All Releases](https://github.com/maekom-hub/maekom-api-runner/releases)

### IntelliJ IDEA 수동 설치 및 자동 업데이트 안내
1. 아래 버전별 다운로드 링크 또는 [Latest Release](https://github.com/maekom-hub/maekom-api-runner/releases/latest)에서 `.zip` 파일을 다운로드합니다.
2. IntelliJ IDEA에서 **`Settings / Preferences` → `Plugins` → 톱니바퀴(⚙️) → `Install Plugin from Disk...`** 를 선택합니다.
3. 다운로드한 `maekom-api-runner-<버전>.zip` 파일을 선택하고 적용 후 IDE를 재시작합니다.
4. 재시작 시 플러그인이 Custom Plugin Repository URL을 자동 등록하고 새 버전 출시 시 우측 하단 알림 및 툴바 **`[🚀 vX.Y.Z 업데이트]`** 버튼으로 알려주므로, 클릭 한 번으로 IntelliJ `Plugins` 메뉴의 **`[Update]`** 버튼을 즉시 활성화할 수 있습니다.

---

## 📋 버전별 다운로드 및 릴리즈 이력

<!-- VERSION_HISTORY_START -->
| 버전 | 릴리즈 날짜 | 변경 요약 | 다운로드 |
| --- | --- | --- | --- |
| `v0.1.3` | 2026-11-19 | 시나리오 관리 메뉴(생성/이름변경/복제/삭제), 공통 헤더 가져오기, 스마트 자동저장, 전역변수 CRUD, 변수명 중복 검증/추천 및 즉시 업데이트 알림 추가 | [maekom-api-runner-0.1.3.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.3/maekom-api-runner-0.1.3.zip) |
| `v0.1.1` | 2026-11-19 | Spring `@ModelAttribute`/POJO 자동 추출 & `Params` 전용 탭, 시나리오 전환 Dirty Check, `KO / EN` 언어 전환 스위치 추가 | [maekom-api-runner-0.1.1.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.1/maekom-api-runner-0.1.1.zip) |
| `v0.1.0` | 2026-11-19 | 최초 릴리즈 (고추 Gutter 마커, `@RequestBody` 자동 샘플링, 시나리오 체이닝 & Fail-Fast, 3단 분할 ToolWindow UI) | [maekom-api-runner-0.1.0.zip](https://github.com/maekom-hub/maekom-api-runner/releases/download/v0.1.0/maekom-api-runner-0.1.0.zip) |
<!-- VERSION_HISTORY_END -->

---

## 🛠️ 로컬 빌드 방법

JDK 21 환경에서 Gradle Wrapper를 사용해 플러그인 배포 아카이브(`.zip`)를 직접 빌드할 수 있습니다.

```bash
./gradlew buildPlugin
# 결과물: build/distributions/maekom-api-runner-<version>.zip
```

---

## 📄 License

This project is licensed under the **Apache License 2.0** — see the [LICENSE](./LICENSE) file for details.

```text
Copyright 2026 maekom-hub
Licensed under the Apache License, Version 2.0
```
