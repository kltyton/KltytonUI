/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import java.time.Instant;
import java.time.zone.ZoneRules;

/**
 * An available time zone: an offset time zone, or a named IANA time zone backed by the JDK's tz database
 * ({@code rules} is null only for "UTC" itself, which needs no lookup).
 */
public final class TimeZone {
    public final String id;
    public final boolean isOffset;
    public final int offsetMinutes;
    public final ZoneRules rules;

    /** Fixed offset in nanoseconds when the zone never changes its offset, else null. */
    public final Long fixedOffsetNs;

    /** Primary identifier (computed lazily; offset zones are their own primary identifier). */
    public volatile String primary;

    public TimeZone(String id, boolean isOffset, int offsetMinutes) {
        this(id, isOffset, offsetMinutes, null);
    }

    public TimeZone(String id, boolean isOffset, int offsetMinutes, ZoneRules rules) {
        this.id = id;
        this.isOffset = isOffset;
        this.offsetMinutes = offsetMinutes;
        this.rules = rules;
        if (rules == null) {
            this.fixedOffsetNs = getOffsetNs();
        } else if (rules.isFixedOffset()) {
            this.fixedOffsetNs = rules.getOffset(Instant.EPOCH).getTotalSeconds() * 1_000_000_000L;
        } else {
            this.fixedOffsetNs = null;
        }
    }

    public long getOffsetNs() {
        return offsetMinutes * 60_000_000_000L;
    }

    public Long getFixedOffsetNs() {
        return fixedOffsetNs;
    }

    public String getPrimary() {
        return primary;
    }

    public void setPrimary(String primary) {
        this.primary = primary;
    }

    public String getId() {
        return id;
    }

    public boolean isOffset() {
        return isOffset;
    }

    public int getOffsetMinutes() {
        return offsetMinutes;
    }

    public ZoneRules getRules() {
        return rules;
    }

    /** {@code TimeZone.UTC}. */
    public static final TimeZone UTC = new TimeZone("UTC", false, 0);

    public static TimeZone ofOffsetMinutes(int minutes) {
        return new TimeZone(TFormat.formatOffsetMinutes(minutes, true), true, minutes);
    }
}
