package com.box.l10n.mojito.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PullCommandNoSourceBranchSelectionTest {

  @Test
  public void sourceLessNullBranchOptionSelectsNullAndImpliesPullWithNoSource() throws Exception {
    PullCommand pullCommand = parse("--pull-with-no-source-null-branch");

    assertThat(pullCommand.shouldPullWithNoSource()).isTrue();
    pullCommand.normalizePullWithNoSourceBranches();
    assertThat(pullCommand.pullWithNoSourceBranches).containsExactly((String) null);
  }

  @Test
  public void genericNullBranchOptionIsNotAcceptedOnPull() {
    assertThatThrownBy(() -> parse("--null-branch")).isInstanceOf(ParameterException.class);
  }

  @Test
  public void nullBranchCombinesWithNamedBranchesAndKeepsLiteralNullDistinct() throws Exception {
    PullCommand pullCommand =
        parse(
            "--pull-with-no-source-branches",
            " authoring/checkout ",
            "null",
            "authoring/checkout",
            "--pull-with-no-source-null-branch");

    pullCommand.normalizePullWithNoSourceBranches();

    assertThat(pullCommand.pullWithNoSourceBranches)
        .containsExactly("authoring/checkout", "null", (String) null);
  }

  @Test
  public void nullBranchNormalizationIsIdempotent() throws Exception {
    PullCommand pullCommand = parse("--pull-with-no-source-null-branch");

    pullCommand.normalizePullWithNoSourceBranches();
    pullCommand.normalizePullWithNoSourceBranches();

    assertThat(pullCommand.pullWithNoSourceBranches).containsExactly((String) null);
  }

  @Test
  public void pullWithNoSourceWithoutSelectorsStillUsesAllActiveBranches() throws Exception {
    PullCommand pullCommand = parse("--pull-with-no-source");

    pullCommand.normalizePullWithNoSourceBranches();

    assertThat(pullCommand.shouldPullWithNoSource()).isTrue();
    assertThat(pullCommand.pullWithNoSourceBranches).isEmpty();
  }

  private PullCommand parse(String... arguments) {
    PullCommand pullCommand = new PullCommand();
    List<String> completeArguments = new ArrayList<>(List.of("--repository", "test-repository"));
    completeArguments.addAll(Arrays.asList(arguments));
    new JCommander(pullCommand).parse(completeArguments.toArray(String[]::new));
    return pullCommand;
  }
}
