package com.box.l10n.mojito.phabricator;

import com.box.l10n.mojito.phabricator.payload.DiffSearchResult;
import com.box.l10n.mojito.phabricator.payload.QueryDiffsFields;
import com.box.l10n.mojito.phabricator.payload.QueryDiffsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class DifferentialDiff {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(DifferentialDiff.class);

  PhabricatorHttpClient phabricatorHttpClient;

  @Autowired
  public DifferentialDiff(PhabricatorHttpClient phabricatorHttpClient) {
    this.phabricatorHttpClient = phabricatorHttpClient;
  }

  /**
   * @param objectPHID {@link #getObjectPHID(String)}
   * @return
   */
  String getRevisionPHID(String objectPHID) {
    try {
      DiffSearchResult diffSearchResponse =
          phabricatorHttpClient.postEntityAndCheckResponse(
              Method.DIFFERENTIAL_DIFF_SEARCH,
              phabricatorHttpClient.getConstraintsForPHID(objectPHID),
              DiffSearchResult.class);

      String revisionPHID =
          diffSearchResponse.getResult().getData().get(0).getFields().getRevisionPHID();
      return revisionPHID;
    } catch (Exception e) {
      throw new PhabricatorException("Can't find revision PHID", e);
    }
  }

  public QueryDiffsFields queryDiff(String diffId) {
    try {
      QueryDiffsResult queryDiffsResult =
          phabricatorHttpClient.postEntityAndCheckResponse(
              Method.DIFFERENTIAL_QUERYDIFFS,
              phabricatorHttpClient.withId(diffId),
              QueryDiffsResult.class);

      QueryDiffsFields queryDiffsFields = queryDiffsResult.getResult().get(diffId);
      return queryDiffsFields;
    } catch (Exception e) {
      throw new PhabricatorException("Can't get diff", e);
    }
  }
}
