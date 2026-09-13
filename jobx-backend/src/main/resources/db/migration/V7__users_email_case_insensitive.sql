-- V7: make account emails case-insensitive (BUG_REPORT #3).
--
-- V1 declared users.email TEXT UNIQUE, which Postgres compares case-sensitively:
-- "Abhi@Example.com" and "abhi@example.com" could be two accounts, and logging in
-- with the other casing returned 401. The app now lower-cases and trims emails in
-- the auth request DTOs; this migration brings existing rows into that form and
-- makes the database refuse a case-variant duplicate regardless of the caller.
--
-- If two existing accounts already collide, stop. Choosing which account survives
-- (and what happens to its watches, profile and matches) is a human decision, so
-- this migration never merges or deletes users on its own.

DO $$
DECLARE
    collisions TEXT;
BEGIN
    SELECT string_agg(canonical, ', ')
      INTO collisions
      FROM (SELECT LOWER(TRIM(email)) AS canonical
              FROM users
             GROUP BY 1
            HAVING COUNT(*) > 1) dupes;

    IF collisions IS NOT NULL THEN
        RAISE EXCEPTION 'V7: users differ only by email case/whitespace, resolve by hand first: %', collisions;
    END IF;
END $$;

UPDATE users
   SET email = LOWER(TRIM(email))
 WHERE email <> LOWER(TRIM(email));

-- The V1 UNIQUE constraint stays; this index is what enforces case-insensitivity.
CREATE UNIQUE INDEX uq_users_email_lower ON users (LOWER(email));
