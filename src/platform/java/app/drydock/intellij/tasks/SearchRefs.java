package app.drydock.intellij.tasks;

import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.tasks.TaskMirror;
import app.drydock.tasks.TaskNode.DelayedTaskRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure, shared derivation of the pull-request and issue references owned by inbox/tree items. */
final class SearchRefs {

    record ItemRefs(List<SearchTerms.PrRef> pullRequests, List<SearchTerms.IssueRef> issues) {
        ItemRefs {
            pullRequests = List.copyOf(pullRequests);
            issues = List.copyOf(issues);
        }
    }

    record LooseTaskRefs(String title, ItemRefs refs) {
        LooseTaskRefs {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(refs, "refs");
        }
    }

    private SearchRefs() {
    }

    /** A session's own PRs followed by attached task PRs, plus attached task mirror issues. */
    static ItemRefs sessionRefs(TaskRow row, List<DelayedTaskRow> attached) {
        List<SearchTerms.PrRef> pullRequests = new ArrayList<>();
        row.branchPullRequest().map(pr -> new SearchTerms.PrRef(pr.number(), pr.title(), pr.headRefName(),
                Optional.empty())).ifPresent(pullRequests::add);
        row.session().targetPullRequest().ifPresent(target -> pullRequests.add(
                new SearchTerms.PrRef(target.number(), "", "", Optional.empty())));
        List<SearchTerms.IssueRef> issues = new ArrayList<>();
        for (DelayedTaskRow task : attached) {
            ItemRefs refs = looseTaskRefs(task);
            pullRequests.addAll(refs.pullRequests());
            issues.addAll(refs.issues());
        }
        return new ItemRefs(pullRequests, issues);
    }

    /** The references carried by a standalone delayed-task row. */
    static ItemRefs looseTaskRefs(DelayedTaskRow row) {
        List<SearchTerms.PrRef> pullRequests = row.pullRequest()
                .map(pr -> List.of(new SearchTerms.PrRef(pr.number(), pr.title(), pr.headRefName(), pr.author())))
                .orElse(List.of());
        List<SearchTerms.IssueRef> issues = row.mirrors().stream()
                .filter(url -> TaskMirror.kindOf(url).isPresent())
                .map(url -> new SearchTerms.IssueRef(TaskMirror.label(url), Optional.empty()))
                .toList();
        return new ItemRefs(pullRequests, issues);
    }
}
