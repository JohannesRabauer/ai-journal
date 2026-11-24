-- Thinking Gym (SPEC section 7). steps and feedback_questions hold JSON arrays of strings.
CREATE TABLE IF NOT EXISTS gym_exercise (
    id                bigint       NOT NULL PRIMARY KEY,
    category          varchar(32)  NOT NULL,
    language          varchar(8)   NOT NULL,
    title             varchar(300) NOT NULL,
    prompt            text         NOT NULL,
    source_excerpt    text,
    source_entry_id   bigint,
    steps             text         NOT NULL,
    estimated_minutes integer      NOT NULL,
    personalized      boolean      NOT NULL DEFAULT false,
    template_id       varchar(64),
    created_at        timestamp(6) with time zone NOT NULL
);
CREATE SEQUENCE IF NOT EXISTS gym_exercise_seq START WITH 1 INCREMENT BY 50;
CREATE INDEX IF NOT EXISTS idx_gym_exercise_category ON gym_exercise (category, created_at);
CREATE INDEX IF NOT EXISTS idx_gym_exercise_source_entry ON gym_exercise (source_entry_id);

CREATE TABLE IF NOT EXISTS gym_attempt (
    id                 bigint       NOT NULL PRIMARY KEY,
    exercise_id        bigint       NOT NULL,
    category           varchar(32)  NOT NULL,
    answer             text         NOT NULL,
    duration_seconds   integer      NOT NULL,
    feedback_questions text,
    created_at         timestamp(6) with time zone NOT NULL
);
CREATE SEQUENCE IF NOT EXISTS gym_attempt_seq START WITH 1 INCREMENT BY 50;
CREATE INDEX IF NOT EXISTS idx_gym_attempt_exercise ON gym_attempt (exercise_id);
CREATE INDEX IF NOT EXISTS idx_gym_attempt_category ON gym_attempt (category, created_at);
