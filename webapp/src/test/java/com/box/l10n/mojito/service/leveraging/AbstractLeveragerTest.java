package com.box.l10n.mojito.service.leveraging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.AddTMTextUnitCurrentVariantResult;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * @author jaurambault
 */
public class AbstractLeveragerTest {

  private AbstractLeverager getLeveragingImpl() {

    return new AbstractLeverager() {

      @Override
      public String getType() {
        return "for test";
      }

      @Override
      public List<TextUnitDTO> getLeveragingMatches(
          TMTextUnit tmTextUnit, Long sourceTmId, Long sourceAssetId) {
        throw new UnsupportedOperationException(
            "Not supported yet."); // To change body of generated methods, choose Tools | Templates.
      }

      @Override
      public boolean isTranslationNeededIfUniqueMatch() {
        throw new UnsupportedOperationException("Not supported yet.");
      }
    };
  }

  @Test
  public void testFilterTextUnitDTOWithSameTMTextUnitIdEmpty() {
    List<TextUnitDTO> textUnitDTOs = new ArrayList<>();
    getLeveragingImpl().filterTextUnitDTOWithSameTMTextUnitId(textUnitDTOs);
    assertTrue(textUnitDTOs.isEmpty());
  }

  @Test
  public void testFilterTextUnitDTOWithSameTMTextUnitId() {
    List<TextUnitDTO> textUnitDTOs = new ArrayList<>();

    TextUnitDTO textUnitDTO = new TextUnitDTO();
    textUnitDTO.setTmTextUnitId(1L);
    textUnitDTOs.add(textUnitDTO);

    TextUnitDTO textUnitDTO2 = new TextUnitDTO();
    textUnitDTO2.setTmTextUnitId(2L);
    textUnitDTOs.add(textUnitDTO2);

    TextUnitDTO textUnitDTO3 = new TextUnitDTO();
    textUnitDTO3.setTmTextUnitId(1L);
    textUnitDTOs.add(textUnitDTO3);

    getLeveragingImpl().filterTextUnitDTOWithSameTMTextUnitId(textUnitDTOs);

    assertEquals(2, textUnitDTOs.size());
    assertEquals(textUnitDTO, textUnitDTOs.get(0));
    assertEquals(textUnitDTO3, textUnitDTOs.get(1));
  }

  @Test
  public void performLeveragingForCommitsTransactionWhenTranslationsAreAdded() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TMService tmService = mock(TMService.class);
    UserService userService = mock(UserService.class);
    User leverageUser = new User();
    when(userService.findOrCreateLeverageUser()).thenReturn(leverageUser);
    when(tmService.addTMTextUnitCurrentVariantWithResult(
            eq(10L),
            eq(20L),
            eq("target"),
            eq("target comment"),
            eq(TMTextUnitVariant.Status.APPROVED),
            eq(true),
            isNull(),
            eq(leverageUser)))
        .thenReturn(new AddTMTextUnitCurrentVariantResult(false, currentVariant(30L)));
    AbstractLeverager leverager =
        leveragingImplWithMatch(transactionManager, tmService, userService, translation());
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(10L);
    tmTextUnit.setName("name");
    List<TMTextUnit> tmTextUnits = new ArrayList<>();
    tmTextUnits.add(tmTextUnit);

    leverager.performLeveragingFor(tmTextUnits, null, null);

    assertTrue(tmTextUnits.isEmpty());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void performLeveragingForRollsBackTransactionWhenTranslationAddFails() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TMService tmService = mock(TMService.class);
    UserService userService = mock(UserService.class);
    User leverageUser = new User();
    when(userService.findOrCreateLeverageUser()).thenReturn(leverageUser);
    when(tmService.addTMTextUnitCurrentVariantWithResult(
            any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
        .thenThrow(new IllegalStateException("failed"));
    AbstractLeverager leverager =
        leveragingImplWithMatch(transactionManager, tmService, userService, translation());
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(10L);
    tmTextUnit.setName("name");

    try {
      leverager.performLeveragingFor(new ArrayList<>(List.of(tmTextUnit)), null, null);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected performLeveragingFor to rethrow the add failure");
  }

  private AbstractLeverager leveragingImplWithMatch(
      PlatformTransactionManager transactionManager,
      TMService tmService,
      UserService userService,
      TextUnitDTO translation) {
    AbstractLeverager leverager =
        new AbstractLeverager() {

          @Override
          public String getType() {
            return "for test";
          }

          @Override
          public List<TextUnitDTO> getLeveragingMatches(
              TMTextUnit tmTextUnit, Long sourceTmId, Long sourceAssetId) {
            return new ArrayList<>(List.of(translation));
          }

          @Override
          public boolean isTranslationNeededIfUniqueMatch() {
            return false;
          }
        };
    leverager.transactionManager = transactionManager;
    leverager.tmService = tmService;
    leverager.userService = userService;
    leverager.tmTextUnitVariantLeveragingService = mock(TMTextUnitVariantLeveragingService.class);
    leverager.tmTextUnitVariantCommentService = mock(TMTextUnitVariantCommentService.class);
    return leverager;
  }

  private TextUnitDTO translation() {
    TextUnitDTO translation = new TextUnitDTO();
    translation.setTmTextUnitId(1L);
    translation.setTmTextUnitVariantId(2L);
    translation.setLocaleId(20L);
    translation.setTarget("target");
    translation.setTargetComment("target comment");
    translation.setStatus(TMTextUnitVariant.Status.APPROVED);
    translation.setIncludedInLocalizedFile(true);
    return translation;
  }

  private TMTextUnitCurrentVariant currentVariant(Long id) {
    TMTextUnitCurrentVariant currentVariant = new TMTextUnitCurrentVariant();
    currentVariant.setId(id);
    currentVariant.setTmTextUnitVariant(new TMTextUnitVariant());
    currentVariant.getTmTextUnitVariant().setId(40L);
    return currentVariant;
  }
}
