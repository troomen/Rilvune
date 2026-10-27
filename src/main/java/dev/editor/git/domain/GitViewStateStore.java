package dev.editor.git.domain;
import java.io.IOException;
public interface GitViewStateStore { GitViewState load() throws IOException; void save(GitViewState state) throws IOException; }
