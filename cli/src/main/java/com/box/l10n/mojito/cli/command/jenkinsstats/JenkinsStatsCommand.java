package com.box.l10n.mojito.cli.command.jenkinsstats;

import static java.time.temporal.ChronoUnit.DAYS;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.box.l10n.mojito.cli.command.Command;
import com.box.l10n.mojito.cli.command.CommandException;
import com.box.l10n.mojito.cli.console.ConsoleWriter;
import com.box.l10n.mojito.json.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * @author jaurambault
 */
@Component
@Scope("prototype")
@Parameters(
    commandNames = {"jenkins-stats"},
    commandDescription =
        "Compute basic statistics (success rate, and average duration) for Jenkins jobs")
public class JenkinsStatsCommand extends Command {

  @Parameter(
      names = "--config",
      required = true,
      description =
          "Path to configuration file. Format: "
              + "{\"jobs\":[\"https://jenkins1.org/job/mojito-test1\",\"https://jenkins2.org/job/mojito-test2\"],"
              + "\"authCookies\":{\"https://jenkins1.org\":\"cookie value\",\"https://jenkins2.org\":\"cookie2 value\"}}\n")
  String config;

  boolean printDurationInDecimalDays = true;

  @Autowired ConsoleWriter consoleWriter;

  ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();

  JenkinsStatsConfig jenkinsStatsConfig;

  @Override
  public boolean shouldShowInCommandList() {
    return false;
  }

  @Override
  protected void execute() throws CommandException {
    initFromConfigurationFile();

    Instant now = Instant.now();
    Instant nowMinus1Day = now.minus(1, DAYS);
    Instant yesterdayEnd = now.truncatedTo(DAYS);
    Instant yesterdayStart = yesterdayEnd.minus(1, DAYS);
    Instant weekEnd = now.truncatedTo(DAYS);
    Instant weekStart = weekEnd.minus(7, DAYS);

    ImmutableList<JenkinsJobResult> jobResults = getJobResults(jenkinsStatsConfig.jobs());
    printSuccessRate(
        jobResults, now, nowMinus1Day, yesterdayEnd, yesterdayStart, weekEnd, weekStart);
    printAvgTimes(jobResults, now, nowMinus1Day, yesterdayEnd, yesterdayStart, weekEnd, weekStart);
  }

  void printAvgTimes(
      ImmutableList<JenkinsJobResult> jobResults,
      Instant now,
      Instant nowMinus1Day,
      Instant yesterdayEnd,
      Instant yesterdayStart,
      Instant weekEnd,
      Instant weekStart) {
    printAvgTimeHeader();
    jobResults.stream()
        .collect(Collectors.groupingBy(JenkinsJobResult::jobName))
        .forEach(
            (jobName, jenkinsJobResults) -> {
              long avgCurrentDay =
                  jenkinsJobResults.stream()
                      .filter(
                          jenkinsJobResult ->
                              nowMinus1Day.toEpochMilli() < jenkinsJobResult.timestamp()
                                  && jenkinsJobResult.timestamp() < now.toEpochMilli())
                      .collect(Collectors.averagingLong(JenkinsJobResult::duration))
                      .longValue();

              long avgYesterday =
                  jenkinsJobResults.stream()
                      .filter(
                          jenkinsJobResult ->
                              yesterdayStart.toEpochMilli() < jenkinsJobResult.timestamp()
                                  && jenkinsJobResult.timestamp() < yesterdayEnd.toEpochMilli())
                      .collect(Collectors.averagingLong(JenkinsJobResult::duration))
                      .longValue();

              long avgWeek =
                  jenkinsJobResults.stream()
                      .filter(
                          jenkinsJobResult ->
                              weekStart.toEpochMilli() < jenkinsJobResult.timestamp()
                                  && jenkinsJobResult.timestamp() < weekEnd.toEpochMilli())
                      .collect(Collectors.averagingLong(JenkinsJobResult::duration))
                      .longValue();

              long avgGlobal =
                  jenkinsJobResults.stream()
                      .collect(Collectors.averagingLong(JenkinsJobResult::duration))
                      .longValue();
              printAvgTimes(jobName, avgCurrentDay, avgYesterday, avgWeek, avgGlobal);
            });
  }

