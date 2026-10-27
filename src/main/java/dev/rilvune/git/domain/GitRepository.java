package dev.rilvune.git.domain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Query-only boundary. Revision-bearing requests contain resolved, immutable object IDs. */
public interface GitRepository {
    GitFileRevision fileRevision(RepositoryLocation repository, String commit, String path) throws IOException;
    List<GitBlameLine> blame(RepositoryLocation repository, String commit, String path) throws IOException;
    RepositoryLocation locate(Path project) throws IOException;
    LocalGitStatus status(RepositoryLocation repository) throws IOException;
    String statusFingerprint(RepositoryLocation repository, List<GitChange> paths) throws IOException;
    List<GitRef> references(RepositoryLocation repository) throws IOException;
    java.util.Set<String> ignored(RepositoryLocation repository, List<String> paths) throws IOException;
    String ignoreFingerprint(RepositoryLocation repository, java.util.Set<String> paths) throws IOException;
    RepositoryState state(RepositoryLocation repository) throws IOException;
    HistoryPage history(RepositoryLocation repository, HistoryQuery query, int offset, int count) throws IOException;
    FileHistoryPage fileHistory(RepositoryLocation repository, FileHistoryQuery query, int offset, int count) throws IOException;
    GitCommit commit(RepositoryLocation repository, String objectId) throws IOException;
    String resolve(RepositoryLocation repository, String revision) throws IOException;
    List<GitChange> changes(RepositoryLocation repository, GitSource left, GitSource right) throws IOException;
    GitContent content(RepositoryLocation repository, GitSource source, String path) throws IOException;
    String fingerprint(RepositoryLocation repository, List<GitChange> paths) throws IOException;
}
