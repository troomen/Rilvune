package dev.editor.platform.text.infrastructure.syntax.rsta;

import dev.editor.platform.text.domain.syntax.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Opt-in measurements, not hardware-sensitive unit-test assertions. */
class SyntaxPerformanceTest {
    @Test void recordsColdAndWarmTokenizerLatencyWithoutLoggingSource() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("EDITOR_SYNTAX_BENCHMARK")));
        var provider = new RstaSyntaxProvider(); var baseline = SyntaxLimits.interactive();
        var limits = new SyntaxLimits(baseline.characters(), baseline.lines(), baseline.lineCharacters(), baseline.spans(), 2, 256, 512,
                baseline.payloadBytes(), Duration.ZERO, Duration.ofSeconds(10));
        var report = new StringBuilder("Syntax benchmark (tokenization + compact result; excludes debounce/queue/EDT)\n")
                .append("Java ").append(System.getProperty("java.version")).append(" · ").append(System.getProperty("os.name"))
                .append(' ').append(System.getProperty("os.arch")).append(" · processors ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        Map<String, String> rows = Map.of("java", "public class Example { int n = 42; String s = \"value\"; } // source\n",
                "python", "def example(value):\n    return value + 42 # source\n", "javascript", "const value = 42; function example() { return \"text\"; }\n",
                "html", "<div class=\"source\"><script>const n = 42;</script></div>\n");
        for (var sample : rows.entrySet()) {
            String text = sample.getValue().repeat(102_400 / sample.getValue().length());
            long[] times = new long[21]; int spans = 0;
            for (int run = 0; run < times.length; run++) {
                var id = new SyntaxLanguageId(sample.getKey()); long started = System.nanoTime();
                try (var lexer = provider.create(id)) {
                    spans = lexer.lex(new SyntaxInput(id, text), new SyntaxWorkBudget(limits, () -> false, System::nanoTime)).size();
                }
                times[run] = System.nanoTime() - started;
            }
            long cold = times[0]; long[] warm = Arrays.copyOfRange(times, 1, times.length); Arrays.sort(warm);
            report.append(String.format(Locale.ROOT, "%s %d UTF-16 units, %d spans: cold %.3f ms, warm p50 %.3f ms, p95 %.3f ms%n",
                    sample.getKey(), text.length(), spans, cold / 1e6, warm[9] / 1e6, warm[18] / 1e6));
        }
        Path output = Path.of("build/reports/syntax-benchmark.txt"); Files.createDirectories(output.getParent()); Files.writeString(output, report);
    }
}
