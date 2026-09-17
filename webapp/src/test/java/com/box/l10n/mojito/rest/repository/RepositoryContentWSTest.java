package com.box.l10n.mojito.rest.repository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.service.content.RepositoryContentService;
import org.junit.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class RepositoryContentWSTest {
  private final RepositoryContentService content = mock(RepositoryContentService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new RepositoryContentWS(content)).build();

  @Test
  public void omittedRecursionRetainsTheOriginalRecursiveContract() throws Exception {
    mvc.perform(get("/api/repositories/1/content")).andExpect(status().isOk());
    verify(content).list(1L, null, null, 0, 100, null, null, null, null, null, true);
  }

  @Test
  public void directChildRequestPassesDirectoryAndRecursionToTheService() throws Exception {
    mvc.perform(
            get("/api/repositories/1/content")
                .param("directory", "docs/")
                .param("recursive", "false"))
        .andExpect(status().isOk());
    verify(content).list(1L, null, null, 0, 100, null, null, null, null, "docs/", false);
  }

  @Test
  public void invalidRecursionIsRejectedBeforeQuerying() throws Exception {
    mvc.perform(get("/api/repositories/1/content").param("recursive", "invalid"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(content);
  }
}
