# 오픈소스 라이선스 확인

> 상태: 진행 중 · 최종 갱신: 2026-10-09 · a43a5f8 · 근거: Maven Central POM(2026-10-01), license-maven-plugin 보고서(PR #2), PR #6·#9 추가분

KDMS 가 쓸 예정인 오픈소스와 라이선스. [plan.md](plan.md) §1.2 의 구성 기준이다.

- 확인일 **2026-10-01**. 출처는 Maven Central 의 각 라이브러리 POM(Project Object Model, Maven 설정 파일) `<licenses>` 항목(없으면 부모 POM). 버전은 그날의 최신 안정판이다.
- 1단계에서 `license-maven-plugin` 으로 **전이 의존성(간접으로 끌려오는 라이브러리)까지 전부** 자동 보고서를 만들어 이 표를 대체·보완한다. 보고서에 "미확인" 이 하나라도 있으면 빌드를 실패시킨다.
- 이 문서는 법률 검토가 아니다. 고객사 반입 전 고객사 오픈소스 검토 절차를 따른다.

## 1. jar 에 포함되는 것 (배포됨)

| 구성 요소 | Maven 좌표 | 확인 버전 | 라이선스 | 출처 |
|---|---|---|---|---|
| Spring Boot (web, jdbc, thymeleaf, actuator) | `org.springframework.boot:spring-boot-starter-*` | 4.1.1 | Apache-2.0 | 각 starter POM |
| Spring Framework | `org.springframework:spring-core` 외 | 7.0.9 | Apache-2.0 | POM |
| 내장 Tomcat | `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.26 | Apache-2.0 | POM |
| Thymeleaf | `org.thymeleaf:thymeleaf` | 3.1.5.RELEASE | Apache-2.0 | 부모 POM |
| Debezium Embedded / API / Core | `io.debezium:debezium-embedded`, `-api`, `-core` | 3.7.0.Final | Apache-2.0 | 부모 `debezium-build-parent` POM |
| Debezium SQL Server 커넥터 | `io.debezium:debezium-connector-sqlserver` | 3.7.0.Final | Apache-2.0 | 부모 POM |
| Debezium JDBC 저장소(오프셋·스키마 이력) | `io.debezium:debezium-storage-jdbc` | 3.7.0.Final | Apache-2.0 | 부모 POM |
| Debezium 저장소 공통(오프셋 읽기·쓰기) | `io.debezium:debezium-storage-common` | 3.7.0.Final | Apache-2.0 | 부모 POM. storage-jdbc 가 provided 로만 선언해 4단계에서 직접 넣었다(cdc.md §6) |
| Kafka Connect API / Runtime / JSON / Transforms, Kafka Clients | `org.apache.kafka:connect-*`, `kafka-clients` | 4.3.1 (Debezium 3.7 이 쓰는 버전) | Apache-2.0 | POM |
| PostgreSQL JDBC(PgJDBC) | `org.postgresql:postgresql` | 42.7.13 | BSD-2-Clause | POM |
| Microsoft SQL Server JDBC | `com.microsoft.sqlserver:mssql-jdbc` | 13.6.0.jre11 | MIT | POM |
| HikariCP(커넥션 풀) | `com.zaxxer:HikariCP` | 7.1.0 | Apache-2.0 | POM |
| Jackson | `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | Apache-2.0 | POM |
| SnakeYAML | `org.yaml:snakeyaml` | 2.7 | Apache-2.0 | POM |
| picocli(CLI 해석) | `info.picocli:picocli` | 4.7.7 | Apache-2.0 | POM |
| SLF4J | `org.slf4j:slf4j-api` | 2.0.20 | MIT | 부모 POM |
| Logback | `ch.qos.logback:logback-classic` | 1.6.5 | EPL-2.0 / LGPL-2.1 **이중 라이선스** | 부모 POM |
| Micrometer(Actuator 지표) | `io.micrometer:micrometer-core` | 1.17.1 | Apache-2.0 | POM |

### 1.1 Kafka Connect Runtime 이 함께 끌어오는 것 (1단계에서 제외 가능 여부 시험)

| 구성 요소 | 확인 버전 | 라이선스 | 비고 |
|---|---|---|---|
| Jetty (`org.eclipse.jetty:jetty-server` 등) | 12.1.13 | EPL-2.0 / Apache-2.0 이중 | Connect REST 서버용. Embedded 에서는 쓰지 않을 가능성이 크다 → 제외 시도 |
| Jersey (`org.glassfish.jersey.*`) | 4.0.2 | EPL-2.0 / GPL-2.0 with Classpath Exception 외 여러 개 | 위와 같음. 제외 시도. 남으면 개별 검토 |
| jose4j | 0.9.7 | Apache-2.0 | |
| ClassGraph | 4.8.196 | MIT | |
| maven-artifact | 3.9.16 | Apache-2.0 | |
| swagger-annotations | 2.2.55 | Apache-2.0 | |
| jackson-jakarta-rs-json-provider | 2.22.3 | Apache-2.0 | |
| zstd-jni (kafka-clients 압축) | 1.5.7-20 | BSD-2-Clause | 네이티브 라이브러리 포함. 압축을 안 쓰면 제외 시도 |
| lz4-java (kafka-clients 압축) | `org.lz4` 1.8.0 / `at.yawk.lz4` 1.12.0 | Apache-2.0 | Kafka 4.3.1 이 어느 좌표를 쓰는지 1단계 보고서에서 확정 |
| snappy-java (kafka-clients 압축) | 1.1.10.8 | Apache-2.0 | 네이티브 라이브러리 포함 |

위 "확인 버전" 은 그날의 최신판이다. 실제로 jar 에 들어가는 버전은 Kafka 4.3.1 이 고정한 버전이며 1단계 보고서에서 확정한다.

### 1.2 화면(JS·CSS)

MVP 는 외부 프런트엔드 라이브러리를 쓰지 않는다(직접 작성한 작은 JS·CSS, Thymeleaf 서버 렌더링). 나중에 넣게 되면 webjar 로 jar 안에 포함하고 이 표에 추가한다. 참고로 확인한 후보:

| 후보 | 확인 버전 | 라이선스 |
|---|---|---|
| htmx (`org.webjars.npm:htmx.org`) | 4.0.0 | 0BSD |
| Bootstrap (`org.webjars:bootstrap`) | 5.3.8 | Apache-2.0 (webjar POM 기준. 원 프로젝트는 MIT — 넣을 때 원 프로젝트 LICENSE 로 다시 확인) |

## 2. 빌드·시험에만 쓰는 것 (배포되지 않음)

| 구성 요소 | 확인 버전 | 라이선스 | 비고 |
|---|---|---|---|
| JUnit Jupiter | 6.1.3 | EPL-2.0 | 시험 전용 |
| AssertJ | 3.27.7 | Apache-2.0 | 시험 전용 |
| spring-boot-starter-test | 4.1.1 | Apache-2.0 | 시험 전용 |
| license-maven-plugin (`org.codehaus.mojo`) | 2.7.1 | LGPL-3.0 | 빌드 플러그인. 결과물에 포함되지 않는다 |
| cyclonedx-maven-plugin | 2.9.3 | Apache-2.0 | SBOM(Software Bill of Materials, 구성 요소 명세) 생성 |

## 3. 설치본(7단계)에 포함되는 JRE

| 구성 요소 | 라이선스 | 비고 |
|---|---|---|
| OpenJDK 기반 배포판(예: Eclipse Temurin 21) 을 jlink 로 줄인 런타임 | GPL-2.0 with Classpath Exception | Classpath Exception 덕분에 위에서 실행되는 애플리케이션에는 GPL 의무가 없다. 런타임 자체의 라이선스 문서는 설치본에 함께 넣는다. 배포판 선택은 7단계에서 |

## 4. 지켜야 할 것

| 라이선스 | 의무 | KDMS 에서 하는 일 |
|---|---|---|
| Apache-2.0 | 라이선스 전문과 각 프로젝트의 `NOTICE` 를 배포물에 포함. 수정한 파일은 수정 표시 | 수정하지 않는다(포크 금지). jar 의 `META-INF/` 와 설치본 `licenses/` 에 전문·NOTICE 모음 |
| MIT, BSD-2-Clause, 0BSD | 저작권 표시·라이선스 문구 포함(0BSD 는 의무 없음) | 같은 `licenses/` 모음에 포함 |
| EPL-2.0 / LGPL-2.1 (Logback) | 수정 없이 라이브러리로 쓰면 소스 공개 의무 없음. 수정 시 해당 파일 공개 | 수정하지 않는다. EPL-2.0 쪽을 선택한 것으로 기록 |
| EPL-2.0 (Jetty·Jersey, 남는 경우) | 위와 같음 | 가능하면 제외 |
| GPL-2.0 + Classpath Exception (JRE) | 런타임 배포 시 라이선스 문서·소스 입수 방법 안내 | 설치본에 배포판의 라이선스 파일을 그대로 둔다 |

- **Debezium 은 포크하지 않는다.** Maven 의존성으로만 쓰고 확장은 공개 인터페이스(엔진 API, `OffsetBackingStore` 설정 등)로만 한다.
- GPL·AGPL·SSPL 등 강한 카피레프트 라이선스가 jar 에 들어오면 1단계 보고서 검사로 빌드를 실패시킨다(허용 목록: Apache-2.0, MIT, BSD-2/3-Clause, 0BSD, EPL-2.0, LGPL-2.1(이중 라이선스 한정), 그 밖은 개별 승인).

## 5. 1단계 자동 보고서 결과 (2026-10-01)

`license-maven-plugin` 이 `package` 때마다 전이 의존성까지 검사한다(설정: `pom.xml`, `config/license/`).

- 허용 목록: `config/license/allowed-licenses.txt` (Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, 0BSD, EPL-2.0). 이중 라이선스는 하나라도 허용 목록에 있으면 통과(Logback EPL-2.0/LGPL-2.1, Jakarta Annotations EPL-2.0/GPL-2.0+CPE).
- 같은 라이선스의 여러 표기는 `config/license/license-merges.txt` 로 SPDX(Software Package Data Exchange) 식별자 하나로 모은다.
- 라이선스 정보가 없거나 허용 목록 밖이면 빌드 실패. 허용 목록에서 EPL-2.0 을 빼 보면 실패하는 것을 확인했다.
- 결과: jar 안 `META-INF/THIRD-PARTY.txt`(라이선스별 목록), `META-INF/sbom/application.cdx.json`(CycloneDX SBOM).
- 라이선스 전문(全文) 모음(`download-licenses`)은 빌드 중 인터넷이 필요해 7단계 설치본에서 한다.

### 5.1 실제로 들어간 버전 (§1 의 "확인 버전" 과 다른 것)

§1 은 그날 최신판이고, jar 에는 Spring Boot 4.1.1 이 관리하는 버전이 들어간다. 예외는 `kafka.version` 하나(Debezium 기준 4.3.1).

| 구성 요소 | §1 확인 버전 | 실제 | 비고 |
|---|---|---|---|
| mssql-jdbc | 13.6.0.jre11 | 13.4.0.jre11 | Boot 관리. Debezium 3.7 은 12.4.2.jre8 로 시험했다(4단계 실측 대상) |
| Tomcat | 11.0.26 | 11.0.24 | Boot 관리 |
| Jackson 2 (Debezium 용) | 2.22.3 | 2.21.5 | Boot 관리. Debezium 3.7 은 2.21.2. Spring 웹은 Jackson 3(`tools.jackson` 3.1.5) |
| SnakeYAML | 2.7 | 2.6 | Boot 관리 |
| Logback | 1.6.5 | 1.5.38 | Boot 관리 |
| SLF4J | 2.0.20 | 2.0.18 | Boot 관리 |
| ClassGraph | 4.8.196 | 4.8.179 | Kafka 4.3.1 고정 |
| HikariCP, Micrometer core | — | 아직 없음 | 1단계에서는 쓰지 않아 넣지 않았다(필요한 단계에서 추가) |

### 5.2 뺀 것 (pom.xml `exclusions`)

Kafka Connect Runtime 이 끌어오지만 Embedded 엔진에서 쓰지 않는 것. `DebeziumClasspathTest` 가 엔진이 접속 단계까지 가고 스트리밍 단계 클래스가 모두 로드되는지 확인한다. 실제 스트리밍은 4단계에서 확인한다.

| 뺀 것 | 라이선스 | 이유 |
|---|---|---|
| Jetty, Jersey(containers·inject) | EPL-2.0 / Apache-2.0, EPL-2.0 / GPL-2.0+CPE | Connect REST 서버 |
| jakarta.ws.rs-api, jackson-jakarta-rs-*, jackson-module-jakarta-xmlbind-annotations | EPL-2.0 / GPL-2.0+CPE, Apache-2.0 | 위와 같음 |
| javax.xml.bind:jaxb-api 2.3.1, javax.activation 1.1.1·activation-api 1.2.0 | CDDL-1.1 / GPL-2.0+CPE, CDDL-1.0 | 위와 같음. CDDL 은 허용 목록 밖이라 빼는 편이 낫다 |
| jose4j, swagger-annotations | Apache-2.0 | Connect REST 요청 서명·API 문서 |
| zstd-jni, snappy-java, lz4-java | BSD-2-Clause, Apache-2.0, Apache-2.0 | Kafka 메시지 압축(네이티브 라이브러리 포함). 브로커로 보내지 않으므로 쓰지 않는다 |
| Debezium 이 끌어오는 mssql-jdbc | MIT | Boot 관리 버전 하나만 쓴다 |

남아 있는 것 중 쓰지 않을 가능성이 큰 것: `debezium-storage-file`, `debezium-storage-kafka`, `connect-file`(모두 Apache-2.0). 4단계에서 JDBC 저장소로 엔진을 띄운 뒤 빼 볼 수 있다.

## 6. 설계 문서(`docs/design`)에 넣은 것 (jar 에는 들어가지 않음)

확인일 2026-10-06. 출처는 각 npm 패키지의 `package.json`·라이선스 파일. 폐쇄망에서 문서를 열 수 있도록 파일을 저장소에 넣었다.

| 구성 요소 | 버전 | 라이선스 | 넣은 파일 | 의무와 한 일 |
|---|---|---|---|---|
| Pretendard 글꼴 (`pretendard`) | 1.3.9 | OFL-1.1 (SIL Open Font License) | `docs/design/assets/fonts/PretendardVariable.woff2`, `fonts/otf/Pretendard-Regular·Bold.otf`(발표 PC 설치용) | 라이선스 전문을 함께 배포: `fonts/OFL.txt`. 글꼴 단독 판매 금지, 수정하면 이름 변경(수정하지 않는다) |
| Material Symbols 아이콘 (`@material-symbols/svg-400`, Outlined) | 0.47.6 | Apache-2.0 | `docs/design/assets/icons/*.svg` (쓰는 것만) | 라이선스 전문 포함: `icons/LICENSE`. 원본 SVG 를 수정하지 않고 path 만 그림에 넣는다 |

문서 배포본을 만드는 도구(배포물에 포함되지 않음): Chromium 헤드리스(PDF·PNG, BSD-3-Clause 외), python-pptx 1.0.2(PPTX, MIT).
