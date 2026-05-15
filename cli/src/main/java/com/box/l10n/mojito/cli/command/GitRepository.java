package com.box.l10n.mojito.cli.command;

import com.box.l10n.mojito.rest.entity.GitBlame;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.MessageFormat;
import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitRepository {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(GitRepository.class);

  Repository jgitRepository;

  File discoveredGitDir;
  File commonGitDir;

  /**
   * Init a git repository if current directory is within a git repository
   *
   * @throws CommandException
   */
  public void init(String gitDir) throws CommandException {

    logger.debug("Init the jgit Repository");

    FileRepositoryBuilder builder = new FileRepositoryBuilder();

    try {
      builder.findGitDir(new File(gitDir)).readEnvironment();
      configureForWorktree(builder);
      jgitRepository = builder.build();
    } catch (IOException ioe) {
      throw new CommandException("Can't build the git repository", ioe);
    }
  }

  void configureForWorktree(FileRepositoryBuilder builder) throws IOException {
    discoveredGitDir = builder.getGitDir();
    if (discoveredGitDir == null) {
      return;
    }
    discoveredGitDir = discoveredGitDir.getCanonicalFile();

    File workTree = getWorkTree(discoveredGitDir);
    if (workTree != null) {
      builder.setWorkTree(workTree);
    }

    File commonDirFile = new File(discoveredGitDir, "commondir");
    if (!commonDirFile.isFile()) {
      return;
    }

    String commonDirPath = Files.readString(commonDirFile.toPath(), StandardCharsets.UTF_8).trim();
    if (commonDirPath.isEmpty()) {
      return;
    }

    commonGitDir = new File(commonDirPath);
    if (!commonGitDir.isAbsolute()) {
      commonGitDir = new File(discoveredGitDir, commonDirPath);
    }
    commonGitDir = commonGitDir.getCanonicalFile();

    builder.setObjectDirectory(new File(commonGitDir, "objects"));
  }

  File getWorkTree(File discoveredGitDir) throws IOException {
    File worktreeGitDirFile = new File(discoveredGitDir, "gitdir");
    if (worktreeGitDirFile.isFile()) {
      String worktreeGitDirPath =
          Files.readString(worktreeGitDirFile.toPath(), StandardCharsets.UTF_8).trim();
      if (!worktreeGitDirPath.isEmpty()) {
        File worktreeGitDir = new File(worktreeGitDirPath);
        if (!worktreeGitDir.isAbsolute()) {
          worktreeGitDir = new File(discoveredGitDir, worktreeGitDirPath);
        }
        return worktreeGitDir.getCanonicalFile().getParentFile();
      }
    }

    return discoveredGitDir.getCanonicalFile().getParentFile();
  }

  /**
   * Get the git-blame information for given line number
   *
   * @param lineNumber
   * @param blameResultForFile
   * @return
   */
  public GitBlame getBlameResults(int lineNumber, BlameResult blameResultForFile)
      throws LineMissingException {

    GitBlame gitBlame = new GitBlame();

    try {
      gitBlame.setAuthorName(blameResultForFile.getSourceAuthor(lineNumber).getName());
      gitBlame.setAuthorEmail(blameResultForFile.getSourceAuthor(lineNumber).getEmailAddress());
      gitBlame.setCommitName(blameResultForFile.getSourceCommit(lineNumber).getName());
      gitBlame.setCommitTime(
          Integer.toString(blameResultForFile.getSourceCommit(lineNumber).getCommitTime()));
    } catch (ArrayIndexOutOfBoundsException e) {
      String msg =
          MessageFormat.format("The line: {0} is not available in the file anymore", lineNumber);
      logger.debug(msg);
      throw new LineMissingException(msg);
    }

    return gitBlame;
  }

  /**
   * Get the git-blame information for entire file
   *
   * @param filePath
   * @return
   * @throws CommandException
   */
  public BlameResult getBlameResultForFile(String filePath) throws CommandException {

    logger.debug("getBlameResultForFile: {}", filePath);
    try {
      BlameCommand blamer = new BlameCommand(jgitRepository);
      ObjectId commitID = resolveHead();
      blamer.setStartCommit(commitID);
      blamer.setFilePath(filePath);
      BlameResult blame = blamer.call();

      return blame;
    } catch (GitAPIException | IOException e) {
      String msg = MessageFormat.format("Can't get blame result for file: {0}", filePath);
      logger.error(msg, e);
      throw new CommandException(msg, e);
    }
  }

  public ObjectId resolve(String revision) throws IOException {
    ObjectId objectId = jgitRepository.resolve(revision);
    if (objectId == null && "HEAD".equals(revision)) {
      objectId = resolveLinkedWorktreeHead();
    }
    return objectId;
  }

  public ObjectId resolveHead() throws IOException {
    return resolve("HEAD");
  }

  ObjectId resolveLinkedWorktreeHead() throws IOException {
    if (discoveredGitDir == null) {
      return null;
    }
    return resolveHeadFromGitDir(discoveredGitDir);
  }

  ObjectId resolveHeadFromGitDir(File gitDir) throws IOException {
    File headFile = new File(gitDir, "HEAD");
    if (!headFile.isFile()) {
      return null;
    }

    return resolveRefValue(Files.readString(headFile.toPath(), StandardCharsets.UTF_8).trim());
  }

  ObjectId resolveRefValue(String refValue) throws IOException {
    if (ObjectId.isId(refValue)) {
      return ObjectId.fromString(refValue);
    }

    String refPrefix = "ref:";
    if (!refValue.startsWith(refPrefix)) {
      return null;
    }

    String refName = refValue.substring(refPrefix.length()).trim();
    ObjectId objectId = resolveRef(discoveredGitDir, refName);
    if (objectId == null && commonGitDir != null) {
      objectId = resolveRef(commonGitDir, refName);
    }
    return objectId;
  }

  ObjectId resolveRef(File gitDir, String refName) throws IOException {
    if (gitDir == null) {
      return null;
    }

    File refFile = new File(gitDir, refName);
    if (refFile.isFile()) {
      return resolveRefValue(Files.readString(refFile.toPath(), StandardCharsets.UTF_8).trim());
    }

    return resolvePackedRef(gitDir, refName);
  }

  ObjectId resolvePackedRef(File gitDir, String refName) throws IOException {
    File packedRefsFile = new File(gitDir, "packed-refs");
    if (!packedRefsFile.isFile()) {
      return null;
    }

    for (String line : Files.readAllLines(packedRefsFile.toPath(), StandardCharsets.UTF_8)) {
      if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '^') {
        continue;
      }

      String[] parts = line.split(" ", 2);
      if (parts.length == 2 && refName.equals(parts[1]) && ObjectId.isId(parts[0])) {
        return ObjectId.fromString(parts[0]);
      }
    }
    return null;
  }

  public File getDirectory() {
    return jgitRepository.getDirectory();
  }

  public File getWorkTree() {
    return jgitRepository.getWorkTree();
  }
}
