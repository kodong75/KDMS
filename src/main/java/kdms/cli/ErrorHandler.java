package kdms.cli;

import kdms.config.ConfigException;
import picocli.CommandLine;

/** 설정 오류는 스택 없이 한 줄로, 그 밖의 오류는 원문 그대로. */
public class ErrorHandler implements CommandLine.IExecutionExceptionHandler {

    public static final int CONFIG_ERROR = 1;

    @Override
    public int handleExecutionException(Exception ex, CommandLine cl, CommandLine.ParseResult parseResult) {
        if (ex instanceof ConfigException) {
            cl.getErr().println("설정 오류: " + ex.getMessage());
            return CONFIG_ERROR;
        }
        ex.printStackTrace(cl.getErr());
        return CommandLine.ExitCode.SOFTWARE;
    }
}
