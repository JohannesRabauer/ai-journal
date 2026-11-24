-- One report per ISO week; the six sections are one JSON document in "content".
CREATE TABLE IF NOT EXISTS weekly_report (
    id           bigint       NOT NULL PRIMARY KEY,
    iso_year     integer      NOT NULL,
    iso_week     integer      NOT NULL,
    language     varchar(8)   NOT NULL,
    content      text         NOT NULL,
    generated_at timestamp(6) with time zone NOT NULL,
    edited_at    timestamp(6) with time zone
);
CREATE SEQUENCE IF NOT EXISTS weekly_report_seq START WITH 1 INCREMENT BY 50;
CREATE UNIQUE INDEX IF NOT EXISTS uq_weekly_report_week ON weekly_report (iso_year, iso_week);
