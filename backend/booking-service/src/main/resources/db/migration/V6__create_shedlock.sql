-- ============================================================
-- ShedLock's lock registry (see com.aireak.common.scheduling.SchedulerLockConfig).
-- One row per @SchedulerLock name; the row is held for the duration of a run so a second
-- replica of this service skips the job instead of running it concurrently.
--
-- Column names and types are fixed by JdbcTemplateLockProvider -- do not rename them.
-- TIMESTAMP(3) because the provider compares lock_until against the database clock
-- (usingDbTime()), and second-level precision would round two near-simultaneous acquisitions
-- into a tie.
-- ============================================================

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
