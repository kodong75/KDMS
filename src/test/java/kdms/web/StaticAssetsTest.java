package kdms.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** plan.md T-N02: 화면 파일이 외부 URL 의 스크립트·스타일·글꼴을 읽지 않는다(폐쇄망, CDN 금지). */
class StaticAssetsTest {

    private static final Pattern EXTERNAL = Pattern.compile(
            "(?i)(<script[^>]+src\\s*=\\s*[\"']?(https?:)?//)|(<link[^>]+href\\s*=\\s*[\"']?(https?:)?//)"
                    + "|(@import\\s+(url\\()?[\"']?(https?:)?//)|(url\\(\\s*[\"']?(https?:)?//)|(fetch\\(\\s*[\"'](https?:)?//)");

    @Test
    void 외부_URL_참조가_없다() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Stream.concat(
                Files.walk(Path.of("src/main/resources/templates")),
                Files.walk(Path.of("src/main/resources/static")))) {
            files = s.filter(Files::isRegularFile).toList();
        }
        assertThat(files).isNotEmpty();
        for (Path f : files) {
            String text = Files.readString(f);
            assertThat(EXTERNAL.matcher(text).find()).as(f + " 에 외부 URL 참조").isFalse();
        }
    }
}
