/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.util.ULocale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unicode BCP 47 locale identifiers: structural validation (ECMA-402 IsStructurallyValidLanguageTag, i.e. the UTS 35
 * {@code unicode_locale_id} grammar without its backwards-compatibility forms) and canonicalization
 * (CanonicalizeUnicodeLocaleId, done by ICU's UTS 35 alias replacement plus the syntactic normalisation below).
 *
 * <p>The only mutable state is a bounded, concurrent cache of canonicalized tags (immutable strings).
 * Java adaptation: Kotlin {@code Pair} values are represented by the domain types {@link Keyword} and
 * {@link Components}.
 */
public final class LanguageTag {
    private LanguageTag() {
    }

    /** Longest tag accepted (well above any real tag; keeps ICU from processing adversarially long input). */
    public static final int MAX_LENGTH = 512;

    private static final int CACHE_LIMIT = 4096;
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    /** Keys whose "yes" value is an alias of "true" (UTS 35); ICU drops "yes" for every key. */
    private static final Set<String> YES_IS_TRUE = Set.of("kb", "kc", "kh", "kk", "kn");

    public static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    public static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    public static boolean isAlnum(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private static boolean allAlpha(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!isAlpha(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean allDigit(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean allAlnum(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!isAlnum(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isLanguageSubtag(String value) {
        int length = value.length();
        return ((length >= 2 && length <= 3) || (length >= 5 && length <= 8)) && allAlpha(value);
    }

    public static boolean isScriptSubtag(String value) {
        return value.length() == 4 && allAlpha(value);
    }

    public static boolean isRegionSubtag(String value) {
        return (value.length() == 2 && allAlpha(value)) || (value.length() == 3 && allDigit(value));
    }

    public static boolean isVariantSubtag(String value) {
        int length = value.length();
        if (length >= 5 && length <= 8) {
            return allAlnum(value);
        }
        return length == 4 && isDigit(value.charAt(0)) && allAlnum(value);
    }

    private static boolean isAlnumRange(String value, int min, int max) {
        return value.length() >= min && value.length() <= max && allAlnum(value);
    }

    /** A canonical Unicode extension keyword ("value" is "" for a key without a value). */
    public static final class Keyword {
        public final String key;
        public final String value;

        public Keyword(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    /** Attributes and keywords of a "u-..." extension. */
    public static final class Components {
        public final List<String> attributes;
        public final List<Keyword> keywords;

        public Components(List<String> attributes, List<Keyword> keywords) {
            this.attributes = attributes;
            this.keywords = keywords;
        }
    }

    /** A parsed (lowercase) locale identifier. Extensions keep their singleton, e.g. "u-ca-gregory". */
    public static final class Parsed {
        public final String language;
        public final String script;
        public final String region;
        public final List<String> variants;
        public final List<String> extensions;
        public final String privateUse;

        public Parsed(String language, String script, String region, List<String> variants,
                      List<String> extensions, String privateUse) {
            this.language = language;
            this.script = script;
            this.region = region;
            this.variants = variants;
            this.extensions = extensions;
            this.privateUse = privateUse;
        }
    }

    /** Parses {@code tag} (any case) as a {@code unicode_locale_id}; null when it is not structurally valid. */
    public static Parsed parse(String tag) {
        if (tag.isEmpty() || tag.length() > MAX_LENGTH) {
            return null;
        }
        for (int c = 0; c < tag.length(); c++) {
            char ch = tag.charAt(c);
            if (!isAlnum(ch) && ch != '-') {
                return null;
            }
        }
        String[] subtags = tag.toLowerCase(Locale.ROOT).split("-", -1);
        for (String subtag : subtags) {
            if (subtag.isEmpty()) {
                return null;
            }
        }
        List<String> st = List.of(subtags);
        int n = st.size();
        int i = 0;
        if (!isLanguageSubtag(st.get(0))) {
            return null;
        }
        String language = st.get(i++);
        String script = null;
        String region = null;
        if (i < n && isScriptSubtag(st.get(i))) {
            script = st.get(i++);
        }
        if (i < n && isRegionSubtag(st.get(i))) {
            region = st.get(i++);
        }
        List<String> variants = new ArrayList<>();
        while (i < n && isVariantSubtag(st.get(i))) {
            if (variants.contains(st.get(i))) {
                return null;
            }
            variants.add(st.get(i++));
        }
        List<String> extensions = new ArrayList<>();
        Set<Character> seen = new HashSet<>();
        String privateUse = null;
        while (i < n) {
            String subtag = st.get(i);
            if (subtag.length() != 1) {
                return null;
            }
            char singleton = subtag.charAt(0);
            if (singleton == 'x') {
                i++;
                if (i >= n) {
                    return null;
                }
                int start = i;
                while (i < n) {
                    if (!isAlnumRange(st.get(i), 1, 8)) {
                        return null;
                    }
                    i++;
                }
                privateUse = "x-" + String.join("-", st.subList(start, n));
                break;
            }
            if (!seen.add(singleton)) {
                return null;
            }
            int start = i;
            i++;
            if (singleton == 'u') {
                i = parseUnicodeExtension(st, i);
            } else if (singleton == 't') {
                i = parseTransformedExtension(st, i);
            } else {
                i = parseOtherExtension(st, i);
            }
            if (i < 0) {
                return null;
            }
            extensions.add(String.join("-", st.subList(start, i)));
        }
        return new Parsed(language, script, region, variants, extensions, privateUse);
    }

    /** Returns the index after the extension, or -1. */
    private static int parseUnicodeExtension(List<String> st, int start) {
        int i = start;
        int count = 0;
        while (i < st.size() && isAlnumRange(st.get(i), 3, 8)) {
            i++;
            count++;
        }
        while (i < st.size() && isUnicodeKey(st.get(i))) {
            i++;
            count++;
            while (i < st.size() && isAlnumRange(st.get(i), 3, 8)) {
                i++;
            }
        }
        return count == 0 ? -1 : i;
    }

    public static boolean isUnicodeKey(String value) {
        return value.length() == 2 && isAlnum(value.charAt(0)) && isAlpha(value.charAt(1));
    }

    private static boolean isTKey(String value) {
        return value.length() == 2 && isAlpha(value.charAt(0)) && isDigit(value.charAt(1));
    }

    private static int parseTransformedExtension(List<String> st, int start) {
        int i = start;
        int count = 0;
        if (i < st.size() && isLanguageSubtag(st.get(i))) {
            i++;
            count++;
            if (i < st.size() && isScriptSubtag(st.get(i))) {
                i++;
            }
            if (i < st.size() && isRegionSubtag(st.get(i))) {
                i++;
            }
            Set<String> variants = new HashSet<>();
            while (i < st.size() && isVariantSubtag(st.get(i))) {
                if (!variants.add(st.get(i))) {
                    return -1;
                }
                i++;
            }
        }
        while (i < st.size() && isTKey(st.get(i))) {
            i++;
            int values = 0;
            while (i < st.size() && isAlnumRange(st.get(i), 3, 8)) {
                i++;
                values++;
            }
            if (values == 0) {
                return -1;
            }
            count++;
        }
        return count == 0 ? -1 : i;
    }

    private static int parseOtherExtension(List<String> st, int start) {
        int i = start;
        while (i < st.size() && isAlnumRange(st.get(i), 2, 8)) {
            i++;
        }
        return i == start ? -1 : i;
    }

    /** IsStructurallyValidLanguageTag */
    public static boolean isStructurallyValid(String tag) {
        return parse(tag) != null;
    }

    // ------------------------------------------------------------------ canonicalization

    /**
     * CanonicalizeUnicodeLocaleId for a tag that {@link #parse} accepted. Throws a RangeError for invalid tags so callers
     * can use it as "validate and canonicalize".
     */
    public static String canonicalize(String tag) {
        String cached = CACHE.get(tag);
        if (cached != null) {
            return cached;
        }
        Parsed parsed = parse(tag);
        if (parsed == null) {
            throw Dsl.rangeErr("Incorrect locale information provided: " + tag);
        }
        String result = canonicalizeParsed(parsed);
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(tag, result);
        return result;
    }

    private static String canonicalizeParsed(Parsed parsed) {
        // ICU turns the variant "posix" into "-u-va-posix"; keep it as a variant
        boolean posix = parsed.variants.contains("posix");
        List<String> inputVariants = new ArrayList<>(parsed.variants);
        if (posix) {
            inputVariants.remove("posix");
        }
        String input = build(parsed.language, parsed.script, parsed.region, inputVariants,
                parsed.extensions, parsed.privateUse);
        String icuTag = null;
        try {
            icuTag = ULocale.createCanonical(new ULocale.Builder().setLanguageTag(input).build()).toLanguageTag();
        } catch (Exception ignored) {
            icuTag = null;
        }
        Parsed canonicalParsed = icuTag == null ? null : parse(icuTag);
        Parsed q = canonicalParsed != null ? canonicalParsed : parsed;
        List<String> variants = q.variants;
        if (posix && !q.variants.contains("posix")) {
            variants = new ArrayList<>(q.variants);
            variants.add("posix");
        }
        List<String> extensions = restoreYesValues(parsed.extensions, q.extensions);
        return normalize(q.language, q.script, q.region, variants, extensions, q.privateUse);
    }

    private static List<String> restoreYesValues(List<String> original, List<String> canonical) {
        String unicode = null;
        for (String extension : original) {
            if (!extension.isEmpty() && extension.charAt(0) == 'u') {
                unicode = extension;
                break;
            }
        }
        if (unicode == null) {
            return canonical;
        }
        Components originalComponents = unicodeComponents(List.of(unicode.split("-", -1)), 1);
        List<String> yesKeys = new ArrayList<>();
        for (Keyword keyword : originalComponents.keywords) {
            if ("yes".equals(keyword.value) && !YES_IS_TRUE.contains(keyword.key)) {
                yesKeys.add(keyword.key);
            }
        }
        if (yesKeys.isEmpty()) {
            return canonical;
        }
        List<String> result = new ArrayList<>(canonical.size());
        for (String extension : canonical) {
            if (extension.isEmpty() || extension.charAt(0) != 'u') {
                result.add(extension);
                continue;
            }
            Components components = unicodeComponents(List.of(extension.split("-", -1)), 1);
            List<Keyword> keywords = new ArrayList<>(components.keywords.size());
            for (Keyword keyword : components.keywords) {
                if (yesKeys.contains(keyword.key) && keyword.value.isEmpty()) {
                    keywords.add(new Keyword(keyword.key, "yes"));
                } else {
                    keywords.add(keyword);
                }
            }
            result.add(buildUnicodeExtension(components.attributes, keywords));
        }
        return result;
    }

    /** Case regularization and canonical ordering (variants, extensions, u keywords and attributes, t fields). */
    private static String normalize(String language, String script, String region, List<String> variants,
                                    List<String> extensions, String privateUse) {
        List<String> exts = new ArrayList<>();
        for (String extension : extensions) {
            String normalized = normalizeExtension(extension.toLowerCase(Locale.ROOT));
            if (!normalized.isEmpty()) {
                exts.add(normalized);
            }
        }
        exts.sort(Comparator.comparingInt(value -> value.isEmpty() ? 0 : value.charAt(0)));
        List<String> normalizedVariants = new ArrayList<>(variants.size());
        for (String variant : variants) {
            normalizedVariants.add(variant.toLowerCase(Locale.ROOT));
        }
        Collections.sort(normalizedVariants);
        return build(language.toLowerCase(Locale.ROOT), script, region, normalizedVariants,
                exts, privateUse == null ? null : privateUse.toLowerCase(Locale.ROOT));
    }

    private static String normalizeExtension(String extension) {
        List<String> st = List.of(extension.split("-", -1));
        if (st.isEmpty()) {
            return extension;
        }
        switch (st.get(0).charAt(0)) {
            case 'u': {
                Components components = unicodeComponents(st, 1);
                return buildUnicodeExtension(components.attributes, components.keywords);
            }
            case 't':
                return normalizeTransformed(st);
            default:
                return extension;
        }
    }

    private static String normalizeTransformed(List<String> st) {
        int i = 1;
        List<String> tlang = new ArrayList<>();
        if (i < st.size() && isLanguageSubtag(st.get(i))) {
            tlang.add(st.get(i++));
            if (i < st.size() && isScriptSubtag(st.get(i))) {
                tlang.add(st.get(i++));
            }
            if (i < st.size() && isRegionSubtag(st.get(i))) {
                tlang.add(st.get(i++));
            }
            List<String> variants = new ArrayList<>();
            while (i < st.size() && isVariantSubtag(st.get(i))) {
                variants.add(st.get(i++));
            }
            Collections.sort(variants);
            tlang.addAll(variants);
        }
        List<Keyword> fields = new ArrayList<>();
        while (i < st.size()) {
            String key = st.get(i++);
            List<String> values = new ArrayList<>();
            while (i < st.size() && !isTKey(st.get(i))) {
                values.add(st.get(i++));
            }
            fields.add(new Keyword(key, String.join("-", values)));
        }
        StringBuilder builder = new StringBuilder("t");
        for (String value : tlang) {
            builder.append('-').append(value);
        }
        fields.sort(Comparator.comparing(field -> field.key));
        for (Keyword field : fields) {
            builder.append('-').append(field.key).append('-').append(field.value);
        }
        return builder.toString();
    }

    public static String build(String language, String script, String region, List<String> variants,
                               List<String> extensions, String privateUse) {
        StringBuilder builder = new StringBuilder(language);
        if (script != null && !script.isEmpty()) {
            builder.append('-').append(Character.toUpperCase(script.charAt(0)))
                    .append(script.substring(1).toLowerCase(Locale.ROOT));
        }
        if (region != null) {
            builder.append('-').append(region.toUpperCase(Locale.ROOT));
        }
        for (String variant : variants) {
            builder.append('-').append(variant);
        }
        for (String extension : extensions) {
            builder.append('-').append(extension);
        }
        if (privateUse != null) {
            builder.append('-').append(privateUse);
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ Unicode extension helpers

    /** Attributes and (key, value) keywords of a "u-..." extension split into subtags, starting at {@code from}. */
    public static Components unicodeComponents(List<String> st, int from) {
        List<String> attrs = new ArrayList<>();
        List<Keyword> keywords = new ArrayList<>();
        int i = from;
        while (i < st.size() && !isUnicodeKey(st.get(i))) {
            if (!attrs.contains(st.get(i))) {
                attrs.add(st.get(i));
            }
            i++;
        }
        while (i < st.size()) {
            String key = st.get(i++);
            List<String> values = new ArrayList<>();
            while (i < st.size() && !isUnicodeKey(st.get(i))) {
                values.add(st.get(i++));
            }
            boolean present = false;
            for (Keyword keyword : keywords) {
                if (keyword.key.equals(key)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                keywords.add(new Keyword(key, String.join("-", values)));
            }
        }
        return new Components(attrs, keywords);
    }

    /** Builds a canonical "u-..." extension ("" if empty); "true" values are dropped, keys and attributes sorted. */
    public static String buildUnicodeExtension(List<String> attrs, List<Keyword> keywords) {
        if (attrs.isEmpty() && keywords.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("u");
        List<String> sortedAttrs = new ArrayList<>(new LinkedHashSet<>(attrs));
        Collections.sort(sortedAttrs);
        for (String attr : sortedAttrs) {
            builder.append('-').append(attr);
        }
        List<Keyword> sortedKeywords = new ArrayList<>(keywords);
        sortedKeywords.sort(Comparator.comparing(keyword -> keyword.key));
        for (Keyword keyword : sortedKeywords) {
            builder.append('-').append(keyword.key);
            if (!keyword.value.isEmpty() && !"true".equals(keyword.value)) {
                builder.append('-').append(keyword.value);
            }
        }
        return builder.toString();
    }

    /** Splits a canonical tag into (baseName, extensions, privateUse). */
    public static Parsed split(String tag) {
        Parsed parsed = parse(tag);
        if (parsed == null) {
            throw new IllegalStateException("not a valid tag: " + tag);
        }
        return parsed;
    }

    /** The Unicode locale extension of a canonical tag as (attributes, keywords), or empty lists. */
    public static Components unicodeExtensionOf(String tag) {
        Parsed parsed = parse(tag);
        if (parsed == null) {
            return new Components(List.of(), List.of());
        }
        String unicode = null;
        for (String extension : parsed.extensions) {
            if (!extension.isEmpty() && extension.charAt(0) == 'u') {
                unicode = extension;
                break;
            }
        }
        if (unicode == null) {
            return new Components(List.of(), List.of());
        }
        return unicodeComponents(List.of(unicode.split("-", -1)), 1);
    }

    /** The value of Unicode extension {@code key} in canonical {@code tag}: null if absent, "" for a key without a value. */
    public static String unicodeKeyword(String tag, String key) {
        for (Keyword keyword : unicodeExtensionOf(tag).keywords) {
            if (keyword.key.equals(key)) {
                return keyword.value;
            }
        }
        return null;
    }

    /** The locale without Unicode locale extension sequences. */
    public static String removeUnicodeExtension(String tag) {
        Parsed parsed = parse(tag);
        if (parsed == null) {
            return tag;
        }
        boolean present = false;
        for (String extension : parsed.extensions) {
            if (!extension.isEmpty() && extension.charAt(0) == 'u') {
                present = true;
                break;
            }
        }
        if (!present) {
            return tag;
        }
        List<String> extensions = new ArrayList<>();
        for (String extension : parsed.extensions) {
            if (extension.isEmpty() || extension.charAt(0) != 'u') {
                extensions.add(extension);
            }
        }
        return build(parsed.language, parsed.script, parsed.region, parsed.variants, extensions, parsed.privateUse);
    }

    /** GetLocaleBaseName: language, script, region and variants. */
    public static String baseName(String tag) {
        Parsed parsed = parse(tag);
        if (parsed == null) {
            return tag;
        }
        return build(parsed.language, parsed.script, parsed.region, parsed.variants, List.of(), null);
    }

    /** InsertUnicodeExtensionAndCanonicalize */
    public static String insertUnicodeExtensionAndCanonicalize(String locale, List<String> attrs,
                                                               List<Keyword> keywords) {
        Parsed parsed = parse(locale);
        if (parsed == null) {
            return locale;
        }
        String unicode = buildUnicodeExtension(attrs, keywords);
        List<String> others = new ArrayList<>();
        for (String extension : parsed.extensions) {
            if (extension.isEmpty() || extension.charAt(0) != 'u') {
                others.add(extension);
            }
        }
        List<String> extensions = new ArrayList<>(others);
        if (!unicode.isEmpty()) {
            extensions.add(unicode);
        }
        extensions.sort(Comparator.comparingInt(value -> value.isEmpty() ? 0 : value.charAt(0)));
        return canonicalize(build(parsed.language, parsed.script, parsed.region, parsed.variants,
                extensions, parsed.privateUse));
    }

    /** CanonicalizeUValue(key, value) for a lowercase value: the canonical value per UTS 35 ("" for "true"). */
    public static String canonicalizeUValue(String key, String value) {
        if (value.isEmpty() || "true".equals(value)) {
            return "";
        }
        String tag = "und-u-" + key + "-" + value;
        if (!isStructurallyValid(tag)) {
            return value;
        }
        String canonical = unicodeKeyword(canonicalize(tag), key);
        return canonical != null ? canonical : value;
    }
}
