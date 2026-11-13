/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.BreakIterator;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.vm.Iteration;

import java.util.List;
import java.util.Map;

/** Intl.Segmenter (ECMA-402 §18). */
final class SegmenterBuiltins {
    private SegmenterBuiltins() {
    }

    private static final String[] GRANULARITIES = {"grapheme", "word", "sentence"};

    static void install(Realm realm, JSObject intl) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%Intl.Segmenter.prototype%", proto);
        var constructor = Dsl.makeCtor(realm, "Segmenter", 0, proto, (function, thisArg, args, newTarget) -> {
            if (newTarget == null) {
                throw Dsl.typeErr("Constructor Intl.Segmenter requires 'new'");
            }
            return create(function.realm, newTarget, Values.arg(args, 0), Values.arg(args, 1));
        });
        realm.getIntrinsics().put("%Intl.Segmenter%", constructor);
        intl.defineOwn("Segmenter", constructor, Attr.WC);
        Locales.installSupportedLocalesOf(realm, constructor);
        proto.defineOwn(JSSymbol.toStringTag, "Intl.Segmenter", Attr.CONFIGURABLE);
        Dsl.method(proto, realm, "resolvedOptions", 0, (function, thisArg, args, newTarget) -> {
            JSIntlSegmenter segmenter = thisSegmenter(thisArg, "resolvedOptions");
            JSObject object = Dsl.plainObject(function.realm);
            object.createDataPropertyOrThrow("locale", segmenter.locale);
            object.createDataPropertyOrThrow("granularity", segmenter.granularity);
            return object;
        });
        Dsl.method(proto, realm, "segment", 1, (function, thisArg, args, newTarget) -> {
            JSIntlSegmenter segmenter = thisSegmenter(thisArg, "segment");
            String string = Ops.toString(Values.arg(args, 0));
            return new JSIntlSegments(function.realm.intrinsic("%SegmentsPrototype%"), segmenter, string);
        });

        JSObject segmentsProto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%SegmentsPrototype%", segmentsProto);
        Dsl.method(segmentsProto, realm, "containing", 1, (function, thisArg, args, newTarget) -> {
            if (!(thisArg instanceof JSIntlSegments)) {
                throw Dsl.typeErr("Method %Segments%.prototype.containing called on incompatible receiver "
                        + Ops.describe(thisArg));
            }
            return containing(function.realm, (JSIntlSegments) thisArg, Values.arg(args, 0));
        });
        Dsl.method(segmentsProto, realm, JSSymbol.iterator, 0, (function, thisArg, args, newTarget) -> {
            if (!(thisArg instanceof JSIntlSegments)) {
                throw Dsl.typeErr("Method %Segments%.prototype[@@iterator] called on incompatible receiver "
                        + Ops.describe(thisArg));
            }
            JSIntlSegments segments = (JSIntlSegments) thisArg;
            return new JSIntlSegmentIterator(function.realm.intrinsic("%SegmentIteratorPrototype%"),
                    segments.segmenter, segments.string);
        });

