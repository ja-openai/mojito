package com.box.l10n.mojito.service.stringauthoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Branch;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.service.branch.BranchRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.UsedFilter;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

public class StringAuthoringServiceTest {

  private final StringAuthoringService stringAuthoringService =
      new StringAuthoringService(null, null, null, null, null, null, null);

  @Test
  public void generateStringIdUsesSourceAndDescription() {
    assertThat(StringAuthoringService.generateStringId("Button label", "Shown on checkout"))
        .isEqualTo(DigestUtils.md5Hex("Button labelShown on checkout"));
  }

  @Test
  public void generateStringIdHandlesMissingDescription() {
    assertThat(StringAuthoringService.generateStringId("Button label", null))
        .isEqualTo(DigestUtils.md5Hex("Button label"));
  }

  @Test
  public void normalizeStringRequiresExplicitGenerateIdForBlankName() {
    StringAuthoringService.StringAuthoringString string =
        new StringAuthoringService.StringAuthoringString(
            null, "Button label", "Shown on checkout", null, null, null, null);

    assertThatThrownBy(() -> stringAuthoringService.normalizeString(string))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A string id must be provided unless md5 id generation is explicitly enabled");
  }

  @Test
  public void normalizeStringGeneratesIdWhenExplicitlyRequested() {
    StringAuthoringService.StringAuthoringString normalized =
        stringAuthoringService.normalizeString(
            new StringAuthoringService.StringAuthoringString(
                null, "Button label", "Shown on checkout", null, null, null, true));

    assertThat(normalized.name()).isEqualTo(DigestUtils.md5Hex("Button labelShown on checkout"));
    assertThat(normalized.generateId()).isFalse();
  }

  @Test
  public void normalizeStringRejectsGenerateIdWithProvidedName() {
    StringAuthoringService.StringAuthoringString string =
        new StringAuthoringService.StringAuthoringString(
            "checkout.submit", "Button label", "Shown on checkout", null, null, null, true);

    assertThatThrownBy(() -> stringAuthoringService.normalizeString(string))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("md5 id generation cannot be enabled when a string id is provided");
  }

  @Test
  public void getBranchesIncludesDefaultBranchForAllScope() {
    RepositoryRepository repositoryRepository = mock(RepositoryRepository.class);
    BranchRepository branchRepository = mock(BranchRepository.class);
    Repository repository = new Repository();
    repository.setId(7L);
    Branch defaultBranch = branch(11L, null);
    Branch authoringBranch = branch(12L, "authoring/checkout");
    ZonedDateTime cleanupDate = ZonedDateTime.parse("2026-07-10T00:00:00Z");
    authoringBranch.setCleanupDate(cleanupDate);

    when(repositoryRepository.findById(7L)).thenReturn(Optional.of(repository));
    when(branchRepository.findByRepositoryIdAndDeletedFalseOrderByCreatedDateDescNameAsc(
            eq(7L), any(Pageable.class)))
        .thenReturn(List.of(defaultBranch, authoringBranch));

    List<StringAuthoringService.StringAuthoringBranch> branches =
        new StringAuthoringService(
                repositoryRepository, null, branchRepository, null, null, null, null)
            .getBranches(7L, StringAuthoringService.BranchScope.ALL);

    assertThat(branches)
        .extracting(StringAuthoringService.StringAuthoringBranch::name)
        .containsExactly(null, "authoring/checkout");
    assertThat(branches)
        .extracting(StringAuthoringService.StringAuthoringBranch::authoring)
        .containsExactly(false, true);
    assertThat(branches)
        .extracting(StringAuthoringService.StringAuthoringBranch::cleanupDate)
        .containsExactly(null, cleanupDate);
  }

  @Test
  public void getStringsUsesRequestedUsedFilter() {
    TextUnitSearcherParameters searchParameters =
        getStringsSearchParameters(StringAuthoringService.StringAuthoringUsedFilter.UNUSED);

    assertThat(searchParameters.getUsedFilter()).isEqualTo(UsedFilter.UNUSED);
  }

  @Test
  public void getStringsAllStatusOmitsUsedFilter() {
    TextUnitSearcherParameters searchParameters =
        getStringsSearchParameters(StringAuthoringService.StringAuthoringUsedFilter.ALL);

    assertThat(searchParameters.getUsedFilter()).isNull();
  }

  private TextUnitSearcherParameters getStringsSearchParameters(
      StringAuthoringService.StringAuthoringUsedFilter usedFilter) {
    RepositoryRepository repositoryRepository = mock(RepositoryRepository.class);
    BranchRepository branchRepository = mock(BranchRepository.class);
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    Repository repository = new Repository();
    repository.setId(7L);
    Branch branch = branch(12L, "authoring/checkout");

    when(repositoryRepository.findById(7L)).thenReturn(Optional.of(repository));
    when(branchRepository.findByNameAndRepository("authoring/checkout", repository))
        .thenReturn(branch);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class))).thenReturn(List.of());

    new StringAuthoringService(
            repositoryRepository, null, branchRepository, null, null, textUnitSearcher, null)
        .getStrings(7L, "authoring/checkout", "authoring/checkout/strings.json", usedFilter, 25);

    ArgumentCaptor<TextUnitSearcherParameters> captor =
        ArgumentCaptor.forClass(TextUnitSearcherParameters.class);
    verify(textUnitSearcher).search(captor.capture());
    return captor.getValue();
  }

  private Branch branch(Long id, String name) {
    Branch branch = new Branch();
    branch.setId(id);
    branch.setName(name);
    return branch;
  }
}
