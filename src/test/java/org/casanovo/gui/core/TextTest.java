package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tail of a failed command's output ends up in a console line and in an error dialog, so it
 * has to be one line and it has to be printable — both places show it verbatim.
 */
class TextTest {

    @Test
    @DisplayName("Nothing to say produces nothing")
    void emptyStaysEmpty() {
        assertEquals("", Text.tail(null, 10));
        assertEquals("", Text.tail("   \n  ", 10));
    }

    @Test
    @DisplayName("Every kind of line break is collapsed, carriage returns included")
    void lineBreaksAreCollapsed() {
        String tail = Text.tail("error: could not resolve\r\n  hint: retry\rprogress\n", 400);
        assertEquals(" — error: could not resolve   hint: retry progress", tail);
        assertFalse(tail.contains("\r"), tail);
        assertFalse(tail.contains("\n"), tail);
    }

    @Test
    @DisplayName("Truncation counts code points, so it cannot split a character in half")
    void truncationDoesNotSplitASurrogatePair() {
        // Four astral characters: eight chars, four code points. Keeping three must not cut
        // through one of them and leave a lone surrogate in the dialog.
        String astral = "🚀🚀🚀🚀";
        String tail = Text.tail(astral, 3);

        assertTrue(tail.startsWith(" — …"), tail);
        String kept = tail.substring(" — …".length());
        assertEquals(2, kept.codePointCount(0, kept.length()), "two whole characters kept");
        for (int i = 0; i < kept.length(); i++) {
            if (Character.isHighSurrogate(kept.charAt(i))) {
                assertTrue(i + 1 < kept.length() && Character.isLowSurrogate(kept.charAt(i + 1)),
                        "a high surrogate must keep its pair");
            }
        }
    }

    @Test
    @DisplayName("A budget of nothing yields nothing, rather than an exception from a constructor")
    void nonPositiveBudgetIsTotal() {
        // Both callers pass 400, but tail() is used from inside CommandFailed's constructor and
        // from a probe failure message: a throw there would replace the real failure with an
        // unrelated crash, so the helper has to be total.
        assertEquals("", Text.tail("a long line of output", 0));
        assertEquals("", Text.tail("a long line of output", -5));
        assertEquals(" — …", Text.tail("a long line of output", 1),
                "a budget of one is spent on the ellipsis");
    }

    @Test
    @DisplayName("Short text is kept whole, with no ellipsis")
    void shortTextIsUntouched() {
        assertEquals(" — exit 2: no such option", Text.tail("exit 2: no such option", 400));
    }
}
