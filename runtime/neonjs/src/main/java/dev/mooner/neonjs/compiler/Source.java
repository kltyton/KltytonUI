package dev.mooner.neonjs.compiler;

import java.util.ArrayList;
import java.util.List;

/** Source shared by a script's code blocks; derived from NeonJS and adapted to Java. */
public final class Source {
    public final String name;
    public final String text;
    public Object owner;
    private volatile int[] lineStarts;

    public Source(String name, String text) {
        this.name = name;
        this.text = text;
    }

    public String getName() {
        return name;
    }

    public String getText() {
        return text;
    }

    public LineColumn lineCol(int position) {
        int[] starts = lineStarts;
        if (starts == null) {
            List<Integer> lines = new ArrayList<>();
            lines.add(0);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n' || c == 0x2028 || c == 0x2029) {
                    lines.add(i + 1);
                } else if (c == '\r') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    lines.add(i + 1);
                }
            }
            starts = new int[lines.size()];
            for (int i = 0; i < starts.length; i++) starts[i] = lines.get(i);
            lineStarts = starts;
        }
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int middle = (lo + hi + 1) >>> 1;
            if (starts[middle] <= position) lo = middle;
            else hi = middle - 1;
        }
        return new LineColumn(lo + 1, position - starts[lo] + 1);
    }

    public record LineColumn(int line, int column) {
    }
}
