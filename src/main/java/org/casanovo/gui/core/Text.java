package org.casanovo.gui.core;

/** Small text helpers shared by the pieces that report a failed command's output. */
final class Text {

    private Text() {
    }

    /**
     * The tail of {@code text}, collapsed onto one line and prefixed with an em dash, ready to
     * append to a failure message; empty when there is nothing to say.
     *
     * <p>Truncation counts code points, not {@code char}s: cutting on a UTF-16 index can split a
     * surrogate pair and leave a broken character in a console line or a dialog. Every kind of
     * line break is collapsed, including the bare carriage returns a progress bar leaves in
     * merged stderr.</p>
     *
     * @param maxCodePoints how much of the tail to keep
     */
    static String tail(String text, int maxCodePoints) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || maxCodePoints < 1) {
            return ""; // asked to keep nothing: there is nothing to append
        }
        if (trimmed.codePointCount(0, trimmed.length()) > maxCodePoints) {
            trimmed = "\u2026" + trimmed.substring(
                    trimmed.offsetByCodePoints(trimmed.length(), -maxCodePoints + 1));
        }
        return " \u2014 " + trimmed.replaceAll("\\R", " ");
    }
}
