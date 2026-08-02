package com.aireak.catalog.domain.model;

import java.util.List;
import java.util.Optional;

/** Fixed stadium catalog shared by showtime creation and public stadium discovery. */
public final class StadiumCatalog {

    public static final String MY_DINH = "my-dinh";
    public static final String THONG_NHAT = "thong-nhat";
    public static final String HANG_DAY = "hang-day";

    private static final List<StadiumDefinition> STADIUMS = List.of(
            new StadiumDefinition(MY_DINH, "Sân vận động Quốc gia Mỹ Đình", 432, 2, "OVAL"),
            new StadiumDefinition(THONG_NHAT, "Sân vận động Thống Nhất", 320, 1, "COMPACT"),
            new StadiumDefinition(HANG_DAY, "Sân vận động Hàng Đẫy", 540, 3, "MULTI_TIER")
    );

    private StadiumCatalog() {
    }

    public static List<StadiumDefinition> list() {
        return STADIUMS;
    }

    public static Optional<StadiumDefinition> find(String stadiumId) {
        if (stadiumId == null) return Optional.empty();
        return STADIUMS.stream().filter(stadium -> stadium.id().equals(stadiumId)).findFirst();
    }

    public record StadiumDefinition(String id, String name, int totalSeats, int levels, String design) {
    }
}
