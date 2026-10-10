#!/usr/bin/env bash
# KDMS 6단계 MVP 리허설 1회(plan.md §6, §8 전체). 절차·합격 기준: docs/rehearsal.md
#
#   bash scripts/rehearsal.sh <회차>            # 저장소 루트에서. 회차는 1, 2 …
#
# Mac 에서 kdms.jar 명령을 차례로 실행하고, 노트북이 할 일(원천 되돌림·쓰기·캡처 Job·T-C10 정리)은 화면에 PowerShell 명령을 띄우고 Enter 를 기다린다.
# 결과: runs/<시각>_p6_r<회차>_log.txt(실행 원문), runs/<시각>_p6_r<회차>_score.txt(시험별 합격·불합격). 중간 파일은 out/rehearsal/.
#
# 환경 변수(모두 선택):
#   KDMS_REH_WRITE_SEC   본 시험 원천 쓰기 시간(초, 기본 300)
#   KDMS_REH_SKIP_BUILD  1 이면 빌드를 건너뛴다(T-L17 은 미시행)
#   KDMS_REH_LAPTOP      노트북 단계를 대신 실행할 명령(클라우드 시험용). "<명령> <단계> [초]" 로 부른다. 없으면 사람에게 묻는다
#   KDMS_REH_STAMP       결과 파일 시각(기본 지금 KST yyyyMMdd_HHmm, DEC-45)
#
# macOS 기본 bash 3.2 에서 돈다(연관 배열·wait -n 을 쓰지 않는다). 비밀번호는 출력하지 않는다(.env 는 kdms.jar 가 읽는다).

set -u
R=${1:-}
if [ -z "$R" ]; then
    echo "사용법: bash scripts/rehearsal.sh <회차>   (예: bash scripts/rehearsal.sh 1)"
    exit 2
fi
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT" || exit 2

JAR=target/kdms.jar
CFG=config/kdms.yml
S=${KDMS_REH_STAMP:-$(TZ=Asia/Seoul date +%Y%m%d_%H%M)}
W=out/rehearsal/${S}_r${R}
LOG=runs/${S}_p6_r${R}_log.txt
SCORE=runs/${S}_p6_r${R}_score.txt
WRITE_SEC=${KDMS_REH_WRITE_SEC:-300}
HOOK=${KDMS_REH_LAPTOP:-}
PS=./scripts/Invoke-KdmsRehearsal.ps1

if [ ! -f "$CFG" ] || [ ! -f .env ]; then
    echo "config/kdms.yml 과 .env 가 있어야 한다(docs/test-env.md §5)"
    exit 2
fi
mkdir -p "$W" runs
: > "$W/codes"
: > "$W/score"

# ---------------------------------------------------------------- 출력·기록

ts() { date '+%Y-%m-%d %H:%M:%S'; }

# 화면과 실행 원문에 같이 찍는다
say() {
    echo "$*"
    echo "$*" >> "$LOG"
}

head_line() {
    say ""
    say "===== $(ts) [r${R}] $*"
}

# kd 이름 인수… : kdms 명령을 앞에서 실행하고 원문을 $W/이름.txt 와 실행 원문에 남긴다. 종료 코드를 돌려준다
kd() {
    local name=$1
    shift
    head_line "kdms $* ($name)"
    java -jar "$JAR" "$@" > "$W/$name.txt" 2>&1
    local rc=$?
    grep -v '^Picked up JAVA_TOOL_OPTIONS' "$W/$name.txt" >> "$LOG"
    say "exit $rc"
    echo "$name $rc" >> "$W/codes"
    tail -n 3 "$W/$name.txt" | sed 's/^/    /'
    return $rc
}

# kd_bg 이름 인수… : 뒤에서 실행. PID 는 BG_PID 에
kd_bg() {
    local name=$1
    shift
    head_line "kdms $* ($name, 뒤에서)"
    java -jar "$JAR" "$@" > "$W/$name.txt" 2>&1 &
    BG_PID=$!
    BG_ALL="$BG_ALL $BG_PID"
}

