package com.jobx.fetcher.lever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Job;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.ExperienceParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetcher for Lever ATS — VERIFIED live against FamPay + Sprinto (2026-08-02).
 *
 * Endpoint: GET https://api.lever.co/v0/postings/{company}?mode=json
 * No auth required. Root is a BARE JSON ARRAY, not an object.
 * (Postman's board 404s as of 2026-08-02 — dead target, dropped.)
 *
 * Translation decisions (from live recon):
 *  - text                → Job.title          (NOT "title")
 *  - hostedUrl           → Job.applyUrl       (posting page; applyUrl field is the bare form)
 *  - createdAt           → epoch MILLISECONDS → Job.platformPostedAt (no updated_at exists)
 *  - categories.location → Job.location       (null-safe — categories vary per board)
 *  - description: descriptionPlain ALONE IS ONLY THE INTRO — the requirements
 *    ("Must Haves", experience strings) live in lists[].content as HTML.
 *    Assemble: descriptionPlain + lists[].text + strip(lists[].content) + additionalPlain.
 *  - id (UUID string)    → Job.externalId
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LeverFetcher implements AtsFetcher {

    private static final String BASE_URL = "https://api.lever.co";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.LEVER;
    }

    @Override
    public List<Job> fetch(WatchedCompany company) {
        String token = company.getBoardToken();
        String url = BASE_URL + "/v0/postings/" + token + "?mode=json";
        log.info("Fetching Lever board: {} ({})", company.getCompanyName(), token);

        String responseBody;
        try {
            responseBody = webClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            throw new AtsFetchException("Lever board request failed for token " + token, e);
        }

        if (responseBody == null) {
            throw new AtsFetchException("Empty response from Lever for token " + token);
        }

        try {
            return parse(responseBody, company);
        } catch (AtsFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new AtsFetchException("Could not parse Lever response for token " + token, e);
        }
    }

    // Package-private seam so fixture tests can exercise the mapping without HTTP.
    List<Job> parse(String responseBody, WatchedCompany company) throws Exception {
        List<Job> results = new ArrayList<>();

        JsonNode root = objectMapper.readTree(responseBody);

        // A live board with no openings returns an empty array. An object root
        // is Lever's error shape ({"error": ...}) — a dead/renamed board token,
        // which must surface as a failure rather than "no jobs today".
        if (!root.isArray()) {
            throw new AtsFetchException("Expected bare array in Lever response for token "
                    + company.getBoardToken() + ", got " + root.getNodeType());
        }

        for (JsonNode node : root) {
            Job job = new Job();
            job.setCompany(company);
            job.setAtsPlatform(AtsPlatform.LEVER);

            job.setExternalId(node.path("id").asText());
            job.setTitle(node.path("text").asText(""));

            String location = node.path("categories").path("location").asText("");
            if (!location.isBlank()) {
                job.setLocation(location.trim());
            }

            job.setApplyUrl(node.path("hostedUrl").asText(""));

            // createdAt is epoch milliseconds — the only timestamp Lever exposes
            long createdAt = node.path("createdAt").asLong(0);
            if (createdAt > 0) {
                job.setPlatformPostedAt(Instant.ofEpochMilli(createdAt));
            }

            String description = assembleDescription(node);
            if (!description.isEmpty()) {
                job.setDescription(description);
                ExperienceParser.parse(description, job);
            }

            job.setRawJson(node.toString());
            job.setFirstSeenAt(Instant.now());

            results.add(job);
        }

        log.info("Translated {} jobs for {} (Lever)", results.size(), company.getCompanyName());
        return results;
    }

    /**
     * Lever splits the JD across fields: descriptionPlain is only the intro;
     * the requirement bullets (including "3-5 years..." experience strings that
     * ExperienceParser and keyword scoring need) are HTML inside lists[].content.
     */
    private String assembleDescription(JsonNode node) {
        StringBuilder sb = new StringBuilder();

        appendIfPresent(sb, node.path("descriptionPlain").asText(""));

        for (JsonNode list : node.path("lists")) {
            appendIfPresent(sb, list.path("text").asText(""));
            String content = list.path("content").asText("");
            if (!content.isBlank()) {
                appendIfPresent(sb, Jsoup.parse(content).text());
            }
        }

        appendIfPresent(sb, node.path("additionalPlain").asText(""));

        return sb.toString().trim();
    }

    private static void appendIfPresent(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(part.trim());
        }
    }
}
