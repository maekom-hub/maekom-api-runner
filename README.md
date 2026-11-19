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
- **🔗 멀티 스텝 시나리오 체이닝 & Fail-Fast & 보안**
  - Jayway `JsonPath`로 응답 값을 추출해 다음 스텝의 URL·Params·Headers·Body(`{{변수명}}`)로 자동 전달합니다.
  - 민감정보(`.maekom/secrets.local.json`)와 시나리오 파일(`.maekom/scenarios/*.json`)을 분리 관리하며, 시나리오 전환 시 변경사항 자동 감지(Dirty Check) 및 저장 확인 팝업을 제공합니다.
  - 네트워크 오류, 기대 상태 코드 불일치, 필수 추출 실패 시 즉시 `ABORTED`로 중단(Fail-Fast)하고 요청/응답 전문과 상세 로그를 출력합니다.
- **🌐 플러그인 자체 `KO / EN` 즉시 언어 전환**
  - OS/IDE 로케일과 무관하게 상단 툴바 우측의 **`[KO / EN]`** 스위치로 재시작 없이 모든 UI 텍스트를 즉시 한국어 ↔ 영어로 전환할 수 있습니다.

---

## 📦 다운로드 및 설치

- **최신 버전 바로 다운로드:** [Latest Release](https://github.com/maekom-hub/maekom-api-runner/releases/latest)
- **전체 버전 목록 보기:** [All Releases](https://github.com/maekom-hub/maekom-api-runner/releases)

### IntelliJ IDEA 수동 설치 및 자동 업데이트 안내
1. 아래 버전별 다운로드 링크 또는 [Latest Release](https://github.com/maekom-hub/maekom-api-runner/releases/latest)에서 `.zip` 파일을 다운로드합니다.
2. IntelliJ IDEA에서 **`Settings / Preferences` → `Plugins` → 톱니바퀴(⚙️) → `Install Plugin from Disk...`** 를 선택합니다.
3. 다운로드한 `maekom-api-runner-<버전>.zip` 파일을 선택하고 적용 후 IDE를 재시작합니다.
4. 재시작 시 플러그인이 Custom Plugin Repository URL을 자동으로 등록하므로, 별도의 수동 저장소 URL 설정 없이 이후 새 버전이 출시되면 IntelliJ `Plugins` 메뉴의 **`[Update]`** 버튼만으로 간편하게 업데이트할 수 있습니다.

---

## 📋 버전별 다운로드 및 릴리즈 이력

<!-- VERSION_HISTORY_START -->
| 버전 | 릴리즈 날짜 | 변경 요약 | 다운로드 |
| --- | --- | --- | --- |
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
