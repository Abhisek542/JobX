package com.jobx.fetcher;

import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared helpers for fetcher fixture tests. Fixtures are real API responses
 * captured live on 2026-08-02 (see docs/ats-api-reference.md).
 */
public final class FixtureSupport {

    private FixtureSupport() {
    }

    public static String fixture(String name) {
        try (InputStream in = FixtureSupport.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read fixture: " + name, e);
        }
    }

    public static WatchedCompany company(String name, AtsPlatform platform, String token) {
        WatchedCompany company = new WatchedCompany();
        company.setCompanyName(name);
        company.setAtsPlatform(platform);
        company.setBoardToken(token);
        company.setStatus(WatchedCompany.CompanyStatus.ACTIVE);
        return company;
    }
}
