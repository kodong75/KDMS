# 오픈소스 라이선스 확인

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
