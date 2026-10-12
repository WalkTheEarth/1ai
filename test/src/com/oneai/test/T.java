package com.oneai.test;

/** Tiny assertion harness. Avoids pulling a test framework into the build. */
public final class T {

    private static int passed;
    private static int failed;
    private static String current = "";
    private static final StringBuilder failures = new StringBuilder();

    public static void test(String name, Runnable body) {
        current = name;
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL  " + name);
            System.out.println("        " + t);
            failures.append(name).append(": ").append(t).append('\n');
        }
    }

    public static void isTrue(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError(what);
        }
    }

    public static void eq(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void contains(String haystack, String needle, String what) {
        if (haystack == null || haystack.indexOf(needle) < 0) {
            throw new AssertionError(what + ": <" + haystack + "> does not contain <" + needle + ">");
        }
    }

    public static void lacks(String haystack, String needle, String what) {
        if (haystack != null && haystack.indexOf(needle) >= 0) {
            throw new AssertionError(what + ": <" + haystack + "> unexpectedly contains <"
                    + needle + ">");
        }
    }

    public static int report() {
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.out.println();
            System.out.print(failures);
        }
        return failed == 0 ? 0 : 1;
    }

    public static String section(String title) {
        System.out.println();
        System.out.println(title);
        return title;
    }
}
