"""D02 SW 아키텍처. 근거: docs/plan.md §1.2·§4.1, src/main/java/kdms/*/package-info.java, pom.xml."""

from diagram import C, Diagram

ID = "d02-sw-architecture"
TITLE = "SW 아키텍처"
VERSION = "v0.1"
DATE = "2026-10-06"


def build() -> Diagram:
    d = Diagram(1600, 900, "KDMS SW 아키텍처",
                "Maven 단일 모듈 · 패키지 계층 · 위 계층만 아래 계층을 쓴다",
                f"KDMS-D02 · {VERSION} · {DATE}")

    # 계층(띠). 왼쪽 200px 는 계층 이름, 블록은 x 290 부터
    d.zone(100, 112, 1450, 146, "진입 계층", "같은 기능을\n두 입구로")
    d.zone(100, 272, 1450, 160, "이관 엔진", "단계마다 채운다\n(아래 줄 = 구현 단계)", core=True)
    d.zone(100, 446, 1450, 160, "공통 계층", "모든 엔진이\n함께 쓴다")
    d.zone(100, 620, 1450, 160, "외부 라이브러리", "모두 jar 에 포함\n실행 중 다운로드 0")

    # 진입
    d.block("main", 290, 126, 400, 118, "Kdms (main)", ["단일 jar 시작점", "--no-web 이면 CLI 만"], icon="deployed_code")
    d.block("cli", 710, 126, 400, 118, "kdms.cli", ["picocli 명령 · status · init · web", "plan · load · sync · cutover · verify"], icon="terminal")
    d.block("web", 1130, 126, 400, 118, "kdms.web", ["Spring MVC · Thymeleaf · 상태 REST", "127.0.0.1 에만 바인딩"], icon="web")

    # 이관 엔진 (cdc·apply 사이를 넓혀 점선 흐름을 보인다)
    y, h, w = 291, 122, 186
    d.block("ddl", 290, y, w, h, "kdms.ddl", ["대상 DDL 생성·적용", "2단계"], icon="schema")
    d.block("load", 492, y, w, h, "kdms.load", ["구간 분할 · COPY", "3단계"], icon="database_upload")
    d.block("verify", 694, y, w, h, "kdms.verify", ["건수 · 합계 · 해시", "3단계"], icon="fact_check")
    d.block("cdc", 896, y, w, h, "kdms.cdc", ["Debezium Embedded", "4단계"], icon="sync", strong=True)
    d.block("apply", 1142, y, w, h, "kdms.apply", ["LSN 순서 멱등 반영", "4단계"], icon="published_with_changes", strong=True)
    d.block("cutover", 1344, y, w, h, "kdms.cutover", ["전환 상태 기계", "5단계"], icon="swap_horiz")
    d.link("cdc", "r", "apply", "l", dashed=True)

    # 공통
    y, w = 465, 295
    d.block("catalog", 290, y, w, h, "kdms.catalog", ["원천·대상 메타데이터", "접속 확인 1단계 · 카탈로그 2단계"], icon="manage_search")
    d.block("rules", 605, y, w, h, "kdms.rules", ["kdms-rules.yml 읽기·검사", "타입·값 변환기 선택"], icon="rule_settings")
    d.block("state", 920, y, w, h, "kdms.state", ["관리 테이블 · 작업 상태 기계", "pg_advisory_lock 동시 실행 방지"], icon="account_tree")
    d.block("config", 1235, y, w, h, "kdms.config", ["YAML 설정 · .env 치환", "JDBC 연결"], icon="settings")

    # 외부 라이브러리 (버전은 licenses.md §5.1 기준)
    y, w = 639, 235
    d.block("boot", 290, y, w, h, "Spring Boot 4.1", ["내장 Tomcat · MVC", "Thymeleaf"], icon="package_2")
    d.block("dbz", 541, y, w, h, "Debezium 3.7", ["Embedded · SQL Server", "JDBC 오프셋 저장소"], icon="extension")
    d.block("jdbc", 792, y, w, h, "JDBC 드라이버", ["PgJDBC", "mssql-jdbc"], icon="cable")
    d.block("picocli", 1043, y, w, h, "picocli 4.7", ["명령줄 해석"], icon="terminal")
    d.block("yaml", 1294, y, w, h, "SnakeYAML · Logback", ["설정 읽기 · 로그"], icon="description")

    # 왼쪽 세로 화살표: 의존 방향(위 → 아래)
    d.line(70, 120, 70, 360, color=C["primary"], width=2.4)
    d.line(70, 500, 70, 776, color=C["primary"], width=2.4, arrow=True)
    for i, ch in enumerate("의존방향"):
        d.text(70, 392 + i * 30, ch, size=19, bold=True, color=C["primary"], anchor="middle")

    d.notes = [
        "점선: CDC 가 대상 PG 의 kdms.change_log 에 쌓고 반영기가 따로 읽는다(수집과 반영 분리)",
        "강조: 무중단의 핵심(CDC 수집·반영) · 엔진 사이 순서는 kdms.state 의 작업 상태로 정한다",
    ]
    return d


