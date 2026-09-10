package com.box.l10n.mojito.mf2;

import java.util.Arrays;
import java.util.Map;

/** Bounded diagnostic benchmark: parses before timing, validates every rendered result. */
public final class ChainScalingBenchmark {
    public static void main(String[] args) throws Exception {
        for (boolean alias : new boolean[]{false, true}) {
            for (int count : new int[]{1000, 2000, 4000, 8000}) {
                StringBuilder source = new StringBuilder(".local $v0 = {1 :number}\n");
                for (int index = 1; index < count; index++) source.append(".local $v").append(index).append(" = {$v").append(index - 1).append(alias ? "}\n" : " :number}\n");
                source.append("{{{$v").append(count - 1).append("}}}");
                var parsed = Mf2Parser.parseToModel(source.toString());
                if (parsed.hasDiagnostics()) throw new AssertionError(parsed.diagnostics());
                var options = Mf2FormatOptions.builder().functions(Mf2FunctionRegistry.portable()).build();
                long[] samples = new long[5];
                for (int sample = -3; sample < samples.length; sample++) {
                    long start = System.nanoTime();
                    var result = Mf2Formatter.formatMessage(parsed.model(), Map.of(), options);
                    long elapsed = System.nanoTime() - start;
                    if (result.hasErrors() || !result.value().equals("1")) throw new AssertionError(result);
                    if (sample >= 0) samples[sample] = elapsed;
                }
                Arrays.sort(samples);
                System.out.printf("java mode=%s declarations=%d median_ms=%.3f%n", alias ? "alias" : "number", count, samples[2] / 1e6);
            }
        }
    }
}
