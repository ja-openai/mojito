package com.box.l10n.mojito.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.mcp.McpTransportWS;
import com.box.l10n.mojito.service.mcp.protocol.McpTransportService;
import com.box.l10n.mojito.service.mcp.protocol.McpTransportService.TransportResult;
import jakarta.servlet.ServletContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@RunWith(SpringRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = McpTransportWSAuthorizationTest.TestConfiguration.class)
public class McpTransportWSAuthorizationTest {

  private static final String MCP_PATH = "/api/mcp";
  private static final int TEST_MAX_REQUEST_BYTES = 256;

  @Autowired private WebApplicationContext applicationContext;
  @Autowired private McpTransportService mcpTransportService;

  private final ObjectMapper objectMapper = ObjectMapper.withNoFailOnUnknownProperties();
  private MockMvc mockMvc;

  @Before
  public void setUp() {
    reset(mcpTransportService);
    when(mcpTransportService.isOriginAllowed(any())).thenReturn(true);
    when(mcpTransportService.parseError()).thenReturn(error("Parse error"));
    when(mcpTransportService.requestTooLargeError()).thenReturn(error("Request body too large"));
    when(mcpTransportService.handlePost(any(), nullable(String.class)))
        .thenReturn(
            new TransportResult(
                HttpStatus.OK,
                objectMapper.createObjectNode().put("accepted", true),
                Optional.empty()));
    mockMvc =
        MockMvcBuilders.webAppContextSetup(applicationContext).apply(springSecurity()).build();
  }

  @Test
  public void authenticatedTranslatorsAndAdminsCanPostWithinTheRawLimit() throws Exception {
    mockMvc
        .perform(
            post(MCP_PATH)
                .with(user("translator").roles("TRANSLATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(exactLimitPayload()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(true));

    mockMvc
        .perform(
            post(MCP_PATH)
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(true));

    verify(mcpTransportService, times(2)).handlePost(any(), nullable(String.class));
  }

  @Test
  public void routeAuthorizationRunsBeforeTheRawBodyLimit() throws Exception {
    mockMvc
        .perform(
            post(MCP_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(TEST_MAX_REQUEST_BYTES + 1)))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(mcpTransportService);
  }

  @Test
  public void rejectsDeclaredOversizedBodyBeforeJsonParsing() throws Exception {
    mockMvc
        .perform(
            post(MCP_PATH)
                .with(user("translator").roles("TRANSLATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(TEST_MAX_REQUEST_BYTES + 1)))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.error.message").value("Request body too large"));

    verify(mcpTransportService, never()).handlePost(any(), nullable(String.class));
  }

  @Test
  public void rejectsOversizedChunkedBodyWithoutContentLength() throws Exception {
    mockMvc
        .perform(chunkedPost("x".repeat(TEST_MAX_REQUEST_BYTES + 1), "admin", "ADMIN"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.error.message").value("Request body too large"));

    verify(mcpTransportService, never()).handlePost(any(), nullable(String.class));
  }

  @Test
  public void malformedBodyWithinTheRawLimitRemainsABadRequest() throws Exception {
    mockMvc
        .perform(
            post(MCP_PATH)
                .with(user("translator").roles("TRANSLATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("not-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message").value("Parse error"));

    verify(mcpTransportService, never()).handlePost(any(), nullable(String.class));
  }

  @Test
  public void configurationCanLowerButCannotRaiseTheHardRawLimit() {
    assertThatThrownBy(
            () -> new McpTransportWS(mcpTransportService, objectMapper, (32 * 1024 * 1024) + 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be between 1 and 33554432");
  }

  private byte[] exactLimitPayload() {
    String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
    return (json + " ".repeat(TEST_MAX_REQUEST_BYTES - jsonBytes.length))
        .getBytes(StandardCharsets.UTF_8);
  }

  private RequestBuilder chunkedPost(String body, String username, String role) {
    byte[] content = body.getBytes(StandardCharsets.UTF_8);
    return (ServletContext servletContext) -> {
      MockHttpServletRequest request =
          new MockHttpServletRequest(servletContext) {
            @Override
            public int getContentLength() {
              return -1;
            }

            @Override
            public long getContentLengthLong() {
              return -1;
            }
          };
      request.setMethod(HttpMethod.POST.name());
      request.setRequestURI(MCP_PATH);
      request.setServletPath(MCP_PATH);
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
      request.setContent(content);
      return user(username).roles(role).postProcessRequest(request);
    };
  }

  private com.fasterxml.jackson.databind.JsonNode error(String message) {
    var response = objectMapper.createObjectNode();
    response.putObject("error").put("message", message);
    return response;
  }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  static class TestConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      http.csrf(csrf -> csrf.disable());
      http.exceptionHandling(
          exceptions ->
              exceptions.authenticationEntryPoint(
                  new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
      WebSecurityConfig.setAuthorizationRequests(http, List.of());
      return http.build();
    }

    @Bean
    McpTransportService mcpTransportService() {
      return mock(McpTransportService.class);
    }

    @Bean
    McpTransportWS mcpTransportWS(McpTransportService mcpTransportService) {
      return new McpTransportWS(
          mcpTransportService,
          ObjectMapper.withNoFailOnUnknownProperties(),
          TEST_MAX_REQUEST_BYTES);
    }
  }
}
