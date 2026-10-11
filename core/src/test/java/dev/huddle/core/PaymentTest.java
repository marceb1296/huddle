package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PaymentTest {
    @Test
    void takesAcrossSlotsInOrderAndStopsAtThePrice() {
        assertArrayEquals(new int[] {3, 0, 2, 0}, Payment.plan(new int[] {3, 0, 4, 64}, 5));
    }

    // si no alcanza no se cobra nada: cobrar a medias dejaría ítems perdidos
    @Test
    void notEnoughTakesNothing() {
        assertNull(Payment.plan(new int[] {2, 2}, 5));
    }

    // un conteo negativo de otro mod no puede sumar ítems
    @Test
    void negativeSlotsCountAsEmpty() {
        assertNull(Payment.plan(new int[] {-10, 4}, 5));
        assertArrayEquals(new int[] {0, 5}, Payment.plan(new int[] {-10, 9}, 5));
    }

    @Test
    void zeroOrNegativePriceIsNotAPayment() {
        assertNull(Payment.plan(new int[] {5}, 0));
        assertNull(Payment.plan(new int[] {5}, -3));
        assertNull(Payment.plan(null, 3));
    }

    @Test
    void splitsIntoStacks() {
        assertArrayEquals(new int[] {64, 64, 2}, Payment.stacks(130, 64));
        assertArrayEquals(new int[] {16}, Payment.stacks(16, 16));
        assertArrayEquals(new int[] {1, 1, 1}, Payment.stacks(3, 1));
        assertArrayEquals(new int[0], Payment.stacks(0, 64));
        assertArrayEquals(new int[0], Payment.stacks(5, 0));
    }
}