  void printSuccessRate(
      ImmutableList<JenkinsJobResult> jobResults,
      Instant now,
      Instant nowMinus1Day,
      Instant yesterdayEnd,
      Instant yesterdayStart,
      Instant weekEnd,
      Instant weekStart) {
    printSuccessRateHeader();
    printSuccessRate(
        "global",
        jobResults.stream().collect(Collectors.groupingBy(o -> o.result(), Collectors.counting())));
    printSuccessRate(
        "today",
        jobResults.stream()
            .filter(
                jenkinsJobResult ->
                    nowMinus1Day.toEpochMilli() < jenkinsJobResult.timestamp()
                        && jenkinsJobResult.timestamp() < now.toEpochMilli())
            .collect(Collectors.groupingBy(o -> o.result(), Collectors.counting())));

    printSuccessRate(
        "yesterday",
        jobResults.stream()
            .filter(
                jenkinsJobResult ->
                    yesterdayStart.toEpochMilli() < jenkinsJobResult.timestamp()
                        && jenkinsJobResult.timestamp() < yesterdayEnd.toEpochMilli())
            .collect(Collectors.groupingBy(o -> o.result(), Collectors.counting())));

    printSuccessRate(
        "last week",
        jobResults.stream()
            .filter(
                jenkinsJobResult ->
                    weekStart.toEpochMilli() < jenkinsJobResult.timestamp()
                        && jenkinsJobResult.timestamp() < weekEnd.toEpochMilli())
            .collect(Collectors.groupingBy(o -> o.result(), Collectors.counting())));

    jobResults.stream()
        .collect(Collectors.groupingBy(JenkinsJobResult::jobName))
        .forEach(
            (jobName, jenkinsJobResults) -> {
              Map<String, Long> counts =
                  jenkinsJobResults.stream()
                      .collect(Collectors.groupingBy(o -> o.result(), Collectors.counting()));
              printSuccessRate(jobName, counts);
            });
  }

  void printAvgTimeHeader() {
    consoleWriter.a("name, today avg, yesterday avg, week avg, global avg").println();
  }

  /** duration are in milliseconds */
  void printAvgTimes(
      String jobName, long avgCurrentDay, long avgYesterday, long avgWeek, long avgGlobal) {
    if (printDurationInDecimalDays) {
      avgCurrentDay = Duration.ofMillis(avgCurrentDay).toDays();
      avgYesterday = Duration.ofMillis(avgYesterday).toDays();
      avgWeek = Duration.ofMillis(avgWeek).toDays();
      avgGlobal = Duration.ofMillis(avgGlobal).toDays();
    }
    consoleWriter
        .a(jobName + "," + avgCurrentDay + "," + avgYesterday + "," + avgWeek + "," + avgGlobal)
        .println();
  }

  void printSuccessRateHeader() {
    consoleWriter.a("name, success, failure, rate").println();
  }

  void printSuccessRate(String name, Map<String, Long> resultCounts) {
    Long success = resultCounts.getOrDefault("SUCCESS", 0L);
    Long failure = resultCounts.getOrDefault("FAILURE", 0L);
    consoleWriter
        .a(name + "," + success + "," + failure + "," + (double) success / (success + failure))
        .println();
  }

  ImmutableList<JenkinsJobResult> getJobResults(List<String> jobUrls) {
    return jobUrls.stream()
        .map(this::getJobResults)
        .flatMap(
            jenkinsJobResults ->
                jenkinsJobResults.builds().stream()
                    .map(
                        jenkinsJobResult ->
                            jenkinsJobResult.withJobName(jenkinsJobResults.displayName())))
        .filter(jenkinsJobResult -> jenkinsJobResult.result() != null)
        .collect(ImmutableList.toImmutableList());
  }

  JenkinsJobResults getJobResults(String baseJobUrl) {
    try {
      HttpURLConnection httpURLConnection =
          (HttpURLConnection)
              new URL(
                      baseJobUrl
                          + "/api/json?tree=builds[number,id,timestamp,result,duration],displayName")
                  .openConnection();
      httpURLConnection.setRequestMethod("GET");
      httpURLConnection.setRequestProperty("Content-Type", "application/json");
      httpURLConnection.setRequestProperty("cookie", getCookieForAuth(baseJobUrl));
      httpURLConnection.setRequestProperty("accept", "application/json");
      httpURLConnection.getResponseCode();
      InputStream responseStream = httpURLConnection.getInputStream();
      JenkinsJobResults jenkinsJobResults =
          mapper.readValue(responseStream, JenkinsJobResults.class);
      return jenkinsJobResults;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  String getCookieForAuth(String baseJobUrl) {
    return jenkinsStatsConfig.authCookies().entrySet().stream()
        .filter(e -> baseJobUrl.contains(e.getKey()))
        .findFirst()
        .map(Map.Entry::getValue)
        .orElseThrow(() -> new RuntimeException("Must have cookie for: " + baseJobUrl));
  }

  void initFromConfigurationFile() {
    try {
      jenkinsStatsConfig =
          mapper.readValueUnchecked(Paths.get(config).toFile(), JenkinsStatsConfig.class);
    } catch (Exception e) {
      throw new CommandException(
          "Invalid config file, format: \n"
              + mapper.writeValueAsStringUnchecked(
                  new JenkinsStatsConfig(
                      ImmutableList.of(
                          "https://jenkins1.org/job/mojito-test",
                          "https://jenkins2.org/job/mojito-test2"),
                      ImmutableMap.of(
                          "jenkins1.org", "cookie value",
                          "jenkins2.org", "cookie2 value"))));
    }
  }
}
