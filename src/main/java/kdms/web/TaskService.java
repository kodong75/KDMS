package kdms.web;

import java.io.PrintWriter;
import java.io.Writer;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import kdms.cli.CutoverCommand;
import kdms.cli.ErrorHandler;
import kdms.cli.LoadCommand;
import kdms.cli.SyncCommand;
import kdms.cli.VerifyCommand;
import picocli.CommandLine;

/**
 * 웹 화면의 버튼이 kdms 명령(sync·load·verify·cutover)을 이 프로세스 안에서 실행한다. CLI 와 같은 명령 클래스를 그대로 쓰므로
 * 거부·잠금·종료 코드도 같다. 종류마다 하나씩만 돈다. 출력은 종류마다 마지막 {@link #MAX_LINES} 줄을 둔다(행 값은 원래 출력에 없다).
 * 지우는 명령(reset·schema --replace)은 화면에 두지 않는다.
 */
public final class TaskService {

    static final int MAX_LINES = 400;

    /** 화면에서 실행할 수 있는 명령과 붙일 인자 */
    static final Map<String, List<String>> KINDS = Map.of(
            "sync", List.of("sync"),
            "load", List.of("load"),
            "verify", List.of("verify"),
            "cutover", List.of("cutover", "--yes"));

    /**
     * @param exitCode 끝났으면 종료 코드, 돌고 있으면 null
     */
    public record Task(String kind, OffsetDateTime startedAt, OffsetDateTime finishedAt, Integer exitCode, boolean stopping, List<String> lines) {
    }

    /** 실행할 수 없음(이미 돌고 있음 등) */
    public static final class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    private final List<String> configArgs;
    private final Map<String, Run> runs = new LinkedHashMap<>();

    /** @param configArgs 설정 파일 옵션(-c … --env-file …). null 이면 화면에서 명령을 실행하지 않는다(시험) */
    public TaskService(List<String> configArgs) {
        this.configArgs = configArgs;
    }

    public boolean enabled() {
        return configArgs != null;
    }

    public synchronized Task start(String kind) {
        List<String> base = KINDS.get(kind);
        if (base == null) {
            throw new Rejected("모르는 명령: " + kind);
        }
        if (!enabled()) {
            throw new Rejected("이 화면은 명령을 실행하지 않도록 띄웠다");
        }
        Run cur = runs.get(kind);
        if (cur != null && cur.exitCode == null) {
            throw new Rejected("kdms " + kind + " 가 이미 돌고 있다");
        }
        Object command = switch (kind) {
            case "sync" -> new SyncCommand();
            case "load" -> new LoadCommand();
            case "verify" -> new VerifyCommand();
            default -> new CutoverCommand();
        };
        Run run = new Run(kind, command);
        // 전환은 동기화와 함께 돌 수 없다(전환이 마지막 반영을 직접 한다). 이 화면에서 띄운 동기화는 먼저 멈춘다
        Run sync = "cutover".equals(kind) ? runs.get("sync") : null;
        if (sync != null && sync.exitCode == null) {
            ((SyncCommand) sync.command).requestStop();
            sync.stopping = true;
        }
        runs.put(kind, run);
        List<String> args = new ArrayList<>(base.subList(1, base.size()));
        args.addAll(configArgs);
        Thread t = new Thread(() -> {
            if (sync != null) {
                run.add("이 화면에서 띄운 kdms sync 를 멈추는 중(진행 중 배치를 마친다)");
                sync.awaitExit(120_000);
            }
            run.execute(args.toArray(String[]::new));
        }, "kdms-web-" + kind);
        t.setDaemon(true);
        t.start();
        return run.snapshot();
    }

    /** sync·cutover 만 멈출 수 있다(Ctrl+C 와 같음). 나머지는 끝날 때까지 기다린다 */
    public synchronized Task stop(String kind) {
        Run run = runs.get(kind);
        if (run == null || run.exitCode != null) {
            throw new Rejected("kdms " + kind + " 가 돌고 있지 않다");
        }
        if (run.command instanceof SyncCommand s) {
            s.requestStop();
        } else if (run.command instanceof CutoverCommand c) {
            c.requestStop();
        } else {
            throw new Rejected("kdms " + kind + " 는 중간에 멈출 수 없다(끝날 때까지 기다린다)");
        }
        run.stopping = true;
        return run.snapshot();
    }

    public synchronized List<Task> tasks() {
        return runs.values().stream().map(Run::snapshot).toList();
    }

    private static final class Run {
        final String kind;
        final Object command;
        final OffsetDateTime startedAt = OffsetDateTime.now();
        final Deque<String> lines = new ArrayDeque<>();
        volatile OffsetDateTime finishedAt;
        volatile Integer exitCode;
        volatile boolean stopping;

        Run(String kind, Object command) {
            this.kind = kind;
            this.command = command;
        }

        void execute(String[] args) {
            PrintWriter pw = new PrintWriter(new LineWriter(this), true);
            int code;
            try {
                CommandLine cl = new CommandLine(command);
                cl.setOut(pw);
                cl.setErr(pw);
                cl.setExecutionExceptionHandler(new ErrorHandler());
                code = cl.execute(args);
            } catch (RuntimeException e) {
                pw.println("실행 오류: " + e.getMessage());
                code = 1;
            }
            pw.flush();
            finishedAt = OffsetDateTime.now();
            exitCode = code;
        }

        void awaitExit(long timeoutMs) {
            long end = System.currentTimeMillis() + timeoutMs;
            while (exitCode == null && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        void add(String line) {
            synchronized (lines) {
                lines.addLast(line);
                while (lines.size() > MAX_LINES) {
                    lines.removeFirst();
                }
            }
        }

        Task snapshot() {
            List<String> copy;
            synchronized (lines) {
                copy = List.copyOf(lines);
            }
            return new Task(kind, startedAt, finishedAt, exitCode, stopping && exitCode == null, copy);
        }
    }

    /** 줄 단위로 끊어 Run 에 넣는다 */
    private static final class LineWriter extends Writer {
        private final Run run;
        private final StringBuilder buf = new StringBuilder();

        LineWriter(Run run) {
            this.run = run;
        }

        @Override
        public synchronized void write(char[] cbuf, int off, int len) {
            for (int i = off; i < off + len; i++) {
                char ch = cbuf[i];
                if (ch == '\n') {
                    run.add(buf.toString());
                    buf.setLength(0);
                } else if (ch != '\r') {
                    buf.append(ch);
                }
            }
        }

        @Override
        public synchronized void flush() {
            // 줄이 끝나야 내보낸다
        }

        @Override
        public synchronized void close() {
            if (!buf.isEmpty()) {
                run.add(buf.toString());
                buf.setLength(0);
            }
        }
    }
}