EXPLAIN = """
<h2>1. 계층과 패키지</h2>
<table>
<thead><tr><th>계층</th><th>패키지</th><th>책임</th><th>구현 단계</th></tr></thead>
<tbody>
<tr><td>진입</td><td><code>kdms</code> (Kdms.java)</td><td>jar 시작점. 명령을 해석해 CLI 로 실행하거나 내장 웹을 띄운다</td><td>1단계</td></tr>
<tr><td>진입</td><td><code>kdms.cli</code></td><td>picocli 명령. 지금 <code>status</code>·<code>init</code>·<code>web</code>, 단계마다 <code>plan</code>·<code>load</code>·<code>sync</code>·<code>cutover</code>·<code>verify</code> 추가</td><td>1단계~</td></tr>
<tr><td>진입</td><td><code>kdms.web</code></td><td>Thymeleaf 화면 + 직접 작성한 JS·CSS(외부 CDN 없음), 상태 REST(Representational State Transfer, 상태 조회·조작 API). 기본 <code>127.0.0.1</code></td><td>1단계~</td></tr>
<tr><td>엔진</td><td><code>kdms.ddl</code></td><td>대상 DDL 생성·적용: 테이블, PK, UNIQUE, 시퀀스, 나중에 켤 FK</td><td>2단계</td></tr>
<tr><td>엔진</td><td><code>kdms.load</code></td><td>전체 적재: 구간 분할, SNAPSHOT 격리로 읽기, PgJDBC <code>CopyManager</code> 로 COPY, 구간 상태 기록</td><td>3단계</td></tr>
<tr><td>엔진</td><td><code>kdms.verify</code></td><td>건수·합계·해시 SQL 생성(원천 T-SQL / 대상 SQL), 비교, 행 단위 차이</td><td>3단계</td></tr>
<tr><td>엔진</td><td><code>kdms.cdc</code></td><td>Debezium Embedded 엔진 수명주기, 이벤트 → <code>kdms.change_log</code> 저장</td><td>4단계</td></tr>
<tr><td>엔진</td><td><code>kdms.apply</code></td><td><code>change_log</code> → 대상 테이블 멱등 적용, 반영 위치 기록</td><td>4단계</td></tr>
<tr><td>엔진</td><td><code>kdms.cutover</code></td><td>전환 상태 기계: 캡처 지연 확인 → 마지막 반영 0건 2회 → 검증 → <code>setval</code> → FK</td><td>5단계</td></tr>
<tr><td>공통</td><td><code>kdms.catalog</code></td><td>원천·대상 메타데이터. 1단계는 접속·준비 상태(<code>status</code>), 2단계부터 <code>sys.*</code> 카탈로그</td><td>1·2단계</td></tr>
<tr><td>공통</td><td><code>kdms.rules</code></td><td>변환 규칙 파일 읽기·검사, 원천 컬럼 → 대상 타입·값 변환기 선택</td><td>1·2단계</td></tr>
<tr><td>공통</td><td><code>kdms.state</code></td><td>관리 테이블(<code>kdms</code> 스키마) 생성·접근, 작업 상태 기계, <code>pg_advisory_lock</code></td><td>1단계~</td></tr>
<tr><td>공통</td><td><code>kdms.config</code></td><td>YAML 설정 읽기·검사, <code>.env</code>·환경 변수 치환, JDBC 연결</td><td>1단계</td></tr>
</tbody>
</table>

<h2>2. 규칙</h2>
<ul>
<li>의존은 위에서 아래로만: 진입 → 엔진 → 공통 → 라이브러리. 엔진 사이 순서는 <code>kdms.state</code> 의 작업 상태로 정한다(예: 전체 적재가 끝난 테이블부터 반영기가 시작, plan §4.4). 전환(<code>kdms.cutover</code>)은 반영·검증을 차례로 부른다(plan §4.5).</li>
<li>Maven 단일 모듈, 패키지로만 나눈다(plan §4.1). 외부 라이브러리는 모두 jar 에 넣고, 라이선스는 빌드 때 자동 검사한다(licenses.md §5).</li>
<li>Debezium 은 포크하지 않고 Maven 의존성과 공개 인터페이스로만 쓴다. Kafka Connect REST 서버용 Jetty·Jersey 등은 뺐다(licenses.md §5.2).</li>
<li>화면은 외부 프런트엔드 라이브러리 없이 서버 렌더링. 웹과 CLI 는 같은 엔진을 부른다.</li>
</ul>
"""
