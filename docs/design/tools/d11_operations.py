"""D11 운영 매뉴얼(역할별 흐름). 근거: docs/runbook.md §0~§10 (요약이며 명령·기준의 원문은 runbook.md)."""

from diagram import C, Diagram

ID = "d11-operations"
TITLE = "운영 매뉴얼"
VERSION = "v0.1"
DATE = "2026-10-10"


def build() -> Diagram:
    d = Diagram(1600, 900, "KDMS 운영 매뉴얼 (역할별 흐름)",
                "runbook.md 요약 · D-n 준비 → 적재·동기화(며칠도 가능) → D-day 전환 ①~⑦ → D+n 정리",
                f"KDMS-D11 · {VERSION} · {DATE}")
    d.legend = [("sync", "다음 순서"), ("async", "넘겨 주는 것 · 경고 · 되돌리기")]

    # 단계 머리와 경계(세로 점선)
    for x, label in ((257, "D-n 준비 (§2 ~ §4)"), (600, "D-n 적재 · 동기화 (§5)"),
                     (1065, "D-day 전환 (§6 ~ §8)"), (1478, "D+n 정리 (§10)")):
        d.text(x, 150, label, size=18, bold=True, color=C["navy"], anchor="middle")
    for x in (470, 730):
        d.line(x, 160, x, 790, color=C["sky"], width=1.6, dashed=True)
    d.line(1400, 160, 1400, 586, color=C["sky"], width=1.6, dashed=True)

    # 역할 줄
    d.zone(44, 164, 1512, 196, "DBA", "")
    d.zone(44, 372, 1512, 214, "이관 담당", "", core=True)
    d.zone(44, 598, 1512, 192, "앱 담당 · 판단자", "")

    # DBA
    y = 214
    d.block("d1", 64, y, 190, 116, "원천 준비", ["CDC · 스냅숏 격리", "읽기 전용 로그인"])
    d.block("d2", 270, y, 186, 116, "대상 준비", ["DB · kdms_app", "앱 역할 · 시간대"])
    d.block("d3", 490, y, 220, 116, "캡처 Job 다시 시작", ["경고가 뜰 때만"], tint=True)
    d.block("d5", 1130, y, 170, 116, "객체·권한 배포", ["트리거·뷰·SP"], num="5")
    d.block("d9", 1410, y, 136, 116, "원천 정리", ["CDC 끄기", "로그인 끄기"])

    # 이관 담당
    y = 428
    d.block("m1", 64, y, 190, 120, "설정 · 접속 확인", [".env · kdms.yml", "kdms status"])
    d.block("m2", 270, y, 186, 120, "계획 검토", ["plan --scan", "오류 0 까지"])
    d.block("m3", 490, y, 220, 120, "적재 · 동기화", ["schema → sync → load", "status · 웹으로 감시"], strong=True)
    d.block("m_2", 750, y, 150, 120, "sync 중지", ["Ctrl+C"], num="2")
    d.block("m_3", 920, y, 180, 120, "cutover --yes", ["kdms 가 재는 시간"], num="3", strong=True)
    d.block("m_4", 1130, y, 170, 120, "check --probe", ["실패 0"], num="4")
    d.block("m9", 1410, y, 136, 120, "기록 보관", ["출력 원문", ".env 삭제"])

    # 앱 담당 · 판단자
    y = 646
    d.block("a0", 270, y, 186, 104, "앱 쿼리 결정", ["plan 의 주의 항목"])
    d.block("a1", 750, y, 150, 104, "쓰기 중지", ["다운타임 시작"], num="1")
    d.block("go", 905, y - 8, 180, 120, "6. 계속?", ["판단자"], shape="diamond")
    d.block("a7", 1130, y, 170, 104, "접속을 PG 로", ["다운타임 끝"], num="7", strong=True)
    d.block("back", 1410, y, 136, 104, "되돌리기", ["§8 · 원천으로"], tint=True)

    # 준비
    d.link("d1", "r", "d2", "l")
    d.link("d2", "b", "m1", "t")
    d.link("m1", "r", "m2", "l")
    d.link("m2", "r", "m3", "l")
    d.link("m2", "b", "a0", "t", dashed=True, label="주의 항목")
    # 적재·동기화
    d.link("m3", "t", "d3", "b", dashed=True, label="멈춤 경고")
    d.link("m3", "b", "a1", "t", label="전환 날", b_off=-40, label_at=0.3)
    # 전환
    d.link("a1", "t", "m_2", "b")
    d.link("m_2", "r", "m_3", "l")
    d.link("m_3", "r", "m_4", "l")
    d.link("m_4", "t", "d5", "b")
    # 5 → 6: m_3·m_4 사이 통로로
    gx, gy = 1115, 616
    d.line(1130, 272, gx, 272, color=C["primary"])
    d.line(gx, 272, gx, gy, color=C["primary"])
    d.line(gx, gy, 995, gy, color=C["primary"])
    d.line(995, gy, 995, y - 8, color=C["primary"], arrow=True)
    d.link("go", "r", "a7", "l", label="예")
    # 6 아니오 → 되돌리기: 블록 아래로
    ny = 776
    d.line(995, y + 112, 995, ny, color=C["sky"], dashed=True)
    d.line(995, ny, 1478, ny, color=C["sky"], dashed=True)
    d.line(1478, ny, 1478, y + 104, color=C["sky"], dashed=True, arrow=True)
    d.text(1310, ny - 8, "아니오", size=17, bold=True, color=C["sky"], anchor="middle")
    # 정리
    d.link("d9", "b", "m9", "t", dashed=True)

    d.notes = [
        "다운타임 = ① ~ ⑦ · kdms 가 재는 것은 ③ 뿐이므로 사람 작업(①·②·④·⑤·⑦) 시간을 리허설로 재 둔다",
        "③ 종료 코드 4 거부 · 5 검증 불일치 · 6 반영 시간 초과 → 할 일은 runbook §7 표 · 그 밖의 증상은 §9",
    ]
    return d