# 뒤에서 돈 명령이 끝나면 원문을 실행 원문에 붙인다
bg_done() {
    local name=$1 rc=$2
    head_line "($name 원문)"
    grep -v '^Picked up JAVA_TOOL_OPTIONS' "$W/$name.txt" >> "$LOG"
    say "exit $rc"
    echo "$name $rc" >> "$W/codes"
}

# wait_for 파일 정규식 초 : 파일에 정규식이 나올 때까지 기다린다
wait_for() {
    local f=$1 re=$2 sec=$3 i=0
    while [ $i -lt "$sec" ]; do
        if grep -Eq "$re" "$f" 2>/dev/null; then
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    return 1
}

# 프로세스가 끝날 때까지 최대 초만큼 기다리고, 넘으면 SIGKILL. 종료 코드를 돌려준다
wait_pid() {
    local pid=$1 sec=$2 i=0
    while kill -0 "$pid" 2>/dev/null && [ $i -lt "$sec" ]; do
        sleep 1
        i=$((i + 1))
    done
    if kill -0 "$pid" 2>/dev/null; then
        kill -9 "$pid" 2>/dev/null
    fi
    wait "$pid" 2>/dev/null
    return $?
}

code_of() { awk -v n="$1" '$1 == n { c = $2 } END { print (c == "" ? "-" : c) }' "$W/codes"; }

# 채점 줄: 시험 결과(합격|불합격|미시행) 설명 근거
# (printf 의 폭은 바이트라 한글 결과 칸은 직접 맞춘다)
mark() {
    local r=$2
    [ "$r" = 합격 ] && r="합격  "
    echo "$1  $r  $3 · 근거: $4" >> "$W/score"
    echo "  $1  $r  $3"
}

# check 조건이 참이면 합격
judge() {
    local id=$1 desc=$2 evidence=$3
    shift 3
    if "$@"; then mark "$id" 합격 "$desc" "$evidence"; else mark "$id" 불합격 "$desc" "$evidence"; fi
}

has() { grep -Eq "$2" "$W/$1.txt" 2>/dev/null; }
rc_is() { [ "$(code_of "$1")" = "$2" ]; }

# ---------------------------------------------------------------- 노트북 단계

laptop() {
    local step=$1 sec=${2:-}
    head_line "노트북: $step ${sec:+${sec}초}"
    if [ -n "$HOOK" ]; then
        $HOOK "$step" $sec > "$W/laptop_$step.txt" 2>&1
        local rc=$?
        cat "$W/laptop_$step.txt" >> "$LOG"
        say "exit $rc"
        return $rc
    fi
    echo ""
    echo "  ┌ 노트북 PowerShell 7 (C:\\Projects\\KDMS) 에서:"
    echo "  │   $PS -Step $step${sec:+ -Sec $sec}"
    echo "  └ 끝나면(초록 글씨 또는 exit 0) 여기서 Enter. 실패하면 n Enter"
    local a
    read -r a
    if [ "$a" = "n" ]; then
        say "사람: 노트북 $step 실패"
        return 1
    fi
    say "사람: 노트북 $step 끝"
    return 0
}

WRITES_PID=
WRITES_END=0
writes_start() {
    local sec=$1
    head_line "노트북: 원천 쓰기 ${sec}초 시작"
    WRITES_END=$(( $(date +%s) + sec ))
    if [ -n "$HOOK" ]; then
        $HOOK writes "$sec" > "$W/laptop_writes.txt" 2>&1 &
        WRITES_PID=$!
        return 0
    fi
    echo ""
    echo "  ┌ 노트북 PowerShell 7 다른 창에서 지금 시작:"
    echo "  │   $PS -Step writes -Sec $sec"
    echo "  └ 시작했으면 바로 Enter (쓰기가 끝날 때까지 기다리지 않는다)"
    local a
    read -r a
    WRITES_END=$(( $(date +%s) + sec ))
    say "사람: 노트북 쓰기 시작"
}

writes_wait() {
    head_line "노트북: 원천 쓰기가 끝나기를 기다림"
    if [ -n "$HOOK" ]; then
        wait "$WRITES_PID"
        local rc=$?
        cat "$W/laptop_writes.txt" >> "$LOG"
        say "exit $rc"
        return $rc
    fi
    local left=$(( WRITES_END - $(date +%s) ))
    if [ $left -gt 0 ]; then
        echo "  쓰기 끝까지 약 ${left}초 기다린다"
        sleep "$left"
    fi
    echo "  노트북 쓰기 창에 '쓰기 끝' 이 나왔으면 Enter (아직이면 나온 뒤에)"
    local a
    read -r a
    say "사람: 노트북 쓰기 끝"
}

