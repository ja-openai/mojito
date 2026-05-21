package com.box.l10n.mojito.mf2;

final class BenchmarkSupport {
    private BenchmarkSupport() {}

    static int utf8Length(String value) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch <= 0x7F) {
                bytes++;
            } else if (ch <= 0x7FF) {
                bytes += 2;
            } else if (Character.isHighSurrogate(ch)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
