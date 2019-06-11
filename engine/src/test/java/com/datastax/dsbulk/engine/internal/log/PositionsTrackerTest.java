/*
 * Copyright DataStax, Inc.
 *
 * This software is subject to the below license agreement.
 * DataStax may make changes to the agreement from time to time,
 * and will post the amended terms at
 * https://www.datastax.com/terms/datastax-dse-bulk-utility-license-terms.
 */
package com.datastax.dsbulk.engine.internal.log;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Range.closed;
import static com.google.common.collect.Range.singleton;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.google.common.collect.Range;
import java.net.URI;
import java.util.List;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PositionsTrackerTest {

  private static final URI RESOURCE = URI.create("file://data.csv");

  @Test
  void should_update_position() {
    PositionsTracker tracker = new PositionsTracker();
    tracker.update(RESOURCE, 3);
    assertThat(tracker.getPositions().get(RESOURCE)).containsExactly(singleton(3L));
    tracker.update(RESOURCE, 1);
    assertThat(tracker.getPositions().get(RESOURCE)).containsExactly(singleton(1L), singleton(3L));
    tracker.update(RESOURCE, 2);
    assertThat(tracker.getPositions().get(RESOURCE)).containsExactly(closed(1L, 3L));
    tracker.update(RESOURCE, 2);
    assertThat(tracker.getPositions().get(RESOURCE)).containsExactly(closed(1L, 3L));
    tracker.update(RESOURCE, 6);
    assertThat(tracker.getPositions().get(RESOURCE)).containsExactly(closed(1L, 3L), singleton(6L));
    tracker.update(RESOURCE, 5);
    assertThat(tracker.getPositions().get(RESOURCE))
        .containsExactly(closed(1L, 3L), closed(5L, 6L));
    tracker.update(RESOURCE, 4);
    assertThat(tracker.getPositions().get(RESOURCE)).containsExactly(closed(1L, 6L));
  }

  @SuppressWarnings("unused")
  static List<Arguments> should_update_positions() {
    return Lists.newArrayList(
        arguments(new long[] {1, 2, 3, 4}, ranges(closed(1L, 4L))),
        arguments(new long[] {1, 2, 3, 5}, ranges(closed(1L, 3L), singleton(5L))),
        arguments(new long[] {5, 3, 2, 1}, ranges(closed(1L, 3L), singleton(5L))),
        arguments(new long[] {1, 3, 5, 4, 2}, ranges(closed(1L, 5L))),
        arguments(new long[] {2, 4, 5, 3, 1}, ranges(closed(1L, 5L))),
        arguments(new long[] {4, 3, 2, 1}, ranges(closed(1L, 4L))),
        arguments(new long[] {4, 3, 2, 1}, ranges(closed(1L, 4L))),
        arguments(new long[] {3, 2}, ranges(closed(2L, 3L))),
        arguments(new long[] {3, 5, 4, 2}, ranges(closed(2L, 5L))));
  }

  @ParameterizedTest
  @MethodSource
  final void should_update_positions(long[] positions, List<Range<Long>> expected) {
    PositionsTracker positionsTracker = new PositionsTracker();
    for (long position : positions) {
      positionsTracker.update(RESOURCE, position);
    }
    assertThat(positionsTracker.getPositions()).hasSize(1).containsEntry(RESOURCE, expected);
  }

  @SafeVarargs
  static List<Range<Long>> ranges(Range<Long>... ranges) {
    return ranges == null ? emptyList() : newArrayList(ranges);
  }
}