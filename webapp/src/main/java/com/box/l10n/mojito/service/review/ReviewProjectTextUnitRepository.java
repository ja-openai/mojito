package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.service.badtranslation.BadTranslationReviewProjectMatchRow;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface ReviewProjectTextUnitRepository
    extends JpaRepository<ReviewProjectTextUnit, Long> {

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewProjectTextUnitDetail(
        rptu.id,
        ttu.id,
        ttu.name,
        ttu.content,
        ttu.comment,
        ttu.createdDate,
        ttu.wordCount,
        asset.path,
        repo.id,
        repo.name,
        ttuv.id,
        ttuv.content,
        ttuv.status,
        ttuv.includedInLocalizedFile,
        ttuv.comment,
        currentTtuv.id,
        currentTtuv.content,
        currentTtuv.status,
        currentTtuv.includedInLocalizedFile,
        currentTtuv.comment,
        decisionTtuv.id,
        decisionTtuv.content,
        decisionTtuv.status,
        decisionTtuv.includedInLocalizedFile,
        decisionTtuv.comment,
        rptud.reviewedVariant.id,
        rptud.notes,
        rptud.decisionState,
        rptud.lastModifiedDate,
        decisionModifiedBy.username
      )
      from ReviewProjectTextUnit rptu
      join rptu.reviewProject rp
      join rptu.tmTextUnit ttu
      join ttu.asset asset
      join asset.repository repo
      left join rptu.tmTextUnitVariant ttuv
      left join TMTextUnitCurrentVariant ttucv
        on ttucv.tmTextUnit = ttu
       and ttucv.locale = rp.locale
      left join ttucv.tmTextUnitVariant currentTtuv
      left join ReviewProjectTextUnitDecision rptud
        on rptud.reviewProjectTextUnit = rptu
      left join rptud.lastModifiedByUser decisionModifiedBy
      left join rptud.decisionVariant decisionTtuv
      where rp.id = :reviewProjectId
      order by rptu.id
      """)
  List<ReviewProjectTextUnitDetail> findDetailByReviewProjectId(
      @Param("reviewProjectId") Long reviewProjectId);

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewProjectTextUnitDetail(
        rptu.id,
        ttu.id,
        ttu.name,
        ttu.content,
        ttu.comment,
        ttu.createdDate,
        ttu.wordCount,
        asset.path,
        repo.id,
        repo.name,
        ttuv.id,
        ttuv.content,
        ttuv.status,
        ttuv.includedInLocalizedFile,
        ttuv.comment,
        currentTtuv.id,
        currentTtuv.content,
        currentTtuv.status,
        currentTtuv.includedInLocalizedFile,
        currentTtuv.comment,
        decisionTtuv.id,
        decisionTtuv.content,
        decisionTtuv.status,
        decisionTtuv.includedInLocalizedFile,
        decisionTtuv.comment,
        rptud.reviewedVariant.id,
        rptud.notes,
        rptud.decisionState,
        rptud.lastModifiedDate,
        decisionModifiedBy.username
      )
      from ReviewProjectTextUnit rptu
      join rptu.reviewProject rp
      join rptu.tmTextUnit ttu
      join ttu.asset asset
      join asset.repository repo
      left join rptu.tmTextUnitVariant ttuv
      left join TMTextUnitCurrentVariant ttucv
        on ttucv.tmTextUnit = ttu
       and ttucv.locale = rp.locale
      left join ttucv.tmTextUnitVariant currentTtuv
      left join ReviewProjectTextUnitDecision rptud
        on rptud.reviewProjectTextUnit = rptu
      left join rptud.lastModifiedByUser decisionModifiedBy
      left join rptud.decisionVariant decisionTtuv
      where rptu.id = :reviewProjectTextUnitId
      """)
  Optional<ReviewProjectTextUnitDetail> findDetailByReviewProjectTextUnitId(
      @Param("reviewProjectTextUnitId") Long reviewProjectTextUnitId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from ReviewProjectTextUnit rptu
      where rptu.reviewProject.id in :projectIds
      """)
  int deleteByReviewProjectIds(@Param("projectIds") List<Long> projectIds);

  @Query(
      """
      select distinct rptu.tmTextUnit.id
      from ReviewProjectTextUnit rptu
      join rptu.reviewProject rp
      where rp.status = :status
        and rp.locale.id = :localeId
        and rptu.tmTextUnit.id in :tmTextUnitIds
      """)
  List<Long> findTmTextUnitIdsByReviewProjectStatusAndLocaleIdAndTmTextUnitIds(
      @Param("status") ReviewProjectStatus status,
      @Param("localeId") Long localeId,
      @Param("tmTextUnitIds") List<Long> tmTextUnitIds);

  @Query(
      """
      select new com.box.l10n.mojito.service.badtranslation.BadTranslationReviewProjectMatchRow(
        rp.id,
        rp.status,
        rp.createdDate,
        rp.dueDate,
        request.id,
        request.name,
        requestCreatedBy.id,
        requestCreatedBy.username,
        team.id,
        team.name,
        assignedPm.id,
        assignedPm.username,
        assignedTranslator.id,
        assignedTranslator.username,
        reviewProjectCreatedBy.id,
        reviewProjectCreatedBy.username,
        baselineTtuv.id,
        baselineTtuv.content,
        currentTtuv.id,
        currentTtuv.content,
        decisionTtuv.id,
        decisionTtuv.content,
        reviewedVariant.id,
        rptud.lastModifiedDate,
        decisionModifiedBy.id,
        decisionModifiedBy.username
      )
      from ReviewProjectTextUnit rptu
      join rptu.reviewProject rp
      left join rp.reviewProjectRequest request
      left join request.createdByUser requestCreatedBy
      left join rp.team team
      left join rp.assignedPmUser assignedPm
      left join rp.assignedTranslatorUser assignedTranslator
      left join rp.createdByUser reviewProjectCreatedBy
      left join rptu.tmTextUnitVariant baselineTtuv
      left join TMTextUnitCurrentVariant ttucv
        on ttucv.tmTextUnit = rptu.tmTextUnit
       and ttucv.locale = rp.locale
      left join ttucv.tmTextUnitVariant currentTtuv
      left join ReviewProjectTextUnitDecision rptud
        on rptud.reviewProjectTextUnit = rptu
      left join rptud.decisionVariant decisionTtuv
      left join rptud.reviewedVariant reviewedVariant
      left join rptud.lastModifiedByUser decisionModifiedBy
      where rptu.tmTextUnit.id = :tmTextUnitId
        and rp.locale.id = :localeId
      order by coalesce(rptud.lastModifiedDate, rp.createdDate) desc, rp.id desc
      """)
  List<BadTranslationReviewProjectMatchRow> findBadTranslationReviewProjectMatches(
      @Param("tmTextUnitId") Long tmTextUnitId, @Param("localeId") Long localeId);
}
