package com.box.l10n.mojito.localtm.merger;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.JSR310Migration;
import com.box.l10n.mojito.json.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

public class MultiBranchStateJsonTest {

  ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void roundTrip() {
    Branch branch =
        new Branch("branch", JSR310Migration.newDateTimeCtor(2020, 7, 14, 0, 0).withNano(0));
    MultiBranchState state =
        new MultiBranchState(
            ImmutableList.of(
                new BranchStateTextUnit(
                    123L,
                    456L,
                    "md5",
                    JSR310Migration.newDateTimeCtor(2020, 7, 15, 0, 0).withNano(0),
                    "name",
                    "source",
                    "comment",
                    "one",
                    "other",
                    ImmutableMap.of(
                        branch.name(), new BranchData(ImmutableSet.of("src/file.ts:1:1"))))),
            ImmutableSet.of(branch));

    String json = objectMapper.writeValueAsStringUnchecked(state);

    assertThat(objectMapper.readValueUnchecked(json, MultiBranchState.class))
        .usingRecursiveComparison()
        .withComparatorForType(
            Comparator.comparing(ChronoZonedDateTime::toInstant), ZonedDateTime.class)
        .isEqualTo(state);
  }

  @Test
  public void defaultsMissingCollectionsToEmpty() {
    MultiBranchState state =
        objectMapper.readValueUnchecked(
            "{\"branchStateTextUnits\":[{\"md5\":\"md5\"}]}", MultiBranchState.class);

    assertThat(state.branches()).isEmpty();
    assertThat(state.branchStateTextUnits()).hasSize(1);
    assertThat(state.branchStateTextUnits().get(0).branchNameToBranchDatas()).isEmpty();
  }
}
