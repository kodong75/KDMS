"""D10 보안·권한 설계. 근거: docs/runbook.md §0·§2·§10, docs/cdc.md §7, docs/plan.md §7(R1·R8)·§8, .env.example, DEC-09·21·34·40·49, docs/rehearsal.md(T-N01~03)."""

from diagram import Diagram

ID = "d10-security"
TITLE = "보안·권한 설계"
VERSION = "v0.1"
DATE = "2026-10-10"


def build() -> Diagram:
    d = Diagram(1600, 900, "KDMS 보안·권한 설계",
                "원천은 읽기만 · 대상은 superuser 아닌 소유자 · 비밀번호는 .env · 행 값은 남기지 않음 · 외부 연결 0",
                f"KDMS-D10 · {VERSION} · {DATE}")
    d.legend = [("sync", "kdms 접속(JDBC·HTTP)"), ("async", "사람이 권한을 준다")]

    d.zone(44, 196, 420, 598, "원천 · MS-SQL 2019", "kdms 는 읽기만")
    d.zone(490, 196, 620, 598, "실행 PC · kdms.jar", "비밀번호·행 값·외부 연결을 막는 곳", core=True)
    d.zone(1136, 196, 420, 598, "대상 · PostgreSQL 16", "superuser 를 쓰지 않는다")

    # 사람
    d.block("dba_s", 74, 128, 360, 46, "DBA · 원천 sysadmin", icon="admin_panel_settings", pill=True)
    d.block("op", 560, 128, 480, 46, "이관 담당 · 터미널 / 브라우저", icon="person", pill=True)
    d.block("dba_t", 1166, 128, 360, 46, "DBA · 대상 superuser", icon="admin_panel_settings", pill=True)

    # 원천
    d.block("login", 64, 262, 380, 124, "이관 로그인 (읽기 전용)",
            ["db_datareader · kdms_cdc_reader", "VIEW DEFINITION · DB·서버 STATE"],
            icon="badge", strong=True)
    d.block("cdc", 64, 402, 380, 120, "CDC · 스냅숏 격리", ["DBA 가 켬 · 게이팅 역할로 읽기 제한", "보존 기간 = 적재 ~ 전환 + 여유"],
            icon="manage_history")
    d.block("no", 64, 538, 380, 120, "주지 않는 권한", ["sa · sysadmin · 쓰기 · DDL"], icon="lock", tint=True)
    d.block("s_end", 64, 674, 380, 104, "정리 (D+n)", ["CDC 끄기 · 이관 로그인 비활성화"], icon="task_alt")

    # 실행 PC
    d.block("jar", 510, 262, 580, 124, "kdms.jar · JDBC 접속",
            ["원천 1433 · 운영은 인증서 검증(trust_cert=false)", "대상 5432 · sslmode 설정 · 그 밖의 연결 0"],
            icon="deployed_code", strong=True)
    d.block("env", 510, 402, 280, 120, "비밀번호는 .env 만", ["저장소엔 .env.example", "설정은 ${…} 자리표시"], icon="key")
    d.block("web", 810, 402, 280, 120, "웹 화면", ["127.0.0.1 에만 · 인증 없음", "POST 는 X-KDMS-Token"], icon="web")
    d.block("log", 510, 538, 280, 120, "행 값 남기지 않음", ["로그·화면은 PK·건수만", "change_log 반영 즉시 삭제"],
            icon="visibility_off")
    d.block("net", 810, 538, 280, 120, "폐쇄망 실행", ["외부 다운로드 0 · CDN 없음", "T-N01 ~ 03"], icon="wifi_off")
    d.block("lic", 510, 674, 280, 104, "라이선스 · SBOM", ["허용 목록 밖이면 빌드 실패"],
            icon="description")
    d.block("p_end", 810, 674, 280, 104, "정리 (D+n)", [".env 삭제 · 출력 원문 보관"], icon="task_alt")

    # 대상
    d.block("app", 1156, 262, 380, 124, "kdms_app (이관 역할)",
            ["DB 소유자 · 대상 테이블 소유", "superuser · CREATEDB 아님"], icon="badge", strong=True)
    d.block("meta", 1156, 402, 380, 120, "kdms 관리 스키마", ["작업·검증 기록 · 행 값 없음", "change_log 는 반영 뒤 비어 있음"],
            icon="database")
    d.block("role", 1156, 538, 380, 120, "앱 역할 (따로)", ["전환 뒤 GRANT · DML · 시퀀스 USAGE"], icon="person")
    d.block("t_end", 1156, 674, 380, 104, "정리 (D+n)", ["kdms 스키마 DROP 여부 결정"], icon="task_alt")

    # 연결
    d.link("op", "b", "jar", "t", label="명령 · 브라우저", label_at=0.35)
    d.link("jar", "l", "login", "r", label="읽기")
    d.link("jar", "r", "app", "l", label="쓰기")
    d.link("dba_s", "b", "login", "t", dashed=True, a_off=110, b_off=110, label="발급", label_at=0.35)
    d.link("dba_t", "b", "app", "t", dashed=True, a_off=110, b_off=110, label="만들기", label_at=0.35)

    d.notes = [
        "웹 화면은 인증이 없다 → 실행 PC 밖으로 열지 않는다(바인딩 변경은 명시적 설정) · 지우는 명령(reset 등)은 화면에 없다",
        "투입 전 확인: 원천 인증서 검증 켜기, 대상 sslmode 값, 이관 로그인 권한 목록을 고객사 DBA 가 승인",
    ]
    return d


