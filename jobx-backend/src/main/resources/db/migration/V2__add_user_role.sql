-- Phase 4: auth. Role isn't enforced by any endpoint yet — added now so a
-- second migration isn't needed once admin-only routes exist.

ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'));
