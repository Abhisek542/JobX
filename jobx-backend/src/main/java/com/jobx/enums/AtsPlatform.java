package com.jobx.enums;

/**
 * Every ATS Jobx can recognise. Recognising a platform is not the same as
 * watching it: only platforms with a registered {@code AtsFetcher} can be
 * watched (see {@code FetcherRegistry}). The next-wave platforms below are
 * recognised by {@code AtsUrlParser} so the add-company flow can name them
 * honestly, and each gains a fetcher in its own PR (jobx-backend/new-ats-add.md).
 */
public enum AtsPlatform {
    GREENHOUSE,
    LEVER,
    ASHBY,
    WORKABLE,
    SMARTRECRUITERS,
    WORKDAY,
    RIPPLING,
    BAMBOOHR,
    JOBVITE,
    JAZZHR,
    ICIMS,
    GUSTO,
    UNSUPPORTED // no recognised platform
}
