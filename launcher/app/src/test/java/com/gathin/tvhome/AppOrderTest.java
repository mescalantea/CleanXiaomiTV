package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class AppOrderTest {

    @Test
    public void savedOrderFirstThenNewAppsAndGoneOnesDropped() {
        assertEquals(Arrays.asList("b", "a", "c"), AppOrder.order(Arrays.asList("b", "gone", "a", "b"), Arrays.asList("a", "b", "c")));
        assertEquals(Arrays.asList("a", "b"), AppOrder.order(Collections.emptyList(), Arrays.asList("a", "b")));
    }

    @Test
    public void anEarlierDockLeadsTheMigratedOrder() {
        assertEquals(Arrays.asList("dock1", "dock2", "x", "y"),
                AppOrder.merge(Arrays.asList("dock1", "dock2"), Arrays.asList("x", "dock2", "y")));
    }
}
