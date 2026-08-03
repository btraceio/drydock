package app.drydock.ui.explorer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A file's shape: its members, their signatures and the lines they span
 * (Explorer delta, part 2, "skim mode").
 *
 * <p><strong>A brace-and-indent outline, not a parser.</strong> Skim mode's
 * promise is "here is the shape of this file"; delivering that with a real
 * parser would mean one per language, and delivering it with regular
 * expressions across the whole file would mean lying about nested types.
 * The compromise is a brace-depth walk: members are the declarations at the
 * top level of the first type in the file, each spanning to the brace that
 * closes it. A file with no braces at all (Markdown, a properties file) has
 * one member -- itself -- and skim mode is then simply the whole file, which
 * is the honest answer.</p>
 *
 * <p>Pure model, unit-tested. Everything the minimap and the skim rows show
 * is derived from here, so this is where "the ticks match the changed set"
 * is actually decided.</p>
 */
public record SourceOutline(List<Member> members, int lineCount) {

    /** Signatures that are worth a row of their own. */
    public record Member(String signature, int startLine, int endLine, boolean privateHelper) {
        public Member {
            signature = signature.strip();
        }

        public int lines() {
            return Math.max(1, endLine - startLine + 1);
        }

        /** Whether this member covers {@code line} (1-based). */
        public boolean covers(int line) {
            return line >= startLine && line <= endLine;
        }

        /** Whether any of {@code changed} falls inside this member. */
        public boolean isChanged(Set<Integer> changed) {
            for (int line : changed) {
                if (covers(line)) {
                    return true;
                }
            }
            return false;
        }
    }

    public SourceOutline {
        members = List.copyOf(members);
    }

    /** The member containing {@code line}, or empty between members (blank lines, imports). */
    public java.util.Optional<Member> memberAt(int line) {
        return members.stream().filter(member -> member.covers(line)).findFirst();
    }

    /** Language-independent enough for Java, Kotlin, JS/TS, C-likes; everything else falls back to one member. */
    public static SourceOutline parse(String text) {
        List<String> lines = text.isEmpty() ? List.of() : List.of(text.split("\n", -1));
        int total = Math.max(1, lines.size());
        List<Member> members = new ArrayList<>();

        int depth = 0;
        int bodyDepth = -1;
        int memberStart = -1;
        StringBuilder pendingSignature = new StringBuilder();
        int pendingStart = -1;

        for (int index = 0; index < lines.size(); index++) {
            String raw = lines.get(index);
            String line = stripComment(raw).strip();
            int lineNumber = index + 1;

            if (memberStart < 0 && bodyDepth >= 0 && !line.isEmpty() && depth == bodyDepth) {
                // A member starts at the first non-blank line inside the type
                // body; a signature may run over several lines, so remember
                // where it began and keep appending until it resolves.
                if (pendingStart < 0) {
                    pendingStart = lineNumber;
                    pendingSignature.setLength(0);
                }
                pendingSignature.append(pendingSignature.isEmpty() ? "" : " ").append(line);
            }

            int depthBefore = depth;
            boolean opened = false;
            // Counted on the comment-stripped line: a trailing `// see {}`
            // must not move the depth. Braces inside string literals still
            // do -- catching those needs a lexer per language, and the cost
            // of being wrong is one badly-bounded member, not a broken view.
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '{') {
                    depth++;
                    opened = true;
                    if (bodyDepth < 0) {
                        // The first brace in the file opens the type body;
                        // its members live one level in.
                        bodyDepth = depth;
                    }
                } else if (ch == '}') {
                    depth--;
                    // Back at the body level: the brace that opened this
                    // member has just closed. (Not `< bodyDepth`, which is
                    // the type's OWN closing brace and one level too late.)
                    if (memberStart >= 0 && depth <= bodyDepth) {
                        members.add(new Member(pendingSignature.toString(), memberStart, lineNumber,
                                isPrivate(pendingSignature.toString())));
                        memberStart = -1;
                        pendingStart = -1;
                        pendingSignature.setLength(0);
                    }
                }
            }

            if (memberStart < 0 && pendingStart >= 0) {
                if (opened && depthBefore == bodyDepth && depth > bodyDepth) {
                    // A block member (method, nested type): it runs until the
                    // brace it just opened closes again.
                    memberStart = pendingStart;
                } else if (opened && depthBefore == bodyDepth) {
                    // Opened AND closed on the same line -- `String name() {
                    // return name; }`. The loop above could not emit it
                    // (memberStart was still unset when the `}` came round),
                    // and leaving it pending would make it adopt the NEXT
                    // member's closing brace: the one-liner would swallow its
                    // neighbour, and that neighbour would lose its row.
                    members.add(new Member(pendingSignature.toString(), pendingStart, lineNumber,
                            isPrivate(pendingSignature.toString())));
                    pendingStart = -1;
                    pendingSignature.setLength(0);
                } else if (line.endsWith(";") && depth == bodyDepth) {
                    // A one-line member: a field, a constant, an abstract
                    // signature.
                    members.add(new Member(pendingSignature.toString(), pendingStart, lineNumber,
                            isPrivate(pendingSignature.toString())));
                    pendingStart = -1;
                    pendingSignature.setLength(0);
                }
            }
        }
        if (memberStart >= 0) {
            // An unterminated block (a truncated file) still gets its row,
            // running to the end -- a missing member reads as "this file has
            // no such code", which is worse than a slightly long one.
            members.add(new Member(pendingSignature.toString(), memberStart, total,
                    isPrivate(pendingSignature.toString())));
        }
        if (members.isEmpty()) {
            members = List.of(new Member("(whole file)", 1, total, false));
        }
        return new SourceOutline(members, total);
    }

    private static boolean isPrivate(String signature) {
        String lower = signature.toLowerCase(Locale.ROOT).strip();
        return lower.startsWith("private ") || lower.startsWith("- ") || lower.startsWith("internal ");
    }

    /** Drops a trailing line comment so {@code foo(); // note {} } does not move the brace depth. */
    private static String stripComment(String line) {
        int at = line.indexOf("//");
        return at < 0 ? line : line.substring(0, at);
    }
}
