package com.jobx.fetcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads schema.org JSON-LD out of an HTML page — the {@code JobPosting} block
 * job pages publish for search engines.
 *
 * HTML-tier fetchers take a posting's details from here rather than from CSS
 * selectors. The block exists so Google can index the role, which makes it the
 * part of an HTML page least likely to change shape under us: a careers-page
 * redesign moves classes around, but breaking the JSON-LD would drop the
 * customer out of Google for Jobs.
 */
public final class JsonLd {

    private JsonLd() {
    }

    /** Every top-level JSON-LD node on the page, arrays and {@code @graph} flattened. */
    public static List<JsonNode> nodes(Document document, ObjectMapper objectMapper) {
        List<JsonNode> nodes = new ArrayList<>();
        for (Element script : document.select("script[type=application/ld+json]")) {
            try {
                collect(objectMapper.readTree(script.data()), nodes);
            } catch (Exception e) {
                // One malformed block (pages hand-edit these) must not hide the others.
            }
        }
        return nodes;
    }

    /** The first node whose {@code @type} is (or includes) the given type. */
    public static Optional<JsonNode> find(Document document, ObjectMapper objectMapper, String type) {
        return nodes(document, objectMapper).stream().filter(node -> hasType(node, type)).findFirst();
    }

    private static void collect(JsonNode node, List<JsonNode> into) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collect(child, into));
        } else if (node.isObject()) {
            into.add(node);
            if (node.has("@graph")) {
                collect(node.get("@graph"), into);
            }
        }
    }

    private static boolean hasType(JsonNode node, String type) {
        JsonNode types = node.path("@type");
        if (types.isArray()) {
            for (JsonNode t : types) {
                if (type.equals(t.asText())) {
                    return true;
                }
            }
            return false;
        }
        return type.equals(types.asText());
    }

    /**
     * "Locality, Region, Country" from a JobPosting's {@code jobLocation}
     * (an object or an array of them). Null when there is nothing to show.
     */
    public static String location(JsonNode jobPosting) {
        JsonNode locations = jobPosting.path("jobLocation");
        List<String> parts = new ArrayList<>();
        for (JsonNode place : locations.isArray() ? locations : List.of(locations)) {
            JsonNode address = place.path("address");
            List<String> line = new ArrayList<>();
            for (String field : List.of("addressLocality", "addressRegion", "addressCountry")) {
                JsonNode value = address.path(field);
                // addressCountry is sometimes a {"@type":"Country","name":…} object
                String text = value.isObject() ? value.path("name").asText("") : value.asText("");
                if (!text.isBlank()) {
                    line.add(text.trim());
                }
            }
            if (!line.isEmpty()) {
                parts.add(String.join(", ", line));
            }
        }
        return parts.isEmpty() ? null : String.join(" / ", parts.stream().distinct().toList());
    }
}
