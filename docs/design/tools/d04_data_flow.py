"""D04 데이터 흐름도. 근거: docs/cdc.md §2·§3·§6, docs/load-verify.md §2, docs/database.md."""

from diagram import Diagram

ID = "d04-data-flow"
TITLE = "데이터 흐름도"
VERSION = "v0.1"
DATE = "2026-10-09"


def build() -> Diagram:
    d = Diagram(1600, 900, "KDMS 데이터 흐름도",
                "전체 적재 경로와 CDC 경로 · 두 경로 모두 같은 값 변환 · 워터마크·오프셋 기록 시점",
                f"KDMS-D04 · {VERSION} · {DATE}")

    d.zone(44, 126, 300, 664, "원천 · MS-SQL 2019", "읽기만 (쓰기 권한 없음)")
    d.zone(370, 126, 834, 664, "kdms.jar", "1 sync 가 워터마크 기록 → 2 load → 3 반영", core=True)
    d.zone(1230, 126, 326, 664, "대상 · PostgreSQL 16", "대상 테이블 + kdms 스키마")

    # 원천
    d.block("src", 66, 220, 256, 120, "업무 테이블", ["dbo.* · PK 구간"], icon="table")
    d.block("ct", 66, 470, 256, 120, "CDC 변경 테이블", ["cdc.*_CT · LSN 순"], icon="manage_history")
    d.link("src", "b", "ct", "t", dashed=True, label="캡처 Job")

    # 1줄: 전체 적재 경로
    y1 = 220
    d.block("r1", 395, y1, 240, 120, "구간 읽기", ["SNAPSHOT · 구간 병렬"], icon="database_upload", num="2")
    d.block("r2", 670, y1, 230, 120, "값 변환", ["CopyValues"], icon="rule_settings")
    d.block("r3", 935, y1, 245, 120, "COPY 적재", ["구간 + DONE 한 트랜잭션"], icon="upload")
    d.link("src", "r", "r1", "l", label="읽기")
    d.link("r1", "r", "r2", "l")
    d.link("r2", "r", "r3", "l")

    # 2줄: CDC 경로
    y2 = 470
    d.block("c1", 395, y2, 240, 120, "Debezium 수집", ["no_data · 하트비트 1초"], icon="sync", num="1")
    d.block("c2", 670, y2, 230, 120, "변경 버퍼", ["kdms.change_log"], icon="receipt_long")
    d.block("c3", 935, y2, 245, 120, "반영기", ["값 변환 → 멱등 반영"], icon="published_with_changes",
            num="3", strong=True)
    d.link("ct", "r", "c1", "l", dashed=True, label="수집")
    d.link("c1", "r", "c2", "l", dashed=True)
    d.link("c2", "r", "c3", "l", dashed=True)

    # 대상
    d.block("tgt", 1252, 220, 282, 370, "대상 테이블", ["COPY 로 적재", "INSERT ON CONFLICT", "DELETE WHERE pk"], icon="table")
    d.block("meta", 1252, 626, 282, 140, "kdms 관리 기록", ["watermark · load_chunk", "Debezium 오프셋"], icon="database")
    d.link("r3", "r", "tgt", "l", b_off=-125, label="COPY")
    d.link("c3", "r", "tgt", "l", dashed=True, b_off=125, label="반영")
    d.link("c1", "b", "meta", "l", label="워터마크 · 오프셋 기록")

    d.block("same", 395, 716, 785, 52, "같은 행이 두 경로로 들어와도 값 변환이 같아 해시가 같다 (T-C05)",
            icon="rule_settings", pill=True)

    d.notes = [
        "워터마크를 적재보다 먼저 기록 → 적재 중 생긴 변경은 모두 change_log 에 있다 · 반영은 LOADED 테이블만",
        "change_log 는 대상 PG 의 kdms 스키마 · payload(행 값)는 반영하면 바로 지운다",
    ]
    return d


EXPLAIN = """
<h2>1. 두 경로</h2>
<table>
<thead><tr><th>경로</th><th>읽기</th><th>값 변환</th><th>쓰기</th><th>기록(같은 트랜잭션)</th></tr></thead>
<tbody>
<tr><td>전체 적재 (<code>kdms load</code>)</td><td>원천 업무 테이블을 SNAPSHOT 격리로 PK 구간마다 SELECT(계산 컬럼 제외)</td><td><code>CopyValues</code>: NUL → 끝 공백 → 대소문자, 반올림, 센티널, uuid 소문자, bytea</td><td>PG <code>COPY … FROM STDIN</code></td><td>구간 행 + <code>load_chunk.status = DONE</code></td></tr>
<tr><td>CDC (<code>kdms sync</code>)</td><td>Debezium SQL Server 커넥터가 원천 CDC 변경 테이블(<code>cdc.*_CT</code>)을 LSN 순서로</td><td><code>CdcValues.decode</code> → 같은 <code>CopyValues</code></td><td>c·u: <code>INSERT … ON CONFLICT (pk) DO UPDATE</code>, d: <code>DELETE … WHERE pk</code></td><td>대상 적용 + 적용한 <code>change_log</code> 행 삭제 + 반영 위치 갱신</td></tr>
</tbody>
</table>

<h2>2. 기록 시점</h2>
<table>
<thead><tr><th>무엇</th><th>언제</th><th>어디</th></tr></thead>
<tbody>
<tr><td>워터마크(시작 LSN)</td><td><code>kdms sync</code> 가 스트리밍 시작 뒤 첫 배치를 받을 때 한 번. <code>change_log</code> 저장과 같은 트랜잭션</td><td><code>kdms.watermark.start_lsn</code></td></tr>
<tr><td>Debezium 오프셋</td><td>배치마다, <code>change_log</code> 커밋 <b>뒤</b></td><td><code>kdms.debezium_offset_&lt;job_id&gt;</code></td></tr>
<tr><td>적재 구간 완료</td><td>구간 COPY 와 같은 트랜잭션</td><td><code>kdms.load_chunk</code></td></tr>
<tr><td>반영 위치</td><td>반영 배치와 같은 트랜잭션</td><td><code>kdms.watermark.applied_*</code>, <code>kdms.job_table.applied_*</code></td></tr>
</tbody>
</table>

<h2>3. 빠짐·중복이 없는 이유</h2>
<ul>
<li><code>kdms load</code> 는 워터마크가 있어야 시작한다. 모든 적재 구간이 워터마크 뒤 시점을 읽고, 그 사이 변경은 <code>change_log</code> 에 있다.</li>
<li>반영기는 적재가 끝난(<code>LOADED</code>) 테이블의 변경만 <code>(commit_lsn, change_lsn, event_serial_no)</code> 순서로 멱등 적용한다. 적재가 이미 담은 변경을 다시 적용해도 결과가 같다.</li>
<li>어디서 죽어도 다시 실행하면 오프셋 다음부터 받는다. 중복 수신은 <code>change_log</code> UNIQUE 와 "테이블 반영 위치 이하" 조건으로 버린다(T-C08).</li>
<li>PK 를 바꾸는 UPDATE 는 삭제 + 입력, LOB 미변경 UPDATE 는 그 컬럼을 빼고 UPDATE 한다.</li>
<li>자세한 내용은 <code>docs/cdc.md</code> §2·§3, <code>docs/load-verify.md</code> §2.</li>
</ul>
"""