EXPLAIN = """
<p>이 그림은 <code>docs/runbook.md</code>(운영 절차서)를 역할별로 한 장에 줄인 것이다. 명령·확인 기준·종료 코드의 원문은 runbook.md 가 기준이고, 이 문서는 누가 언제 무엇을 넘겨 주는지를 본다.</p>

<h2>1. 역할</h2>
<table>
<thead><tr><th>역할</th><th>하는 일</th><th>권한(D10)</th></tr></thead>
<tbody>
<tr><td>DBA(Database Administrator, DB 관리자)</td><td>원천 CDC(Change Data Capture, 변경 데이터 캡처) 켜기·끄기, 로그인·권한, 대상 DB·역할, 전환 뒤 트리거·뷰·SP(Stored Procedure, 저장 프로시저)·권한 배포</td><td>원천 sysadmin, 대상 superuser</td></tr>
<tr><td>이관 담당</td><td><code>kdms</code> 명령 실행, 감시, 기록</td><td>원천 읽기 전용 로그인, 대상 <code>kdms_app</code></td></tr>
<tr><td>앱 담당</td><td>원천 앱 쓰기 중지, 앱 접속을 대상으로 전환, 전환 뒤 앱 확인</td><td>앱 설정</td></tr>
<tr><td>판단자</td><td>전환 ⑥ 에서 계속할지 되돌릴지 정한다</td><td>—</td></tr>
</tbody>
</table>

<h2>2. 단계별 할 일</h2>
<table>
<thead><tr><th>단계</th><th>누가</th><th>하는 일</th><th>다음으로 가는 기준</th><th>runbook</th></tr></thead>
<tbody>
<tr><td rowspan="4">D-n 준비</td><td>DBA</td><td>원천: SQL Agent, 스냅숏 격리, CDC(게이팅 역할), 보존 기간, 이관 로그인, 스키마 동결. 대상: DB 와 <code>kdms_app</code>, 시간대, 앱 역할</td><td><code>kdms status</code> 에 나오는 항목</td><td>§2</td></tr>
<tr><td>이관 담당</td><td><code>.env</code>·<code>kdms.yml</code> 작성, <code>kdms status</code></td><td>원천·대상 접속 OK, CDC 켜짐, 스냅숏 격리 ON, Agent Running, 인코딩 UTF8</td><td>§3</td></tr>
<tr><td>이관 담당 + DBA + 앱 담당</td><td><code>kdms plan --scan</code> 보고서 검토. 오류는 규칙 파일로 결정, 경고는 승인·조치 기록</td><td>오류 0. 규칙 파일·보고서 보관</td><td>§4</td></tr>
<tr><td>앱 담당</td><td>보고서의 <code>== 주의 ==</code>(대소문자, 끝 공백, 바이트 길이 등)를 받아 앱 쿼리 수정 여부 결정</td><td>결정 기록</td><td>§4</td></tr>
<tr><td rowspan="2">D-n 적재·동기화</td><td>이관 담당</td><td><code>schema</code> → <code>sync</code>(창 A, 전환 직전까지 계속) → 워터마크 뒤 <code>load</code>. 죽으면 같은 명령을 다시(이어서 한다)</td><td><code>load</code> 끝에 실패 0개, 지연 0 근처</td><td>§5·§5.1</td></tr>
<tr><td>DBA</td><td><code>캡처 Job 이 N초째 로그를 읽지 않음</code> 경고가 뜨면 SQL Agent·캡처 Job 을 다시 시작</td><td>지연이 다시 줄어듦</td><td>§5.1</td></tr>
<tr><td rowspan="7">D-day 전환</td><td>앱 담당</td><td>① 원천 앱 쓰기 중지(시각 기록 = 다운타임 시작)</td><td>앱 쓰기 0</td><td>§7</td></tr>
<tr><td>이관 담당</td><td>② 창 A 에서 Ctrl+C</td><td>창 A 가 끝남</td><td>§7</td></tr>
<tr><td>이관 담당</td><td>③ <code>kdms cutover --yes</code>: 마지막 반영 → 재적재·인덱스 → 검증 → setval → FK (D09)</td><td>종료 코드 0, 불일치 0</td><td>§7</td></tr>
<tr><td>이관 담당</td><td>④ <code>kdms check --probe</code></td><td>종료 코드 0, 실패 0</td><td>§7</td></tr>
<tr><td>DBA</td><td>⑤ 트리거·뷰·SP·함수 PG 판 배포, 앱 역할 권한</td><td>배포 오류 0</td><td>§7</td></tr>
<tr><td>판단자</td><td>⑥ 계속 / 되돌리기. 되돌리면 원천 쓰기를 다시 열고 <code>kdms sync</code> 로 이어 간다</td><td>계속이면 ⑦</td><td>§7·§8</td></tr>
<tr><td>앱 담당</td><td>⑦ 앱 접속을 대상 PG 로 바꾸고 기동, 핵심 화면 확인(시각 기록 = 다운타임 끝)</td><td>앱 정상</td><td>§7</td></tr>
<tr><td rowspan="2">D+n 정리</td><td>이관 담당</td><td>출력 원문(<code>load</code>·<code>cutover</code>·<code>check</code>)과 시각표 보관, 실행 PC 의 <code>.env</code> 삭제</td><td>—</td><td>§10</td></tr>
<tr><td>DBA</td><td>원천 CDC 끄기(<code>sp_cdc_disable_db</code>), 이관 로그인 비활성화, 안정화 뒤 대상 <code>kdms</code> 스키마를 지울지 결정</td><td>—</td><td>§10</td></tr>
</tbody>
</table>

<h2>3. 되돌리기와 장애</h2>
<ul>
<li>역방향 동기화(대상 → 원천)가 없으므로 깨끗한 되돌리기는 ⑦ 전까지다. 그래서 ⑥ 에서 판단한 뒤 ⑦ 을 한다(runbook §8, D09 §2).</li>
<li>③ 의 종료 코드: 4 시작 거부(이유 출력) → 고치고 다시 ③, 5 검증 불일치 → 그 테이블 <code>load --reset -t</code> 뒤 다시 ③ 또는 되돌리기, 6 마지막 반영 시간 초과 → ① 을 다시 확인하고 다시 ③.</li>
<li>동기화를 멈춘 시간이 CDC 보존 기간을 넘으면 이어 받을 수 없다 → <code>kdms reset --yes</code> 뒤 §5 처음부터(runbook §9).</li>
<li>모든 명령의 종료 코드: 0 정상, 1 설정 오류, 2 접속 실패, 3 계획에 오류, 4 시작 거부, 5 실패, 6 전환 마지막 반영 시간 초과.</li>
</ul>

<h2>4. 시험한 것</h2>
<p>이 순서(§3 ~ §7)를 6단계 MVP 리허설에서 Mac → 노트북 DB 로 2회 처음부터 밟았고 두 번 모두 32/32 합격이었다(rehearsal.md, WORKLOG 2026-10-10, PR #11). ⑤ PG 판 객체 배포와 ⑦ 앱 전환은 리허설 구간(rehearsal.md §1)에 들어 있지 않아 시험하지 않았다.</p>
"""
