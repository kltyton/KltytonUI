/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.regexp.RegExpMatcher;

/**
 * The legacy static slots of a realm's %RegExp% (proposal-regexp-legacy-features). The match slots are kept as
 * positions into {@link #subject} and are only materialized by the accessors, so a successful exec copies no
 * substrings.
 */
public final class LegacyRegExpStatics {
    /** Input; null when empty (invalidated). */
    public String input = "";
    /** Subject of the recorded match; null when the match slots are empty (invalidated). */
    public String subject = "";
    /** Start / end of the match (0, 1), of captures 1-9 (2..19, -1 when unmatched) and of the last capture (20, 21). */
    public final int[] pos = new int[22];

    public LegacyRegExpStatics() {
        java.util.Arrays.fill(pos, -1);
        pos[0] = 0;
        pos[1] = 0;
    }

    /** UpdateLegacyRegExpStaticProperties from the current match of {@code m}. */
    public void update(String s, int start, int end, RegExpMatcher m, int captures) {
        input = s;
        subject = s;
        pos[0] = start;
        pos[1] = end;
        for (int i = 1; i <= 9; i++) {
            pos[2 * i] = i <= captures ? m.start(i) : -1;
            pos[2 * i + 1] = i <= captures ? m.end(i) : -1;
        }
        pos[20] = captures > 0 ? m.start(captures) : -1;
        pos[21] = captures > 0 ? m.end(captures) : -1;
    }

    /** UpdateLegacyRegExpStaticProperties from capture positions in the layout of the fast replace/split paths. */
    public void update(String s, int[] caps) {
        input = s;
        subject = s;
        int captures = caps.length / 2 - 1;
        pos[0] = caps[0];
        pos[1] = caps[1];
        for (int i = 1; i <= 9; i++) {
            pos[2 * i] = i <= captures ? caps[2 * i] : -1;
            pos[2 * i + 1] = i <= captures ? caps[2 * i + 1] : -1;
        }
        pos[20] = captures > 0 ? caps[2 * captures] : -1;
        pos[21] = captures > 0 ? caps[2 * captures + 1] : -1;
    }

    /** InvalidateLegacyRegExpStaticProperties. */
    public void invalidate() {
        input = null;
        subject = null;
    }

    /** Match slot {@code k} (0 = lastMatch, 1-9 = $1-$9, 10 = lastParen); "" for unmatched captures, null when empty. */
    public String slot(int k) {
        String s = subject;
        if (s == null) return null;
        int i = k == 10 ? 20 : 2 * k;
        int a = pos[i];
        int b = pos[i + 1];
        return a < 0 || b < 0 ? "" : s.substring(a, b);
    }

    public String leftContext() {
        String s = subject;
        return s == null ? null : s.substring(0, pos[0]);
    }

    public String rightContext() {
        String s = subject;
        return s == null ? null : s.substring(pos[1]);
    }
}
