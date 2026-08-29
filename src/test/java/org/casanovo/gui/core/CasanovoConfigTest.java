package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CasanovoConfigTest {

    @Test
    @DisplayName("A one-run accelerator override changes generated YAML without changing the session")
    void scalarOverrideDoesNotMutateCurrentValues() {
        CasanovoConfig config = new CasanovoConfig();
        config.get("accelerator").setValue("gpu");

        String complete = config.toYaml(Map.of("accelerator", "cpu"));
        String overlaid = config.overlayOnto(
                "accelerator: auto\ndevices: 1\n", Map.of("accelerator", "cpu"));

        assertTrue(complete.contains("accelerator: \"cpu\""), complete);
        assertTrue(overlaid.contains("accelerator: \"cpu\""), overlaid);
        assertEquals("gpu", config.get("accelerator").getValue(),
                "the next run must still use the user's selected accelerator");
    }
}
