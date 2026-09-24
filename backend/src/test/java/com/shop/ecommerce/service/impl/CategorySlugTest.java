package com.shop.ecommerce.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The slug rule, tested in isolation and under a hostile locale.
 *
 * <h2>Why this test lives in {@code service.impl} rather than {@code service}</h2>
 *
 * <p>{@code CategoryServiceImpl.slugify} is package-private, and it stays that way. Widening
 * it to {@code public} so a test in another package could call it would be a change to the
 * production API made for the test's convenience - and the wide visibility would then be
 * permanent, because nothing ever narrows a signature back.
 *
 * <p>The method is package-private for a real reason: it is an implementation detail of how
 * a category derives its URL. Nothing outside the service should depend on the rule, because
 * the rule may change. A test that sits in the same package as the code tests exactly what
 * the code exposes, with no modification to the code.
 *
 * <h2>Why the slug deserves direct tests at all</h2>
 *
 * <p>A slug is part of a category's public URL, so it is written once and then relied on
 * forever: a bookmarked link, a sitemap entry a crawler has indexed, a QR code in a printed
 * catalogue. Changing the rule silently breaks all of them. That makes it worth pinning every
 * branch of the transformation directly rather than inferring it from whether a category
 * happened to be creatable.
 *
 * <h2>The locale test is the interesting one</h2>
 *
 * <p>{@code "I".toLowerCase()} is not {@code "i"} in every locale. In Turkish it is
 * {@code "ı"} - a dotless i, U+0131 - and {@code "i".toUpperCase()} is {@code "İ"}, a dotted
 * capital I. Java's default locale is a property of the JVM's environment, so
 * {@code String.toLowerCase()} without an argument produces a different slug on a Turkish
 * server than on an Indian one.
 *
 * <p>The failure mode is genuinely nasty because it is silent and environment-dependent:
 * "Istanbul" becomes {@code "ıstanbul"} in one deployment and {@code "istanbul"} in another,
 * so the same category has two URLs depending on which machine answered the request. Nothing
 * logs, nothing throws, and the bug does not reproduce locally.
 *
 * <p>{@code CategoryServiceImpl.slugify} uses {@code Locale.ROOT} to prevent exactly this.
 * The test below <b>changes the default locale</b> to Turkish and asserts the output is
 * unchanged - which is the only way to prove the {@code Locale.ROOT} argument is doing
 * something. A test that left the locale alone would pass whether or not it was there.
 *
 * <p>Note that mutating the default locale is process-wide state and would affect every
 * other test running concurrently, so it is restored in {@code @AfterEach} unconditionally -
 * including after a failure, which is what {@code @AfterEach} guarantees and a {@code try}
 * block inside one test method would not if the test were ever split up.
 */
@DisplayName("CategoryService.slugify")
class CategorySlugTest {

