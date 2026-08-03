package app.ister.core.entity;

import java.math.BigDecimal;
import java.util.UUID;

/** An entity ordered by a gap-based position column; see {@code GapPositions}. */
public interface Positioned {
    UUID getId();

    BigDecimal getPosition();

    void setPosition(BigDecimal position);
}
