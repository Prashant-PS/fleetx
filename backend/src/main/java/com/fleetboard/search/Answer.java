package com.fleetboard.search;

import java.util.List;

/**
 * The "overview" box shown above the results.
 *
 * @param text        light markdown: paragraphs, "- " bullets and **bold**
 * @param sources     links backing the answer
 * @param generatedBy "claude" or "workbook"
 */
public record Answer(String text, List<Source> sources, String generatedBy) {

    public record Source(String label, String url) {
    }
}
