package kdms.cli;

import picocli.CommandLine;

/** jar 매니페스트의 Implementation-Version. IDE 에서 돌리면 "개발판". */
public class VersionProvider implements CommandLine.IVersionProvider {

    public static String version() {
        String v = VersionProvider.class.getPackage().getImplementationVersion();
        return v == null ? "개발판" : v;
    }

    @Override
    public String[] getVersion() {
        return new String[] {"KDMS " + version(), "Java " + System.getProperty("java.version")};
    }
}
