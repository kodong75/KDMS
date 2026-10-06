"""D01 시스템 구성도. 근거: docs/plan.md §1, §4.1~4.5, config/kdms.example.yml."""

from diagram import Diagram

ID = "d01-system-architecture"
TITLE = "시스템 구성도"
VERSION = "v0.1"
DATE = "2026-10-06"


def build() -> Diagram:
    d = Diagram(1600, 900, "KDMS 시스템 구성도",
                "MS-SQL 2019 → PostgreSQL 16 무중단 이관 · 단일 실행 파일(kdms.jar) · 폐쇄망",
                f"KDMS-D01 · {VERSION} · {DATE}")

    # 영역: 원천 / kdms.jar / 대상
    d.zone(50, 290, 290, 480, "원천 · MS-SQL 2019", "JDBC(mssql-jdbc) · TCP 1433")
    d.zone(410, 190, 780, 580, "kdms.jar · 단일 실행 파일", "Spring Boot · Java 17+ · 외부 다운로드 0",
           core=True, align="end")
    d.zone(1260, 290, 290, 480, "대상 · PostgreSQL 16", "JDBC(PgJDBC) · TCP 5432")

    d.pill("op", 470, 118, 410, 44, "운영자 · 브라우저 / 터미널", "person")

    # 1행: 사용 화면·설정
    d.block("web", 434, 256, 232, 92, "내장 웹 화면", ["진행률 · 지연 · 검증 결과"], icon="web")
    d.block("cli", 684, 256, 232, 92, "CLI", ["plan · load · sync · cutover"], icon="terminal")
    d.block("cfg", 934, 256, 232, 92, "설정 · 변환 규칙", ["kdms.yml · kdms-rules.yml"], icon="rule_settings")

    # 2행: 전체 적재
    d.block("s1", 434, 368, 336, 110, "스키마 변환", ["카탈로그 → 대상 DDL (규칙 적용)"], icon="schema", num="1")
    d.block("s2", 830, 368, 336, 110, "전체 적재기", ["SNAPSHOT 읽기 → COPY · 구간 병렬"], icon="database_upload", num="2")

    # 3행: 변경분 동기화(무중단 핵심, 강조)
    d.block("s3", 434, 498, 212, 110, "CDC 수집", ["Debezium Embedded"], icon="sync", num="3", strong=True)
    d.block("buf", 694, 498, 212, 110, "변경 이벤트 버퍼", ["kdms.change_log"], icon="receipt_long")
    d.block("s4", 954, 498, 212, 110, "반영기", ["LSN 순서 · 멱등 반영"], icon="published_with_changes", num="4", strong=True)

    # 4행: 검증·전환·상태
    d.block("s5", 434, 628, 232, 110, "검증", ["건수 · 합계 · 해시 (양쪽)"], icon="fact_check", num="5")
    d.block("s6", 684, 628, 232, 110, "전환 제어", ["마지막 반영 → 검증 → setval"], icon="swap_horiz", num="6")
    d.block("s7", 934, 628, 232, 110, "상태 · 재시작", ["작업 상태 기계 · 이어 하기"], icon="restart_alt", num="7")

    # 원천
    d.block("src", 74, 368, 242, 110, "업무 테이블", ["dbo.* · PK 구간 분할"], icon="table")
    d.block("ct", 74, 498, 242, 110, "CDC 변경 테이블", ["cdc.*_CT · LSN"], icon="manage_history")
    d.block("agent", 74, 628, 242, 110, "SQL Server Agent", ["CDC 캡처 · 정리 Job"], icon="schedule")

    # 대상
    d.block("tgt", 1284, 368, 242, 240, "대상 테이블", ["변환된 스키마", "전환 후 FK · 트리거 켬"], icon="table")
    d.block("meta", 1284, 628, 242, 110, "kdms 관리 스키마",
            ["job · watermark · change_log", "verify_result · 오프셋"], icon="database")

    # 연결선: 실선 = 실시간 질의, 점선 = 비동기 데이터 흐름
    d.link("op", "b", "web", "t", a_off=-125, label="HTTP 127.0.0.1", label_at=0.3)
    d.link("op", "b", "cli", "t", a_off=125, label="명령 실행", label_at=0.3)

    d.link("src", "r", "s1", "l", label="SNAPSHOT 읽기")
    d.link("s1", "r", "s2", "l")
    d.link("s2", "r", "tgt", "l", b_off=-65, label="COPY 병렬")

    d.link("agent", "t", "ct", "b", dashed=True)
    d.link("ct", "r", "s3", "l", dashed=True, label="변경 수집")
    d.link("s3", "r", "buf", "l", dashed=True)
    d.link("buf", "r", "s4", "l", dashed=True)
    d.link("s4", "r", "tgt", "l", dashed=True, b_off=65, label="멱등 반영")

    d.link("s7", "r", "meta", "l", label="상태 기록")

    d.notes = [
        "폐쇄망: 실행 중 외부 접속 0 · 화면 JS·CSS·글꼴 jar 내장 · 웹은 127.0.0.1 에만 연다",
        "작업 상태·워터마크는 대상 PG 한 곳(kdms 스키마)에 저장 → 어느 단계에서 멈춰도 다시 실행하면 이어서 진행",
    ]
    return d