EXPLAIN = """
<h2>1. 계정과 권한</h2>
<table>
<thead><tr><th>계정</th><th>누가 만드나</th><th>권한</th><th>주지 않는 것</th><th>근거</th></tr></thead>
<tbody>
<tr><td>원천 이관 로그인</td><td>원천 DBA</td><td><code>db_datareader</code>, CDC 게이팅 역할 <code>kdms_cdc_reader</code>, <code>cdc</code> 스키마 SELECT, <code>VIEW DEFINITION</code>, <code>VIEW DATABASE STATE</code>, 서버 <code>VIEW SERVER STATE</code>(drain 의 <code>sys.dm_cdc_log_scan_sessions</code>·Agent 상태)</td><td>sa, sysadmin, 쓰기, DDL(Data Definition Language, 정의 언어)</td><td>runbook §2.1 ⑤, cdc.md §7, plan R1</td></tr>
<tr><td>원천 DBA</td><td>—</td><td>sysadmin(또는 RDS(Relational Database Service) 마스터). CDC 켜기·끄기, 스냅숏 격리, 보존 기간, 로그인 발급</td><td>kdms 명령은 실행하지 않는다</td><td>runbook §0·§2.1</td></tr>
<tr><td><code>kdms_app</code></td><td>대상 DBA</td><td>LOGIN, 이관 DB 소유자. <code>kdms</code> 관리 스키마와 대상 테이블을 만들고 소유</td><td>superuser, CREATEDB</td><td>runbook §2.2 ①</td></tr>
<tr><td>앱 역할</td><td>대상 DBA</td><td>전환 뒤(runbook §7 ⑤) 테이블 SELECT·INSERT·UPDATE·DELETE, 시퀀스 USAGE</td><td>테이블 소유권(소유자는 <code>kdms_app</code>)</td><td>runbook §2.2 ③·§7 ⑤</td></tr>
<tr><td>대상 DBA</td><td>—</td><td>superuser. DB·역할 생성, 전환 뒤 트리거·뷰·SP·함수 배포</td><td>—</td><td>runbook §0</td></tr>
</tbody>
</table>
<p>CDC(Change Data Capture, 변경 데이터 캡처)를 켜는 것은 DBA 가 하고, KDMS 로그인은 읽기만 한다. 4단계에서 이 목록으로 동기화가 도는 것을 확인했다(cdc.md §7).</p>

<h2>2. 비밀번호와 접속</h2>
<table>
<thead><tr><th>항목</th><th>원칙</th><th>근거</th></tr></thead>
<tbody>
<tr><td>비밀번호</td><td><code>.env</code> 또는 환경 변수에만. 저장소에는 <code>.env.example</code>(값 비움)만. 설정 파일은 <code>${KDMS_SRC_PASSWORD}</code> 같은 자리표시. 명령줄·로그·<code>runs/</code>·WORKLOG 에 남기지 않는다</td><td>CLAUDE.md §2-3</td></tr>
<tr><td>원천 TLS(Transport Layer Security, 전송 암호화)</td><td>시험은 자체 서명 인증서라 <code>KDMS_SRC_TRUST_CERT=true</code>. 운영은 <code>false</code> + 인증서 신뢰 저장소</td><td>DEC-21, runbook §3</td></tr>
<tr><td>대상 TLS</td><td><code>KDMS_TGT_SSLMODE</code>(예시 값 <code>prefer</code>). 운영 값은 고객사 정책을 따른다(인증서 검증이 필요하면 <code>verify-full</code>, 권장이며 시험하지 않음)</td><td>.env.example</td></tr>
<tr><td>나가는 연결</td><td>원천 1433, 대상 5432 뿐. 실행 중 인터넷 필요 없음</td><td>runbook §1, T-N03</td></tr>
<tr><td>정리</td><td>이관이 끝나면 실행 PC 의 <code>.env</code> 삭제, 원천 이관 로그인 비활성화</td><td>runbook §10</td></tr>
</tbody>
</table>

<h2>3. 개인정보(행 값)</h2>
<ul>
<li>로그·관리 테이블·화면에는 PK 와 건수만 남긴다. 설정으로도 행 값을 켜지 못한다(plan R8).</li>
<li>예외는 <code>kdms.change_log.payload</code>(변경 행 원문). 반영 트랜잭션 안에서 바로 지운다(DEC-34). 그래서 동기화가 따라잡은 상태면 비어 있다.</li>
<li>검증 차이 목록 <code>kdms.verify_row_diff</code> 도 PK 와 차이 구분만 둔다(D05).</li>
<li>정리 뒤 대상에 남는 <code>kdms</code> 기록(<code>job</code>, <code>cutover_run</code>, <code>verify_run</code>, <code>event_log</code>)에는 행 값이 없다. 지울지는 DBA 가 정한다(runbook §10).</li>
</ul>

<h2>4. 웹 화면</h2>
<ul>
<li>기본 <code>127.0.0.1</code> 에만 연다. 인증이 없으므로 LAN 에 열려면 설정을 명시적으로 바꿔야 한다(plan §4.1).</li>
<li>상태를 바꾸는 요청(POST)은 <code>X-KDMS-Token</code> 헤더가 있어야 한다. CSRF(Cross-Site Request Forgery, 사이트 간 요청 위조)를 막는다(DEC-40).</li>
<li>지우는 명령(<code>reset</code>, <code>schema --replace</code>)은 화면에 없다. 터미널에서만 한다(DEC-40).</li>
<li>인증 있는 원격 화면은 MVP 뒤(plan §3.2).</li>
</ul>

<h2>5. 폐쇄망</h2>
<table>
<thead><tr><th>항목</th><th>방법</th><th>확인</th></tr></thead>
<tbody>
<tr><td>외부 다운로드 0</td><td>라이브러리 전부 jar 에. 화면 JS·CSS·글꼴도 CDN(Content Delivery Network, 외부 배포망) 없이(DEC-09)</td><td>T-N01 인터넷 차단 상태 실행, T-N02 jar 안 화면 파일 외부 URL 0, T-N03 실행 중 연결 상대 = 두 DB 뿐. 6단계 리허설 2회 모두 합격(rehearsal.md, PR #11)</td></tr>
<tr><td>차단 방법(시험)</td><td>Mac 방화벽 pf 로 루프백·같은 LAN 만 열고 나머지를 막음(DEC-49). 차단이 저절로 풀리는 문제가 있어 회차마다 확인(issues.md E08)</td><td>rehearsal.md §2</td></tr>
<tr><td>라이선스</td><td><code>license-maven-plugin</code>: 허용 목록 밖이거나 정보 없으면 빌드 실패. 기록은 licenses.md</td><td>빌드</td></tr>
<tr><td>SBOM(Software Bill of Materials, 구성 요소 명세)</td><td><code>cyclonedx-maven-plugin</code> 이 빌드 때 생성</td><td>빌드 산출물</td></tr>
</tbody>
</table>
"""
