package com.jobx.fetcher;

import com.jobx.enums.AtsPlatform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes a WatchedCompany to the correct AtsFetcher implementation.
 * Spring auto-discovers all AtsFetcher beans and indexes them by platform.
 * Adding a new fetcher requires zero changes here.
 */
@Component
@Slf4j
public class FetcherRegistry {

    private final Map<AtsPlatform, AtsFetcher> fetchers;

    public FetcherRegistry(List<AtsFetcher> allFetchers) {
        this.fetchers = allFetchers.stream()
                .collect(Collectors.toMap(AtsFetcher::supports, Function.identity()));
        log.info("Registered fetchers: {}", fetchers.keySet());
    }

    public Optional<AtsFetcher> getFetcher(AtsPlatform platform) {
        return Optional.ofNullable(fetchers.get(platform));
    }
}