        JSObject iteratorProto = new JSObject(realm.getIteratorPrototype());
        realm.getIntrinsics().put("%SegmentIteratorPrototype%", iteratorProto);
        Dsl.method(iteratorProto, realm, "next", 0, (function, thisArg, args, newTarget) -> {
            if (!(thisArg instanceof JSIntlSegmentIterator)) {
                throw Dsl.typeErr("Method %SegmentIterator%.prototype.next called on incompatible receiver "
                        + Ops.describe(thisArg));
            }
            return next(function.realm, (JSIntlSegmentIterator) thisArg);
        });
        iteratorProto.defineOwn(JSSymbol.toStringTag, "Segmenter String Iterator", Attr.CONFIGURABLE);
    }

    private static JSIntlSegmenter thisSegmenter(Object thisArg, String method) {
        if (thisArg instanceof JSIntlSegmenter) {
            return (JSIntlSegmenter) thisArg;
        }
        throw Dsl.typeErr("Method Intl.Segmenter.prototype." + method
                + " called on incompatible receiver " + Ops.describe(thisArg));
    }

    private static JSIntlSegmenter create(Realm realm, JSObject newTarget, Object locales, Object optionsArg) {
        JSIntlSegmenter segmenter = new JSIntlSegmenter(
                Dsl.protoFromCtor(newTarget, "%Intl.Segmenter.prototype%", realm));
        List<String> requested = Locales.canonicalizeLocaleList(realm, locales);
        JSObject options = Opt.getOptionsObject(optionsArg);
        Opt.localeMatcher(options);
        Locales.Resolved resolved = Locales.resolve(realm, requested, List.of(), Map.of(),
                (locale, key) -> List.of());
        segmenter.locale = resolved.locale;
        segmenter.dataLocale = resolved.dataLocale;
        segmenter.granularity = Opt.str(options, "granularity", GRANULARITIES, "grapheme");
        return segmenter;
    }

    /** A fresh break iterator over {@code text} for {@code segmenter} (a clone of the segmenter's prototype iterator). */
    private static BreakIterator newIterator(JSIntlSegmenter segmenter, String text) {
        BreakIterator proto = segmenter.icu;
        if (proto == null) {
            var locale = LocaleInfo.dataLocale(segmenter.dataLocale);
            try {
                switch (segmenter.granularity) {
                    case "word":
                        proto = BreakIterator.getWordInstance(locale);
                        break;
                    case "sentence":
                        proto = BreakIterator.getSentenceInstance(locale);
                        break;
                    default:
                        proto = BreakIterator.getCharacterInstance(locale);
                        break;
                }
            } catch (RuntimeException invalid) {
                throw Dsl.rangeErr("Internal error in segmentation data");
            }
            segmenter.icu = proto;
        }
        BreakIterator iterator = (BreakIterator) proto.clone();
        iterator.setText(text);
        return iterator;
    }

    /** FindBoundary-based segment around code unit {@code index}, with the word-likeness of that segment. */
    private static Object containing(Realm realm, JSIntlSegments segments, Object index) {
        double n = Ops.toIntegerOrInfinity(index);
        int length = segments.string.length();
        if (n < 0 || n >= length) {
            return Undefined.INSTANCE;
        }
        int i = (int) n;
        BreakIterator iterator = segments.bi;
        if (iterator == null) {
            iterator = newIterator(segments.segmenter, segments.string);
            segments.bi = iterator;
        }
        int start = iterator.isBoundary(i) ? i : iterator.preceding(i);
        int end = iterator.following(i);
        return segmentData(realm, segments.segmenter, segments.string, start, end, iterator.getRuleStatus());
    }

    private static JSObject next(Realm realm, JSIntlSegmentIterator iterator) {
        int start = iterator.nextIndex;
        int length = iterator.string.length();
        if (start >= length) {
            return Iteration.createIterResult(realm, Undefined.INSTANCE, true);
        }
        BreakIterator breakIterator = iterator.bi;
        if (breakIterator == null) {
            breakIterator = newIterator(iterator.segmenter, iterator.string);
            iterator.bi = breakIterator;
        }
        int end = breakIterator.following(start);
        iterator.nextIndex = end;
        JSObject data = segmentData(realm, iterator.segmenter, iterator.string, start, end,
                breakIterator.getRuleStatus());
        return Iteration.createIterResult(realm, data, false);
    }

    /** CreateSegmentDataObject */
    private static JSObject segmentData(Realm realm, JSIntlSegmenter segmenter, String string, int start, int end,
                                        int ruleStatus) {
        JSObject object = Dsl.plainObject(realm);
        object.createDataPropertyOrThrow("segment", string.substring(start, end));
        object.createDataPropertyOrThrow("index", (double) start);
        object.createDataPropertyOrThrow("input", string);
        if ("word".equals(segmenter.granularity)) {
            object.createDataPropertyOrThrow("isWordLike", ruleStatus >= BreakIterator.WORD_NONE_LIMIT);
        }
        return object;
    }
}
