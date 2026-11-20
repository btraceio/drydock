package app.drydock.sdlc;

import app.drydock.domain.RepositoryId;

import java.util.Objects;
import java.util.Optional;

/**
 * Per-invocation snapshot of a selected workflow: registered repository, target,
 * where it came from, the exact prompt text, the supporting bundle and a digest
 * over all of it. Holds no path, secret or filesystem handle, so later source or
 * harness edits cannot change it.
 */
public record SelectedWorkflow(
        RepositoryId repositoryId,
        WorkflowTarget target,
        SourceDescriptor source,
        String promptText,
        WorkflowBundle bundle,
        String digest) {

    public SelectedWorkflow {
        Objects.requireNonNull(repositoryId, "repositoryId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(promptText, "promptText");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(digest, "digest");
        if (digest.isBlank()) {
            throw new IllegalArgumentException("digest must not be blank");
        }
    }

    public enum SourceKind { INLINE, FILE }

    /** Where the prompt came from: config key and, for a file, its repository-relative path. */
    public record SourceDescriptor(SourceKind kind, String configKey, Optional<String> relativeSource) {
        public SourceDescriptor {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(configKey, "configKey");
            Objects.requireNonNull(relativeSource, "relativeSource");
            relativeSource.ifPresent(r -> {
                if (r.startsWith("/") || r.indexOf('\\') >= 0 || r.contains("..")) {
                    throw new IllegalArgumentException("source must be repository-relative: " + r);
                }
            });
            if (kind == SourceKind.FILE && relativeSource.isEmpty()) {
                throw new IllegalArgumentException("file source needs a relative path");
            }
        }
    }
}