# ---------------------------------------------------------------- 외부 연결 감시(T-N03)

env_val() { grep -E "^$1=" .env | tail -n 1 | cut -d= -f2- | tr -d '\r'; }
SRC_EP="$(env_val KDMS_SRC_HOST):$(env_val KDMS_SRC_PORT)"
TGT_EP="$(env_val KDMS_TGT_HOST):$(env_val KDMS_TGT_PORT)"
[ "$SRC_EP" = ":" ] && SRC_EP="?"
case "$SRC_EP" in *:) SRC_EP="${SRC_EP}1433" ;; esac
case "$TGT_EP" in *:) TGT_EP="${TGT_EP}5432" ;; esac

MON_PID=
BG_ALL=
# Ctrl+C: 이 스크립트가 띄운 kdms 와 연결 감시만 멈춘다(다른 창의 kdms web 등은 그대로)
on_int() {
    echo ""
    echo "중단: 이 스크립트가 띄운 kdms 를 멈춘다. 다시 할 때는 처음부터(bash scripts/rehearsal.sh $R)"
    for p in $BG_ALL $WRITES_PID; do kill -TERM "$p" 2>/dev/null; done
    [ -n "$MON_PID" ] && kill "$MON_PID" 2>/dev/null
    exit 130
}
trap on_int INT TERM

mon_start() {
    : > "$W/conn.txt"
    (
        while :; do
            for p in $(pgrep -f 'kdms\.jar' 2>/dev/null); do
                lsof -nP -a -p "$p" -iTCP -sTCP:ESTABLISHED 2>/dev/null | awk 'NR > 1 { print $9 }'
            done >> "$W/conn.txt"
            sleep 1
        done
    ) &
    MON_PID=$!
}
mon_stop() {
    [ -n "$MON_PID" ] && kill "$MON_PID" 2>/dev/null
    wait "$MON_PID" 2>/dev/null
}

