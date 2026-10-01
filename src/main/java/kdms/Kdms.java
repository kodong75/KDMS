package kdms;

import kdms.cli.InitCommand;
import kdms.cli.StatusCommand;
import kdms.cli.VersionProvider;
import kdms.cli.WebCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * 입구. {@code java -jar kdms.jar <명령>}. 웹 화면은 {@code web} 명령일 때만 띄운다.
 */
@Command(name = "kdms",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "MS-SQL 2019 → PostgreSQL 16 미니 DMS(데이터 이관 서비스)",
        subcommands = {StatusCommand.class, InitCommand.class, WebCommand.class, CommandLine.HelpCommand.class})
public class Kdms implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        int code = newCommandLine().execute(args);
        // web 명령은 웹 서버 스레드가 살아 있는 동안 돌아오지 않는다
        if (code != WebCommand.RUNNING) {
            System.exit(code);
        }
    }

    public static CommandLine newCommandLine() {
        CommandLine cl = new CommandLine(new Kdms());
        cl.setExecutionExceptionHandler(new kdms.cli.ErrorHandler());
        return cl;
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