    private final Locale originalLocale = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        /*
         * Restored unconditionally, and this matters more than it looks. Surefire runs test
         * classes in one JVM, so leaving the default locale set to Turkish would make every
         * subsequent test that formats a date, lowercases a string or renders a message in a
         * locale-sensitive way behave differently - and the resulting failures would point
         * at whichever class happened to run after this one, not at this one.
         */
        Locale.setDefault(originalLocale);
    }

    // =================================================================
    //  The basic transformation
    // =================================================================

    @Nested
    @DisplayName("basic transformation")
    class Basic {

        @Test
        @DisplayName("lowercases and hyphenates a plain name")
        void plainName() {
            assertThat(CategoryServiceImpl.slugify("Linen Shirts")).isEqualTo("linen-shirts");
        }

        @Test
        @DisplayName("collapses runs of separators into a single hyphen")
        void collapsesSeparators() {
            /*
             * The realistic input is a name somebody typed by hand - "Books  &  Media" with
             * two spaces, an ampersand and two more spaces. Without the collapse this becomes
             * books--media or books---media, and the URL looks broken to a human even though
             * it works.
             */
            assertThat(CategoryServiceImpl.slugify("Books  &  Media")).isEqualTo("books-media");
            assertThat(CategoryServiceImpl.slugify("Home / Kitchen")).isEqualTo("home-kitchen");
        }

        @Test
        @DisplayName("trims leading and trailing hyphens")
        void trimsEdges() {
            /*
             * The collapse leaves a hyphen at each end when the name starts or ends with a
             * separator - "  Shoes  " becomes "-shoes-" before the trim. A leading hyphen is
             * legal in a URL path segment but reads like an accident, and a trailing one is
             * easy to lose when somebody retypes the link.
             */
            assertThat(CategoryServiceImpl.slugify("  Shoes  ")).isEqualTo("shoes");
            assertThat(CategoryServiceImpl.slugify("-Electronics-")).isEqualTo("electronics");
        }

        @Test
        @DisplayName("strips punctuation and digits are kept")
        void punctuationAndDigits() {
            /* Digits are meaningful: "USB-C 3.0 Cables" must not become "usb-c-cables". */
            assertThat(CategoryServiceImpl.slugify("Men's Shoes!")).isEqualTo("men-s-shoes");
            assertThat(CategoryServiceImpl.slugify("USB-C 3.0 Cables")).isEqualTo("usb-c-3-0-cables");
        }

        @Test
        @DisplayName("a name with no alphanumerics at all falls back rather than producing an empty slug")
        void fallbackForUnusableNames() {
            /*
             * The branch that prevents a NOT NULL violation. A name of "!!!" or "———" reduces
             * to the empty string, and returning that would produce an empty slug, a unique
             * index collision on the second such category, and a URL ending in a bare slash.
             *
             * A constant fallback is the honest answer: the slug is a URL identifier, not a
             * name, and the display name is stored separately and unchanged.
             */
            assertThat(CategoryServiceImpl.slugify("!!!")).isEqualTo("category");
            assertThat(CategoryServiceImpl.slugify("———")).isEqualTo("category");
            assertThat(CategoryServiceImpl.slugify("   ")).isEqualTo("category");
            assertThat(CategoryServiceImpl.slugify("")).isEqualTo("category");
        }
    }

    // =================================================================
    //  Accents and locale
    // =================================================================

    @Nested
    @DisplayName("accents and locale")
    class AccentsAndLocale {

        @Test
        @DisplayName("accented Latin letters are decomposed to their base letter")
        void accentsAreFolded() {
            /*
             * The Normalizer.normalize(NFD) step exists for this. NFD splits a precomposed
             * character into a base letter plus a combining mark: "é" becomes "e" + U+0301.
             * The mark is then matched by \p{M} and stripped, leaving "e".
             *
             * Two things this buys:
             *
             * 1. The slug stays ASCII, so it needs no percent-encoding in a URL - "cafe"
             *    rather than "caf%C3%A9" in every link, log line and crawler report.
             *
             * 2. It is reversible by a human. A customer who sees /categories/cafe can read
             *    it; one who sees /categories/caf%C3%A9 cannot tell whether the link is
             *    broken.
             *
             * Doing this by regex (say, stripping everything outside [a-z0-9]) without the
             * normalisation step would delete the letter entirely and yield "caf", which
             * looks like a typo rather than a transliteration.
             */
            assertThat(CategoryServiceImpl.slugify("Café")).isEqualTo("cafe");
            assertThat(CategoryServiceImpl.slugify("Café & Restaurant")).isEqualTo("cafe-restaurant");
            assertThat(CategoryServiceImpl.slugify("Naïve Décor")).isEqualTo("naive-decor");
            assertThat(CategoryServiceImpl.slugify("Über")).isEqualTo("uber");
            assertThat(CategoryServiceImpl.slugify("Señor"))  .isEqualTo("senor");
        }

        @Test
        @DisplayName("a Turkish default locale does not change the slug")
        void turkishLocaleDoesNotAffectTheSlug() {
            /*
             * =================================================================
             *  THE TEST THE Locale.ROOT ARGUMENT EXISTS FOR.
             * =================================================================
             *
             * Everything below runs with the JVM's default locale set to Turkish, where
             * String.toLowerCase() maps 'I' to 'ı' (U+0131, dotless i) instead of 'i'.
             *
             * Without Locale.ROOT, "Istanbul" would produce "ıstanbul" on a Turkish server
             * and "istanbul" everywhere else - the same category, two URLs, neither wrong
             * from the code's point of view. The bug is silent, environment-dependent, and
             * does not reproduce on the developer's machine.
             *
             * This is not hypothetical: it is a documented class of bug that has broken
             * Java applications in production for two decades, because the JVM's default
             * locale comes from the host environment and changes with the data centre.
             *
             * The assertions compare against the SAME expected values as the locale-neutral
             * tests above. That is the point: the output must not depend on the locale at
             * all.
             */
            Locale.setDefault(new Locale("tr", "TR"));

            /*
             * The canary. If the JVM's Turkish behaviour ever changes, this assertion fails
             * and tells us the test below has stopped proving anything - rather than the
             * test quietly passing because the trap it targets no longer exists.
             */
            assertThat("I".toLowerCase())
                    .as("the Turkish locale really does produce a dotless i; if this fails, "
                            + "the locale-sensitive test below is no longer meaningful")
                    .isEqualTo("\u0131");

            // The tests that would catch a missing Locale.ROOT.
            assertThat(CategoryServiceImpl.slugify("Istanbul")).isEqualTo("istanbul");
            assertThat(CategoryServiceImpl.slugify("IKEA Furniture")).isEqualTo("ikea-furniture");
            assertThat(CategoryServiceImpl.slugify("IPHONE Cases")).isEqualTo("iphone-cases");
            assertThat(CategoryServiceImpl.slugify("Illustrated Books")).isEqualTo("illustrated-books");

            /*
             * And a Turkish-specific name, since that is the realistic input: "İçecekler"
             * (beverages) with a dotted capital I. NFD decomposes İ into I + U+0307, the
             * combining dot above is stripped, and the lowercase lands on "i".
             *
             * Note this passes for the same reason as the others and would also have passed
             * with Locale.ROOT absent in one direction - which is why the canary above and
             * the ASCII 'I' cases matter more than this one. It is here because it is what a
             * Turkish admin would actually type.
             */
            assertThat(CategoryServiceImpl.slugify("İçecekler")).isEqualTo("icecekler");
        }

        @Test
        @DisplayName("non-Latin scripts reduce to the fallback rather than producing mojibake")
        void nonLatinScriptsFallBack() {
            /*
             * A limitation, asserted so it is a known one rather than a surprise.
             *
             * NFD normalisation decomposes accented Latin letters; it does not transliterate.
             * Devanagari, Cyrillic, CJK and Arabic have no ASCII base letter to decompose to,
             * so every character is stripped by the [^a-z0-9] rule and the slug falls back to
             * the constant.
             *
             * The consequence for a shop with categories in those scripts: every such
             * category has the slug "category", and the second one gets "category-2" from
             * uniqueSlug. The URLs work and are unique - which is what the rule requires -
             * but they carry no information. A real deployment serving those markets would
             * want a transliteration library, and the point of this test is that the choice
             * is visible rather than latent.
             *
             * Asserting the current behaviour is the right call: it documents the boundary,
             * and it will fail loudly the day somebody adds transliteration and needs to
             * think about whether existing slugs should change.
             */
            assertThat(CategoryServiceImpl.slugify("पुस्तकें")).isEqualTo("category");
            assertThat(CategoryServiceImpl.slugify("Книги")).isEqualTo("category");
            assertThat(CategoryServiceImpl.slugify("书籍")).isEqualTo("category");
            assertThat(CategoryServiceImpl.slugify("كتب")).isEqualTo("category");
        }
    }
}