net_blocked() { ! curl -s -m 5 -o /dev/null https://repo.maven.apache.org/maven2/ 2>/dev/null; }

# kd_reset 이름 : reset 이 실패하면(노트북 접속 끊김 등) 대상이 초기화되지 않은 채 다음 시험이 이어져 줄줄이 틀린다 → 회차를 멈춘다
kd_reset() {
    kd "$1" reset --yes && return 0
    say "reset 실패(종료 코드 위). 대상이 처음 상태가 아니라 이 회차를 멈춘다. 접속 확인: nc -vz ${SRC_EP%:*} ${SRC_EP##*:}; nc -vz ${TGT_EP%:*} ${TGT_EP##*:}"
    say "회차를 처음부터 다시 한다(docs/rehearsal.md §6)"
    mon_stop
    exit 1
}

# ================================================================= 시작

: > "$LOG"
say "# KDMS 6단계 MVP 리허설 ${R}회차 · $(ts) · 절차 docs/rehearsal.md"
say "# 저장소 $(git rev-parse --abbrev-ref HEAD 2>/dev/null) $(git rev-parse --short HEAD 2>/dev/null) · $(uname -sm) · $(java -version 2>&1 | grep -v '^Picked up' | head -n 1)"
if [ -n "$HOOK" ]; then WHO="자동($HOOK)"; else WHO="사람이 노트북에서"; fi
say "# 원천 $SRC_EP · 대상 $TGT_EP (비밀번호는 .env 에만) · 노트북 단계: $WHO"
say "# 원천 쓰기 ${WRITE_SEC}초 · 중간 파일 $W"
T0=$(date +%s)

# ---- 0. 폐쇄망(T-N01)
head_line "0. 인터넷 차단 확인(T-N01)"
if net_blocked; then
    NET0=1
    say "인터넷 연결 안 됨(repo.maven.apache.org 5초 안에 응답 없음)"
elif [ -z "$HOOK" ]; then
    echo ""
    echo "  인터넷이 연결돼 있다. T-N01 은 인터넷을 끊고(LAN 은 그대로) 해야 한다. docs/rehearsal.md §2 의 명령:"
    echo "    printf 'set skip on lo0\\npass out quick inet from any to 192.168.0.0/24\\nblock drop out quick all\\n' > /tmp/kdms-offline.pf"
    echo "    sudo pfctl -f /tmp/kdms-offline.pf -E      # LAN(192.168.0.x)·자기 자신만 열고 나머지 차단. 되돌리기: sudo pfctl -f /etc/pf.conf; sudo pfctl -d"
    echo "  끊었으면 Enter. 끊지 않고 진행하려면 s Enter (T-N01 미시행)"
    read -r a
    if net_blocked; then NET0=1; say "인터넷 연결 안 됨(사람이 끊음)"; else NET0=0; say "인터넷 연결됨(T-N01 미시행)"; fi
else
    NET0=0
    say "인터넷 연결됨(T-N01 미시행)"
fi
mon_start

# ---- 1. 원천 되돌림 · 빌드 · 접속
laptop restore || say "노트북 restore 실패"

if [ "${KDMS_REH_SKIP_BUILD:-0}" = "1" ]; then
    head_line "1. 빌드 건너뜀(KDMS_REH_SKIP_BUILD=1)"
    echo "build -" >> "$W/codes"
else
    head_line "1. 빌드(오프라인, 단위 시험 포함): ./mvnw -o -B clean package"
    ./mvnw -o -B clean package > "$W/build.txt" 2>&1
    rc=$?
    grep -E 'Tests run:|BUILD|ERROR' "$W/build.txt" | grep -v '^Picked up' >> "$LOG"
    say "exit $rc"
    echo "build $rc" >> "$W/codes"
    if [ $rc -ne 0 ]; then
        say "빌드 실패. $W/build.txt 를 본다. 리허설을 멈춘다"
        mon_stop
        exit 1
    fi
fi

head_line "2. jar 안 화면 파일의 외부 URL(T-N02)"
unzip -p "$JAR" 'BOOT-INF/classes/templates/*' 'BOOT-INF/classes/static/*' 2>/dev/null \
    | grep -Eio "(src|href)=[\"']?https?://[^\"' >]*|url\\([\"']?https?://[^)]*|@import[^;]*https?://[^;]*" > "$W/n02.txt"
N02=$(grep -c . "$W/n02.txt")
cat "$W/n02.txt" >> "$LOG"
say "외부 URL 참조 ${N02}개 (templates·static)"

kd status status

# ---- 2. 계획: 기본 결정(T-L08·T-L10·T-L11·T-L14), NUL 은 fail 이면 막힘(T-L09)
NULCFG=$W/kdms-nulfail.yml
sed 's#^rules:.*#rules: config/rehearsal/rules-nul-fail.yml#' "$CFG" > "$NULCFG"
kd plan plan --scan
kd plan_nulfail plan --scan -c "$NULCFG" -o "$W/plan_nulfail"

# ================================================================= L. 원천 쓰기 없는 적재·검증·전환(T-L01~T-L16)
head_line "L. 원천 쓰기 없음: 적재 → 검증 → 전환 → 점검"
kd_reset l_reset
kd l_schema schema --replace
kd l_load_nulfail load --no-cdc -c "$NULCFG"

# T-L16: 1,000행마다 1초 쉬게 해서 6초 뒤 kill -9 → 다시 실행하면 끝난 구간은 건너뛴다
kd_bg l_load_kill load --no-cdc --reset --throttle-ms 1000
sleep 6
kill -9 "$BG_PID" 2>/dev/null
wait "$BG_PID" 2>/dev/null
bg_done l_load_kill 137
say "kill -9 (적재 6초 뒤)"
kd l_load_resume load --no-cdc
kd l_verify verify
kd l_cutover cutover --yes --no-cdc
kd l_check check --probe

# ================================================================= C. 원천 쓰기 중 적재·반영·전환(T-C01~T-C12)
head_line "C. 원천 쓰기 중: 동기화 → 적재 → 강제 종료·캡처 중지 → 전환 → 점검"
kd_reset c_reset
kd c_schema schema --replace
kd c_load_nowm load      # T-C02: 워터마크 없이 적재하면 거부

# T-C10: 보존 기간 초과 → 조용히 틀리지 않고 멈춤
kd_bg c_sync0 sync
P=$BG_PID
wait_for "$W/c_sync0.txt" '워터마크 기록' 120 || say "워터마크 기록이 120초 안에 나오지 않음"
laptop writes 20
sleep 15
kill -TERM "$P" 2>/dev/null
wait_pid "$P" 60
bg_done c_sync0 $?
laptop tc10
kd_bg c_sync_tc10 sync
P=$BG_PID
wait_pid "$P" 120
bg_done c_sync_tc10 $?
kd_reset c_reset2

# 본 시험
kd_bg c_sync1 sync
P=$BG_PID
wait_for "$W/c_sync1.txt" '워터마크 기록' 120 || say "워터마크 기록이 120초 안에 나오지 않음"
writes_start "$WRITE_SEC"
sleep 15
kd_bg c_load load --throttle-ms 2000
LP=$BG_PID
sleep 10
# T-C08 ①: 적재 중·수집 중 강제 종료
kill -9 "$P" 2>/dev/null
wait "$P" 2>/dev/null
bg_done c_sync1 137
say "kill -9 sync (적재 중)"
kd_bg c_sync2 sync
P=$BG_PID
wait_for "$W/c_sync2.txt" '이어 받는다' 60 || say "다시 시작한 sync 가 60초 안에 이어 받지 않음"
wait_pid "$LP" 900
bg_done c_load $?
sleep 20
# T-C08 ②: 적재 뒤 반영 중 강제 종료(쓰기는 계속)
kill -9 "$P" 2>/dev/null
wait "$P" 2>/dev/null
bg_done c_sync2 137
say "kill -9 sync (반영 중)"
kd_bg c_sync3 sync
P=$BG_PID
wait_for "$W/c_sync3.txt" '이어 받는다' 60 || say "다시 시작한 sync 가 60초 안에 이어 받지 않음"
sleep 10
# T-C09: 캡처 Job 60초 중지
laptop capture-stop
sleep 60
laptop capture-start
writes_wait
sleep 20
kill -TERM "$P" 2>/dev/null
wait_pid "$P" 120
bg_done c_sync3 $?
kd c_cutover cutover --yes
kd c_check check --probe
kd c_status status

mon_stop
NET1=0
net_blocked && NET1=1

# ================================================================= 채점
echo ""
echo "채점 (docs/rehearsal.md §4)"
{
    echo "# KDMS 6단계 MVP 리허설 ${R}회차 채점 · $(ts) · 실행 원문 $LOG"
    echo "# 시험   결과   기대 · 근거(실행 원문의 단계 이름)"
} > "$SCORE"

# L 단계
LOK='rc_is l_verify 0 && has l_verify "불일치 0"'
judge T-L01 "datetime 3.33ms 값 해시 일치" "l_verify" eval "$LOK"
judge T-L02 "datetime2(7) → 6자리 해시 일치" "l_verify" eval "$LOK"
judge T-L03 "money 극값 합계 오버플로 없음" "l_verify(합계)" eval "$LOK"
judge T-L04 "bit → boolean 해시 일치" "l_verify" eval "$LOK"
judge T-L05 "IDENTITY 다음 값 = 원천 IDENT_CURRENT + 1" "l_check·c_check 다음 값" \
    eval 'rc_is l_check 0 && rc_is c_check 0 && has c_check "통과 .*app_user.*user_id.*: 대상 다음 값 [0-9]+ = 원천"'
judge T-L06 "SEQUENCE 기본값 nextval·현재값 setval 일치" "l_check·c_check 다음 값, plan" \
    eval 'rc_is l_check 0 && rc_is c_check 0 && has c_check "통과 .*seq_doc_no.*: 대상 다음 값 [0-9]+ = 원천" && has plan "nextval"'
judge T-L07 "char 끝 공백 RTRIM 정규화로 일치" "l_verify" eval "$LOK"
judge T-L08 "varchar 끝 공백 keep 값 보존·해시 일치, 보고서 주의" "l_verify, plan 주의" eval "$LOK"' && has plan "trailing_space: keep"'
judge T-L09 "NUL fail 이면 계획 막힘·적재가 그 구간에서 멈춤, replace 면 일치" "plan_nulfail·l_load_nulfail·l_verify" \
    eval 'rc_is plan_nulfail 3 && has plan_nulfail "NUL" && rc_is l_load_nulfail 5 && has l_load_nulfail "NUL" && '"$LOK"
judge T-L10 "CP949 varchar 한글 보존" "l_verify, plan 문자 인코딩" eval "$LOK"' && has plan "비유니코드"'
judge T-L11 "nvarchar 한글 문자 수 그대로(근접 인덱스 경고는 SchemaPlannerTest)" "l_verify" eval "$LOK"
judge T-L12 "CI UNIQUE: 대상도 대소문자만 다른 값 거부" "l_check·c_check 입력 시험" \
    eval 'has l_check "통과 .*uq_app_user_login: 대소문자" && has c_check "통과 .*uq_app_user_login: 대소문자"'
judge T-L13 "계산 컬럼 GENERATED STORED, 값 일치" "l_check 계산 컬럼, l_verify" \
    eval 'has l_check "통과 .*rating_rank.*STORED" && has l_check "통과 .*file_ext.*STORED" && '"$LOK"
judge T-L14 "센티널 날짜 keep" "plan 주의, l_verify" eval "$LOK"' && has plan "센티널 날짜 행"'
judge T-L15 "탭·CR·LF·역슬래시 값 해시 일치" "l_verify" eval "$LOK"
judge T-L16 "적재 중 kill -9 → 다시 실행하면 끝난 구간 건너뛰고 일치" "l_load_kill·l_load_resume·l_verify" \
    eval 'rc_is l_load_resume 0 && has l_load_resume "건너뜀" && '"$LOK"

if [ "$(code_of build)" = "-" ]; then
    mark T-L17 미시행 "생성 검증 SQL 에 ISNULL 없음(NormalizerTest)" "빌드 건너뜀"
else
    judge T-L17 "생성 검증 SQL 에 ISNULL 없음(NormalizerTest 포함 단위 시험 통과)" "build" \
        eval 'rc_is build 0 && has build "Tests run: [0-9]+, Failures: 0, Errors: 0" && has build "kdms.verify.NormalizerTest"'
fi

# C 단계
COK='rc_is c_cutover 0 && has c_cutover "불일치 0"'
judge T-C01 "쓰기 중 적재 + 반영 → 쓰기 중지 → 검증 일치" "c_cutover [4/6]" eval "$COK"
judge T-C02 "워터마크 없이 적재하면 거부" "c_load_nowm" eval 'rc_is c_load_nowm 4 && has c_load_nowm "워터마크가 없다"'
judge T-C03 "적재 중 입력 후 삭제된 행이 유령으로 남지 않음" "c_cutover 검증(extra 0)" eval "$COK"
judge T-C04 "원천 트리거 이력 행 수 = 원천(중복 없음)" "c_cutover 검증 rating_hist 건수" eval "$COK"
judge T-C05 "적재 경로·CDC 경로 값 동일" "c_cutover 검증 해시" eval "$COK"
judge T-C06 "LOB 미변경 UPDATE 뒤 대상 LOB 유지" "c_cutover 검증 research_doc 해시" eval "$COK"
judge T-C07 "PK 를 바꾸는 UPDATE" "c_cutover 검증 code_master" eval "$COK"
judge T-C08 "수집 중·반영 중 kill -9 뒤 이어 받고 일치" "c_sync2·c_sync3 '이어 받는다', c_cutover" \
    eval 'has c_sync2 "이어 받는다" && has c_sync3 "이어 받는다" && '"$COK"
LAG_LAST=$(grep -E '지연 [0-9.]+초' "$W/c_sync3.txt" 2>/dev/null | tail -n 1 | sed -E 's/.*지연 ([0-9.]+)초.*/\1/')
judge T-C09 "캡처 Job 1분 중지: 지연이 늘었다 따라잡음, 화면·로그에 경고" "c_sync3 '로그를 읽지 않음', 마지막 지연 ${LAG_LAST:-?}초" \
    eval 'has c_sync3 "로그를 읽지 않음" && [ -n "$LAG_LAST" ] && awk -v x="$LAG_LAST" "BEGIN { exit !(x < 15) }"'
judge T-C10 "보존 기간 초과면 멈추고 reset 안내" "c_sync_tc10" \
    eval 'rc_is c_sync_tc10 5 && has c_sync_tc10 "보존 기간" && rc_is c_reset2 0'
judge T-C11 "전환: 마지막 반영 → 검증 → setval → FK, 소요 시간, 새 입력 PK 충돌 없음" "c_cutover, c_check" \
    eval "$COK"' && has c_cutover "소요 시간\\(예상 다운타임\\)" && rc_is c_check 0 && has c_check "통과 .*fk_rating_issuer: 부모에 없는"'
judge T-C12 "긴 트랜잭션 늦은 커밋도 누락 없음" "c_cutover 검증(30_writes 500번째마다 3초 트랜잭션)" eval "$COK"

# 빌드·폐쇄망
judge T-N02 "jar 안 화면 파일이 외부 URL 을 읽지 않음" "2. jar 안 화면 파일" eval '[ "$N02" = 0 ]'
CONN_BAD=$(sort -u "$W/conn.txt" | sed 's/.*->//' | grep -v -x -F -e "$SRC_EP" -e "$TGT_EP" | grep -v -E '^(127\.0\.0\.1|\[::1\]|localhost):' || true)
CONN_N=$(sort -u "$W/conn.txt" | sed 's/.*->//' | sort -u | tr '\n' ' ')
echo "외부 연결 감시: 본 연결 상대 = ${CONN_N:-없음}" >> "$LOG"
if [ ! -s "$W/conn.txt" ]; then
    mark T-N03 불합격 "실행 중 외부 연결 0(설정한 두 DB 만)" "연결 감시 기록이 비었다(lsof 확인)"
elif [ -z "$CONN_BAD" ]; then
    mark T-N03 합격 "실행 중 외부 연결 0(설정한 두 DB 만)" "lsof 1초 간격, 상대 ${CONN_N}"
else
    mark T-N03 불합격 "실행 중 외부 연결 0(설정한 두 DB 만)" "그 밖의 상대: $(echo "$CONN_BAD" | tr '\n' ' ')"
fi

if [ "$NET0" = 1 ] && [ "$NET1" = 1 ]; then
    judge T-N01 "인터넷 차단 상태에서 빌드·적재·CDC·검증·전환" "0. 인터넷 차단 확인(시작·끝)" \
        eval 'rc_is l_cutover 0 && rc_is c_cutover 0'
else
    mark T-N01 미시행 "인터넷 차단 상태에서 빌드·적재·CDC·검증·전환" "인터넷이 연결된 채 실행(시작 $NET0·끝 $NET1, 1 = 차단)"
fi

DOWN=$(grep -E '소요 시간\(예상 다운타임\)' "$W/c_cutover.txt" 2>/dev/null | tail -n 1 | sed -E 's/.*다운타임\) ([0-9.]+초).*/\1/')
cat "$W/score" >> "$SCORE"
PASS=$(grep -c ' 합격 ' "$W/score")
FAIL=$(grep -c ' 불합격 ' "$W/score")
SKIP=$(grep -c ' 미시행 ' "$W/score")
TOTAL=$(grep -c . "$W/score")
ELAPSED=$(( $(date +%s) - T0 ))
{
    echo ""
    echo "결과: 시험 ${TOTAL}개 · 합격 ${PASS} · 불합격 ${FAIL} · 미시행 ${SKIP}"
    echo "전환 소요 시간(예상 다운타임, C 단계): ${DOWN:-?} · 리허설 전체 $((ELAPSED / 60))분 $((ELAPSED % 60))초"
    if [ "$FAIL" = 0 ] && [ "$SKIP" = 0 ]; then echo "${R}회차 합격"; else echo "${R}회차 불합격(불합격·미시행 줄을 본다)"; fi
} | tee -a "$SCORE"
cat "$SCORE" >> "$LOG"
echo ""
echo "결과 파일: $LOG, $SCORE"
[ "$FAIL" = 0 ] && [ "$SKIP" = 0 ]