# 다이어그램 아래 설명(HTML). 표의 근거 열은 docs/plan.md 절 번호.
EXPLAIN = """
<h2>1. 구성 요소</h2>
<table>
<thead><tr><th>번호</th><th>구성 요소</th><th>하는 일</th><th>근거</th></tr></thead>
<tbody>
<tr><td>—</td><td>내장 웹 화면 · CLI</td><td>같은 기능을 화면과 명령으로 제공. 웹은 기본 <code>127.0.0.1:8080</code> 에만 열린다(인증 없음)</td><td>plan §3.1, §4.1</td></tr>
<tr><td>—</td><td>설정 · 변환 규칙</td><td>접속·병렬도는 <code>kdms.yml</code>, 자료형·콜레이션·끝 공백 등 변환 결정은 <code>kdms-rules.yml</code>. 비밀번호는 환경 변수·<code>.env</code> 로만</td><td>plan §5</td></tr>
<tr><td>1</td><td>스키마 변환</td><td>원천 카탈로그(<code>sys.*</code>)를 읽어 규칙을 적용한 대상 DDL(Data Definition Language, 정의 언어)을 만든다. FK 는 만들기만 하고 전환 직전에 켠다</td><td>plan §3.1</td></tr>
<tr><td>2</td><td>전체 적재기</td><td>워터마크를 먼저 기록한 뒤 SNAPSHOT 격리 수준으로 읽어 PG <code>COPY</code> 로 쓴다. 테이블·PK 구간 병렬</td><td>plan §4.3</td></tr>
<tr><td>3</td><td>CDC 수집</td><td>CDC(Change Data Capture, 변경 데이터 캡처). Debezium Embedded 가 원천 CDC 변경 테이블을 LSN(Log Sequence Number, 로그 순번) 순으로 읽어 <code>kdms.change_log</code> 에 저장. Kafka 없음</td><td>plan §4.4</td></tr>
<tr><td>—</td><td>변경 이벤트 버퍼</td><td>대상 PG 의 <code>kdms.change_log</code>. 수집과 반영을 나눠, 전체 적재가 길어도 원천 CDC 보존 기간에 묶이지 않는다</td><td>plan §1.2</td></tr>
<tr><td>4</td><td>반영기</td><td>테이블별 전체 적재가 끝난 뒤부터 변경을 LSN 순서로 멱등(여러 번 적용해도 결과가 같음) 적용. 적용과 반영 위치 기록을 한 트랜잭션으로</td><td>plan §4.4</td></tr>
<tr><td>5</td><td>검증</td><td>원천·대상 각각에서 건수·합계·행 해시를 계산해 숫자만 비교. 불일치 시 PK 구간을 좁혀 행 단위 차이 목록</td><td>plan §4.7</td></tr>
<tr><td>6</td><td>전환 제어</td><td>쓰기 중지 확인 → 캡처 지연 확인 → 마지막 반영 0건 2회 → 검증 → <code>setval</code> → FK 켜기</td><td>plan §4.5</td></tr>
<tr><td>7</td><td>상태 · 재시작</td><td>작업 상태 기계, 같은 작업 동시 실행 방지(<code>pg_advisory_lock</code>). 어느 단계에서 멈춰도 다시 실행하면 이어서</td><td>plan §4.1, §4.2</td></tr>
</tbody>
</table>

<h2>2. 연결</h2>
<table>
<thead><tr><th>선</th><th>구간</th><th>내용</th></tr></thead>
<tbody>
<tr><td>실선</td><td>원천 업무 테이블 → 스키마 변환 → 전체 적재기 → 대상 테이블</td><td>JDBC 질의와 <code>COPY</code>. 요청한 쪽이 결과를 바로 받는 흐름</td></tr>
<tr><td>점선</td><td>SQL Server Agent → CDC 변경 테이블 → CDC 수집 → 버퍼 → 반영기 → 대상 테이블</td><td>원천 쓰기와 따로 도는 비동기 흐름. 지연(초~분)이 생기며 화면에 표시한다</td></tr>
<tr><td>실선</td><td>상태 · 재시작 → kdms 관리 스키마</td><td>작업·구간·워터마크·검증 결과를 대상 PG 한 곳에 기록</td></tr>
</tbody>
</table>

<h2>3. 원칙</h2>
<ul>
<li>폐쇄망: 모든 라이브러리를 jar 에 넣고 실행 중 외부 다운로드를 하지 않는다. 화면 JS·CSS·글꼴도 CDN(Content Delivery Network, 외부 배포망) 없이 포함한다.</li>
<li>상태는 대상 PG 한 곳: 별도 파일·메시지 브로커 없이 <code>kdms</code> 스키마에 모은다. Debezium 오프셋도 JDBC 저장소로 같은 곳에 둔다.</li>
<li>원천 권한은 읽기만: CDC 켜기는 DBA 가 하고, KDMS 로그인은 테이블·<code>cdc</code> 스키마 읽기 권한만 쓴다(plan §7 R1).</li>
</ul>
"""
