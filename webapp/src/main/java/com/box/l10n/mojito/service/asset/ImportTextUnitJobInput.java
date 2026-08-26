package com.box.l10n.mojito.service.asset;

import com.box.l10n.mojito.entity.BulkImportRun.ActorType;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import java.util.List;

/**
 * @author jaurambault
 */
public class ImportTextUnitJobInput {

  List<TextUnitDTO> textUnitDTOs;
  TextUnitBatchImporterService.IntegrityChecksType integrityChecksType;
  Long initiatingUserId;
  ActorType actorType;
  String actorIdentity;
  String source;

  public TextUnitBatchImporterService.IntegrityChecksType getIntegrityChecksType() {
    return integrityChecksType;
  }

  public void setIntegrityChecksType(
      TextUnitBatchImporterService.IntegrityChecksType integrityChecksType) {
    this.integrityChecksType = integrityChecksType;
  }

  public List<TextUnitDTO> getTextUnitDTOs() {
    return textUnitDTOs;
  }

  public void setTextUnitDTOs(List<TextUnitDTO> textUnitDTOs) {
    this.textUnitDTOs = textUnitDTOs;
  }

  public Long getInitiatingUserId() {
    return initiatingUserId;
  }

  public void setInitiatingUserId(Long initiatingUserId) {
    this.initiatingUserId = initiatingUserId;
  }

  public ActorType getActorType() {
    return actorType;
  }

  public void setActorType(ActorType actorType) {
    this.actorType = actorType;
  }

  public String getActorIdentity() {
    return actorIdentity;
  }

  public void setActorIdentity(String actorIdentity) {
    this.actorIdentity = actorIdentity;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}
