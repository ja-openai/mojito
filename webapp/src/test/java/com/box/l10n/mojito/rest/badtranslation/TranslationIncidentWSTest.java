package com.box.l10n.mojito.rest.badtranslation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentService;
import java.time.LocalDate;
import org.junit.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class TranslationIncidentWSTest {
  private final TranslationIncidentService service = mock(TranslationIncidentService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new TranslationIncidentWS(service)).build();

  @Test
  public void forwardsLocaleAlongsideExistingFilters() throws Exception {
    mvc.perform(
            get("/api/translation-incidents")
                .param("locale", "fr-CA")
                .param("status", "OPEN")
                .param("query", "save")
                .param("createdAfter", "2026-09-01")
                .param("createdBefore", "2026-09-22")
                .param("page", "2")
                .param("size", "50")
                .param("reviewType", "linguistic")
                .param("reviewRunId", "41"))
        .andExpect(status().isOk());

    verify(service)
        .getIncidents(
            TranslationIncidentStatus.OPEN,
            "save",
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 22),
            2,
            50,
            "linguistic",
            41L,
            "fr-CA");
  }

  @Test
  public void keepsLocaleOptional() throws Exception {
    mvc.perform(get("/api/translation-incidents")).andExpect(status().isOk());

    verify(service).getIncidents(null, null, null, null, 0, 25, null, null, null);
  }
}
