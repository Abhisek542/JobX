package com.jobx.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextListsTest {

    @Test
    void trimsAndDropsBlanks() {
        assertEquals(List.of("java", "spring"),
                TextLists.normalize(Arrays.asList("  java ", "", "   ", null, "spring")));
    }

    @Test
    void dedupesCaseInsensitivelyKeepingFirstCasing() {
        assertEquals(List.of("Java", "spring"),
                TextLists.normalize(List.of("Java", "java", "JAVA", "spring")));
    }

    @Test
    void preservesOrder() {
        assertEquals(List.of("c", "a", "b"),
                TextLists.normalize(List.of("c", "a", "b", "a")));
    }

    @Test
    void nullInputBecomesEmptyList() {
        assertTrue(TextLists.normalize(null).isEmpty());
    }
}
