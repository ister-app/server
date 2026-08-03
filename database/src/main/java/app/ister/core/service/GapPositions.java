package app.ister.core.service;

import app.ister.core.entity.Positioned;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Gap-based ordering shared by play-queue and playlist items: positions are whole GAP multiples,
 * an insert takes the midpoint between its neighbours, and when a gap is exhausted the whole list
 * is renumbered first.
 */
public final class GapPositions {

    static final BigDecimal GAP = new BigDecimal("1000");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final int POSITION_SCALE = 10;

    private GapPositions() {
    }

    /** The next position value given the previous one (or null for the first item). */
    public static BigDecimal nextPosition(BigDecimal previous) {
        return (previous == null) ? GAP : previous.add(GAP);
    }

    /** The highest position in the list, or null when it is empty. */
    public static BigDecimal maxPosition(List<? extends Positioned> items) {
        return items.stream()
                .map(Positioned::getPosition)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * Position for placing an item directly after afterItemId (or at the front when null),
     * ignoring the item being moved. Returns null when the gap between the neighbours is
     * exhausted and the list needs a {@link #rebalance} first.
     */
    public static BigDecimal targetPosition(List<? extends Positioned> items, UUID afterItemId, UUID movingItemId) {
        List<? extends Positioned> others = items.stream()
                .filter(item -> !item.getId().equals(movingItemId))
                .sorted(Comparator.comparing(Positioned::getPosition))
                .toList();
        if (others.isEmpty()) {
            return GAP;
        }
        if (afterItemId == null) {
            BigDecimal first = others.getFirst().getPosition();
            BigDecimal candidate = first.divide(TWO, POSITION_SCALE, RoundingMode.HALF_UP);
            return (candidate.signum() > 0 && candidate.compareTo(first) < 0) ? candidate : null;
        }
        int afterIndex = -1;
        for (int i = 0; i < others.size(); i++) {
            if (others.get(i).getId().equals(afterItemId)) {
                afterIndex = i;
                break;
            }
        }
        if (afterIndex == -1) {
            throw new IllegalArgumentException("After-item not in queue");
        }
        BigDecimal previous = others.get(afterIndex).getPosition();
        if (afterIndex == others.size() - 1) {
            return previous.add(GAP);
        }
        BigDecimal next = others.get(afterIndex + 1).getPosition();
        BigDecimal candidate = previous.add(next).divide(TWO, POSITION_SCALE, RoundingMode.HALF_UP);
        return (candidate.compareTo(previous) > 0 && candidate.compareTo(next) < 0) ? candidate : null;
    }

    /** Renumbers all items, in their current order, back to whole GAP multiples. */
    public static void rebalance(List<? extends Positioned> items) {
        List<? extends Positioned> sorted = items.stream()
                .sorted(Comparator.comparing(Positioned::getPosition))
                .toList();
        BigDecimal position = null;
        for (Positioned item : sorted) {
            position = nextPosition(position);
            item.setPosition(position);
        }
    }
}
