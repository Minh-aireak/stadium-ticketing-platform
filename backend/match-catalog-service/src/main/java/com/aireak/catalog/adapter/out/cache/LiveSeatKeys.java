package com.aireak.catalog.adapter.out.cache;

/**
 * The one place that knows how a showtime's live seat counter is addressed in Redis.
 *
 * <p>Shared by the reader ({@link RedisLiveSeatStore}) and the writer
 * ({@link RedisSeatAvailabilityCounter}) so the two can never drift onto different keys — they
 * operate on the same value and a mismatch would be silent, showing up only as counts that never
 * seem to update.
 *
 * <p>Values under this prefix are stored as plain decimal text and read with
 * {@code StringCodec}, never Redisson's default binary codec: the Lua scripts in the writer call
 * {@code tonumber()} on whatever is there, which only works if the bytes in Redis are the digits
 * themselves.
 */
final class LiveSeatKeys {

    private static final String PREFIX = "catalog:seats:";

    private LiveSeatKeys() {
    }

    static String of(String showtimeId) {
        return PREFIX + showtimeId;
    }

    static String showtimeIdIn(String key) {
        return key.substring(PREFIX.length());
    }
}
