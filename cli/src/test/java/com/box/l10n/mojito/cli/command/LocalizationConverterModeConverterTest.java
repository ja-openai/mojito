package com.box.l10n.mojito.cli.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.box.l10n.mojito.cli.command.param.Param;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection.Mode;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class LocalizationConverterModeConverterTest {

  private final LocalizationConverterModeConverter converter =
      new LocalizationConverterModeConverter();

  @Test
  public void convertsLowercasePortableMode() {
    assertEquals(Mode.PORTABLE, converter.convert("portable"));
  }

  @Test
  public void convertsLowercaseOkapiMode() {
    assertEquals(Mode.OKAPI, converter.convert("okapi"));
  }

  @Test
  public void convertsTrimmedMixedCaseMode() {
    assertEquals(Mode.PORTABLE, converter.convert(" Portable "));
  }

  @Test(expected = ParameterException.class)
  public void rejectsUnsupportedMode() {
    converter.convert("native");
  }

  @Test
  public void pullAndPushCommandsUseCaseInsensitiveConverter() throws Exception {
    assertEquals(LocalizationConverterModeConverter.class, converterFor(PullCommand.class));
    assertEquals(LocalizationConverterModeConverter.class, converterFor(PushCommand.class));
  }

  @Test
  public void pullAndPushDefaultToOkapi() {
    assertEquals(Mode.OKAPI, new PullCommand().converter);
    assertEquals(Mode.OKAPI, new PushCommand().converter);
  }

  @Test
  public void legacyJsonMigrationRequiresExplicitPortableConverter() {
    PushCommand pushCommand = new PushCommand();
    pushCommand.migrateLegacyJsonComments = true;

    try {
      pushCommand.execute();
    } catch (CommandException expected) {
      assertEquals(
          "--migrate-legacy-json-comments requires --converter portable", expected.getMessage());
      return;
    }
    throw new AssertionError("Expected explicit portable converter validation");
  }

  @Test
  public void parallelPullPreservesSelectedConverter() {
    PullCommand pullCommand = new PullCommand();
    pullCommand.converter = Mode.PORTABLE;
    pullCommand.setOriginalArgs(List.of("pull", "--converter", "portable"));

    PullCommandParallel parallel = new PullCommandParallel(pullCommand);
    assertEquals(Mode.PORTABLE, parallel.converter);
    assertEquals(pullCommand.originalArgs, parallel.originalArgs);
    assertTrue(parallel.hasExplicitConverter());
  }

  @Test
  public void detectsExplicitConverterInsideArgumentFile() throws Exception {
    Path argumentFile = Files.createTempFile("mojito-converter", ".args");
    try {
      Files.writeString(argumentFile, "--repository\ntest-repository\n--converter\nokapi\n");
      JCommander commander = new JCommander();
      commander.addCommand(new PullCommand());

      commander.parse("pull", "@" + argumentFile);

      assertTrue(L10nJCommander.isParameterAssigned(commander, "pull", Param.CONVERTER_LONG));
    } finally {
      Files.deleteIfExists(argumentFile);
    }
  }

  @Test
  public void defaultConverterIsNotMarkedExplicit() {
    JCommander commander = new JCommander();
    commander.addCommand(new PullCommand());

    commander.parse("pull", "--repository", "test-repository");

    assertFalse(L10nJCommander.isParameterAssigned(commander, "pull", Param.CONVERTER_LONG));
  }

  private static Class<? extends IStringConverter<?>> converterFor(Class<?> command)
      throws NoSuchFieldException {
    Field converter = command.getDeclaredField("converter");
    return converter.getAnnotation(Parameter.class).converter();
  }
}
