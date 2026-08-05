# session_rename MCP Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a hosted agent an MCP tool that renames its own session, so the tab rail says what the work is instead of what branch it is on.

**Architecture:** The tool writes the session's real `displayName`. A new `boolean namePinned` on `ManagedAgentSession` makes a human's explicit rename permanent. The agent path runs router → `McpSessionContext` → an FX hop in `MainWorkspace` → a single `ApplicationStateStore` transform in `SessionManager` that decides among four outcomes, then `publishSessions()` — the only thing that repaints a tab.

**Tech Stack:** Java 21+, JavaFX, Gradle, JUnit 5. No mocking framework — tests use hand-written fakes.

**Spec:** `docs/superpowers/specs/2026-08-05-session-rename-mcp-design.md`. Read it before Task 1; the validation and pin sections carry reasoning this plan does not repeat.

## Global Constraints

- **Build/test command:** `./gradlew :app:test --tests "<fully.qualified.TestClass>"`. Full suite: `./gradlew :app:test`.
- **Run gradle in the foreground.** A backgrounded run ends a subagent's turn with no report.
- **AGENTS.md is binding.** In particular: never block the FX thread; one writer for persistent state (submit transforms, never load-then-save); use imports, never inline fully-qualified class names.
- **Task order is a landing order.** Tasks 1–3 fix pre-existing bugs and must land first; they do not reference any type this feature introduces.
- **Budget cap:** `MAX_RENAMES_PER_SESSION = 20`.
- **Timeouts:** `WorkspaceMcpSessionContext.RENAME_TIMEOUT_SECONDS = 25` (public); `MainWorkspace.AGENT_RENAME_BUDGET_SECONDS = RENAME_TIMEOUT_SECONDS / 2` (= 12).
- **Title cap:** 60 code points, measured after folding.

**Names other tasks depend on** (define exactly these):

```java
// app.drydock.mcp.PromptSafety
public static String checkSessionTitle(String title) throws McpToolException

// app.drydock.mcp.McpSessionRegistry
public static final int MAX_RENAMES_PER_SESSION = 20;
public void chargeRename(ManagedSessionId sessionId) throws McpBudgetExhaustedException
public void refundRename(ManagedSessionId sessionId)

// app.drydock.mcp.McpSessionContext (nested)
enum RenameKind { RENAMED, UNCHANGED, PINNED, COLLIDED }
record RenameOutcome(RenameKind kind, String currentName) {}
RenameOutcome renameSession(ManagedSessionId caller, String title) throws McpToolException

// app.drydock.app.SessionManager
public RenameOutcome applyAgentRename(ManagedSessionId sessionId, String title)
public ManagedAgentSession renameSession(ManagedSessionId sessionId, String newDisplayName, boolean pin)

// app.drydock.ui.MainWorkspace
public void renameSession(ManagedSessionId sessionId, String newDisplayName, boolean pin)
public CompletableFuture<RenameOutcome> renameSessionFromAgent(ManagedSessionId id, String title)

// app.drydock.domain.ManagedAgentSession
boolean namePinned            // 16th record component, last
public ManagedAgentSession withNamePinned(boolean newNamePinned)
```

---

### Task 1: Sanitize the MCP activity-log argument summary

`McpServer.logActivity` records every call **including failures**, and `summarize` renders raw arguments into a JavaFX `Label`. `JsonWriter` escapes only `c < 0x20`, so U+007F, the C1 block, U+202E, U+200B and the tag block all reach the panel. This is every tool's argument sink, not just this feature's.

**Files:**
- Modify: `app/src/main/java/app/drydock/mcp/McpServer.java` (the `summarize` method, ~`:328-334`)
- Test: `app/src/test/java/app/drydock/mcp/McpServerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing other tasks reference.

- [ ] **Step 1: Write the failing tests**

`summarize` is private and static. Make it package-private (drop `private`) so the test in the same package can call it directly — no behaviour change, and it keeps the test off the HTTP path.

Add to `McpServerTest`:

```java
@Test
void summarizeReplacesInvisibleCodePoints() {
    JsonObject args = JsonObject.empty().put("title", new JsonString("a‮b​cd"));

    String summary = McpServer.summarize(args);

    assertFalse(summary.contains("‮"), "bidi override survived: " + summary);
    assertFalse(summary.contains("​"), "zero-width space survived: " + summary);
    assertFalse(summary.contains(""), "DEL survived: " + summary);
    assertTrue(summary.contains("�"), "nothing was replaced: " + summary);
}

@Test
void summarizeKeepsMultiMemberObjectOnOneLine() {
    JsonObject args = JsonObject.empty()
            .put("scopeId", new JsonString("s1"))
            .put("title", new JsonString("ok"));

    String summary = McpServer.summarize(args);

    assertFalse(summary.contains("\n"), "structural newline survived: " + summary);
    assertFalse(summary.contains("�"), "structural whitespace was replaced: " + summary);
}

@Test
void summarizeTruncationNeverSplitsASurrogatePair() {
    String emoji = "😀"; // U+1F600, one code point, two chars
    JsonObject args = JsonObject.empty().put("title", new JsonString(emoji.repeat(200)));

    String summary = McpServer.summarize(args);

    assertTrue(summary.length() <= 161, "not truncated: " + summary.length());
    for (int i = 0; i < summary.length(); i++) {
        char c = summary.charAt(i);
        if (Character.isHighSurrogate(c)) {
            assertTrue(i + 1 < summary.length() && Character.isLowSurrogate(summary.charAt(i + 1)),
                    "unpaired high surrogate at " + i);
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpServerTest"`
Expected: FAIL — the first test finds U+202E in the output; the third finds a trailing lone surrogate or a length over 161.

- [ ] **Step 3: Implement**

Replace `summarize` with:

```java
    /**
     * A one-line detail for the panel: the arguments, bounded and stripped of
     * anything that can lie in a Label.
     *
     * <p>Order matters. The whitespace collapse runs FIRST: Java's {@code \s}
     * is ASCII-only, so it flattens {@link JsonWriter}'s own pretty-print
     * newlines and indents and nothing else. Only then are the invisible and
     * control categories replaced -- sanitizing first would replace those
     * structural newlines too and turn every row into "{&#xFFFD; ...".
     *
     * <p>{@code JsonWriter} escapes only {@code c < 0x20}, so U+007F, the C1
     * block, the bidi overrides and the tag block all arrive here verbatim.
     * The panel renders this as a {@code Label}, on failed calls as well as
     * successful ones, so this is the boundary that has to remove them.
     */
    static String summarize(JsonValue arguments) {
        if (arguments == null) {
            return "";
        }
        String collapsed = JsonWriter.write(arguments).replaceAll("\\s+", " ");
        StringBuilder clean = new StringBuilder(collapsed.length());
        collapsed.codePoints().forEach(cp -> clean.appendCodePoint(isUnrenderable(cp) ? '�' : cp));
        return truncateByCodePoints(clean.toString(), 160);
    }

    /** Categories that must never reach a Label: invisible, reordering, or not a character at all. */
    private static boolean isUnrenderable(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                 Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
            default -> false;
        };
    }

    /** Truncates on a code-point boundary, so a cut never re-creates a lone surrogate. */
    private static String truncateByCodePoints(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxCodePoints - 1);
        return text.substring(0, end) + "…";
    }
```

`Character.getType` returns `int`; the `case` labels above are `int` constants, so this switch compiles as an old-style `switch` over `int`. If the compiler objects to the arrow form with `int` constants, use an `if` chain instead — the behaviour is what matters.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpServerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/McpServer.java app/src/test/java/app/drydock/mcp/McpServerTest.java
git commit -m "The MCP activity panel stops rendering what it cannot show honestly"
```

---

### Task 2: Classify activity-log direction by an explicit tool set

`logActivity` picks INBOUND/OUTBOUND with `tool.startsWith("review_") && !review_scope && !review_state`. That prefix rule cannot express "an agent read", and it would log a new `session_rename` as OUTBOUND. Replacing it deliberately reclassifies exactly one existing tool: `review_comments`, which is a read.

**Files:**
- Modify: `app/src/main/java/app/drydock/mcp/McpServer.java` (the direction expression at ~`:317`)
- Test: `app/src/test/java/app/drydock/mcp/McpServerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `McpServer.directionOf(String tool)` — package-private, used only by tests.

- [ ] **Step 1: Write the failing test**

```java
@Test
void activityDirectionIsClassifiedByAnExplicitSet() {
    // an agent write
    assertEquals(McpActivityLog.Direction.INBOUND, McpServer.directionOf("review_finding"));
    // a read that the old startsWith("review_") rule got wrong
    assertEquals(McpActivityLog.Direction.OUTBOUND, McpServer.directionOf("review_comments"));
    // the new tool, which the old rule would have called OUTBOUND
    assertEquals(McpActivityLog.Direction.INBOUND, McpServer.directionOf("session_rename"));
    // unknown tools are reads, not writes
    assertEquals(McpActivityLog.Direction.OUTBOUND, McpServer.directionOf("repos_list"));
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpServerTest"`
Expected: FAIL — `directionOf` does not exist.

- [ ] **Step 3: Implement**

Add the set and the method, and use it in `logActivity`:

```java
    /**
     * The tools that WRITE into drydock. Everything else is drydock answering
     * a question, including the tools whose names begin "review_".
     *
     * <p>An explicit set rather than the name-prefix rule this replaced: that
     * rule classified {@code review_comments} as a write, which it is not --
     * the agent asks for open threads and drydock answers -- and it would
     * have classified {@code session_rename} as a read. A prefix cannot
     * express either, and every tool added later must be classified on
     * purpose rather than by how it was named.</p>
     */
    private static final Set<String> AGENT_WRITE_TOOLS = Set.of(
            "review_reply", "review_intents", "review_finding", "review_answer", "session_rename");

    static McpActivityLog.Direction directionOf(String tool) {
        return AGENT_WRITE_TOOLS.contains(tool)
                ? McpActivityLog.Direction.INBOUND
                : McpActivityLog.Direction.OUTBOUND;
    }
```

In `logActivity`, replace the inline conditional with `directionOf(tool)`:

```java
                activityLog.record(new McpActivityLog.Entry(Instant.now(),
                        directionOf(tool),
                        tool, summarize(arguments), scopeId, bytes, failed));
```

Add `import java.util.Set;` if it is not already imported.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpServerTest"`
Expected: PASS. Then run `./gradlew :app:test --tests "app.drydock.mcp.*"` — no other test should depend on the old classification.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/McpServer.java app/src/test/java/app/drydock/mcp/McpServerTest.java
git commit -m "MCP activity direction is a decision, not a name prefix"
```

---

### Task 3: Stop two session-name labels from setting the window's minimum width

`RepositorySidebar`'s session-name `Label` and `OpenSessionTab`'s `headerTitle` have no max width and no overrun. A `Label`'s min width defaults to its pref width, so a long title sets the sidebar's — then the window's — minimum width. The tab-rail label was already defended with `setMaxWidth(160)`; these two were not. This is a pre-existing bug that a 60-code-point title makes easy to hit.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java:1491`
- Modify: `app/src/main/java/app/drydock/ui/OpenSessionTab.java:629`
- Test: none automated — a layout property the headless suite cannot see. Verified by the manual checklist in Task 13.

**Interfaces:**
- Consumes: nothing. Produces: nothing.

- [ ] **Step 1: Fix the sidebar row label**

At `RepositorySidebar.java:1491`, after `name.getStyleClass().add("session-name");`:

```java
            // The name is agent-authored (session_rename) and can be 60 code
            // points of full-width CJK. A Label's min width is its pref
            // width, so without this the row -- and then the window -- takes
            // its minimum width from the title. The clamp resolves against
            // the HGROW'd `text` column below, not against the text.
            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
```

Add `import javafx.scene.control.OverrunStyle;`.

- [ ] **Step 2: Fix the session header label**

At `OpenSessionTab.java:629`, after `headerTitle.getStyleClass().add("session-title");`:

```java
        // Same hazard as the sidebar row: agent-authored text in a Label with
        // no clamp. headerTitles is the HGROW'd node inside the header HBox,
        // so the max width resolves against the header, not the title.
        headerTitle.setMinWidth(0);
        headerTitle.setMaxWidth(Double.MAX_VALUE);
        headerTitle.setTextOverrun(OverrunStyle.ELLIPSIS);
```

Add `import javafx.scene.control.OverrunStyle;` if absent.

- [ ] **Step 3: Verify the suite still passes**

Run: `./gradlew :app:test`
Expected: PASS — no test covers layout, so this confirms nothing regressed.

- [ ] **Step 4: Verify by eye**

Launch the app, rename a session by hand to 60 full-width CJK characters (paste `工作工作工作…`), and confirm: the sidebar row ellipsizes, the session header ellipsizes, and the window can still be narrowed to its previous minimum.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java app/src/main/java/app/drydock/ui/OpenSessionTab.java
git commit -m "A long session name no longer holds the window open"
```

---

### Task 4: Add `namePinned` to the session record and its codec

Mechanical and wide: a 16th record component, ten hand-written `withX` copy methods that each re-list every component, and 26 `new ManagedAgentSession(...)` sites across main and test.

**Files:**
- Modify: `app/src/main/java/app/drydock/domain/ManagedAgentSession.java`
- Modify: `app/src/main/java/app/drydock/state/ApplicationStateCodec.java` (encode ~`:188`, decode ~`:332`, header schema comment ~`:66`)
- Modify: every other `new ManagedAgentSession(` call site the compiler flags
- Test: `app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java` (or the existing codec test class)

**Interfaces:**
- Produces: `ManagedAgentSession.namePinned()` and `withNamePinned(boolean)`, used by Tasks 7 and 9.

- [ ] **Step 1: Write the failing codec tests**

```java
@Test
void namePinnedSurvivesARoundTrip() {
    ManagedAgentSession pinned = sampleSession().withNamePinned(true);
    ApplicationState state = ApplicationState.empty().withSessions(List.of(pinned));

    ApplicationState decoded = ApplicationStateCodec.decode(ApplicationStateCodec.encode(state));

    assertTrue(decoded.sessions().getFirst().namePinned());
}

@Test
void aSessionWrittenBeforeThisMemberDecodesUnpinned() {
    // No "namePinned" member at all, as every state file written before this change.
    String json = """
            {"version":2,"repositories":[],"sessions":[{
              "id":"%s","repositoryId":"%s","agentKind":"CLAUDE","displayName":"Session 1",
              "workingDirectory":"%s","status":"INACTIVE",
              "createdAt":"2026-01-01T00:00:00Z","lastOpenedAt":"2026-01-01T00:00:00Z",
              "prState":"NONE","branchCreatedHere":true}]}
            """.formatted(sampleSessionId(), sampleRepositoryId(), sampleWorkingDirectory());

    ApplicationState decoded = ApplicationStateCodec.decode(JsonParser.parse(json));

    assertFalse(decoded.sessions().getFirst().namePinned());
}

@Test
void aMalformedNamePinnedDecodesUnpinned() {
    // Unlike branchCreatedHere, which decodes TRUE when absent or malformed
    // by deliberate choice, an unreadable pin means "nobody has claimed this
    // name" -- the safe reading, since the pin only ever removes ability.
    ManagedAgentSession decoded = decodeSessionWithRawMember("namePinned", "\"yes\"");

    assertFalse(decoded.namePinned());
}
```

Adapt the helper names (`sampleSession()`, `decodeSessionWithRawMember`) to whatever the existing codec test class already provides; write them if it has none.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.state.*"`
Expected: FAIL — `withNamePinned` / `namePinned()` do not exist.

- [ ] **Step 3: Add the component**

In `ManagedAgentSession`, add `boolean namePinned` as the **last** component, after `branchCreatedHere`. Document it on the record:

```java
 * <p>{@link #namePinned()} records that a human explicitly confirmed a
 * rename of this session. The {@code session_rename} MCP tool refuses once
 * it is set: a name the human typed is theirs. Set only by an explicit
 * confirm (Enter in the inline editor, OK in the Rename dialog) -- never by
 * a focus-loss commit, which an agent can provoke by opening a tab.</p>
```

Extend all ten `withX` methods to pass `namePinned` through, and add:

```java
    public ManagedAgentSession withNamePinned(boolean newNamePinned) {
        return new ManagedAgentSession(id, repositoryId, agentKind, displayName, agentSessionId, agentSessionName,
                workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber,
                branchCreatedHere, newNamePinned);
    }
```

`withDisplayName` must pass `namePinned` **through unchanged** — renaming is not pinning.

- [ ] **Step 4: Fix every call site the compiler flags**

Run: `./gradlew :app:compileJava :app:compileTestJava`

Every `new ManagedAgentSession(...)` now needs a final argument. Pass `false` everywhere except where a test is specifically about a pinned session. There are 26 sites: ten inside the record's own copy methods, the rest across main and test.

- [ ] **Step 5: Extend the codec**

Encode, beside `branchCreatedHere` (~`:188`):

```java
        obj.put("namePinned", new JsonBoolean(session.namePinned()));
```

Decode, beside it (~`:332`):

```java
            // Lenient and added within schema version 2, no bump -- like
            // branchCreatedHere, but defaulting the other way: a session
            // persisted before this member existed was never pinned, and a
            // malformed value must not silently lock a name.
            boolean namePinned = obj.get("namePinned") instanceof JsonBoolean pinned && pinned.value();
```

and pass it as the final constructor argument. Add `namePinned` to the header schema comment at ~`:66`, beside `"branchCreatedHere": <boolean>`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:test`
Expected: PASS (whole suite — this task touches test files broadly).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "A session remembers that its name was the human's choice"
```

---

### Task 5: `PromptSafety.checkSessionTitle`

The validation that keeps agent-authored text out of the tab rail, the sidebar, and five confirm dialogs. `checkInboundText` cannot be reused: it deliberately permits `\n`, `\r`, `\t` and caps at 8000.

**Files:**
- Modify: `app/src/main/java/app/drydock/mcp/PromptSafety.java`
- Test: `app/src/test/java/app/drydock/mcp/PromptSafetyTest.java`

**Interfaces:**
- Produces: `PromptSafety.checkSessionTitle(String) throws McpToolException` returning the **folded** title. Used by Task 10.

- [ ] **Step 1: Write the failing tests**

```java
    private static String check(String title) throws McpToolException {
        return PromptSafety.checkSessionTitle(title);
    }

    @Test
    void acceptsAnOrdinaryTitleAndReturnsItFolded() throws Exception {
        assertEquals("Fix the login flow", check("  Fix   the  login flow  "));
    }

    @Test
    void foldsNonBreakingSpacesSoAWhitespaceOnlyTitleIsBlank() {
        // U+00A0 is NOT Character.isWhitespace, so strip()/isBlank() miss it.
        assertThrows(McpToolException.class, () -> check("   "));
    }

    @Test
    void foldsNonBreakingSpacesInsideATitle() throws Exception {
        assertEquals("a b", check("a  b"));
    }

    @Test
    void refusesNewlinesEvenThoughCheckInboundTextAllowsThem() {
        assertThrows(McpToolException.class, () -> check("one\ntwo"));
    }

    @Test
    void refusesBidiOverridesAndZeroWidthCharacters() {
        assertThrows(McpToolException.class, () -> check("safe ‮ gnirts"));
        assertThrows(McpToolException.class, () -> check("a​b"));
    }

    @Test
    void refusesSupplementaryPlaneTagCharacters() {
        // U+E0021 is FORMAT but neither of its surrogates is; a char-based
        // loop passes it. This is the current invisible-text vector.
        assertThrows(McpToolException.class, () -> check("work 󠀡"));
    }

    @Test
    void refusesLineAndParagraphSeparators() {
        assertThrows(McpToolException.class, () -> check("a b"));
        assertThrows(McpToolException.class, () -> check("a b"));
    }

    @Test
    void refusesALoneSurrogate() {
        assertThrows(McpToolException.class, () -> check("a\uD800b"));
    }

    @Test
    void refusesTheDoubleQuoteThatWouldForgeAConfirmDialog() {
        assertThrows(McpToolException.class, () -> check("x\" - already merged, safe to remove"));
    }

    @Test
    void refusesThreeConsecutiveCombiningMarksButAllowsTwo() throws Exception {
        assertEquals("é̂", check("é̂"));
        assertThrows(McpToolException.class, () -> check("é̂̃"));
    }

    @Test
    void capsAtSixtyCodePointsMeasuredAfterFolding() throws Exception {
        assertEquals("a".repeat(60), check("a".repeat(60)));
        assertThrows(McpToolException.class, () -> check("a".repeat(61)));
        // 70 characters that fold to 55 must pass: folding precedes the cap.
        assertEquals(("ab ".repeat(18) + "x").strip(), check("ab  ".repeat(18) + "x"));
    }

    @Test
    void countsCodePointsNotCharsSoAstralTitlesAreNotCutShort() throws Exception {
        String cjk = "工".repeat(60);            // 60 code points, 60 chars
        String astral = "𠮷".repeat(60);   // 60 code points, 120 chars
        assertEquals(cjk, check(cjk));
        assertEquals(astral, check(astral));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.mcp.PromptSafetyTest"`
Expected: FAIL — `checkSessionTitle` does not exist.

- [ ] **Step 3: Implement**

Add to `PromptSafety`, and widen the class javadoc's scope sentence to say it also validates agent-authored text that **drydock itself renders**, not only text sent into a session:

```java
    /** Longest session title, in code points. Bounds what can enter a confirm dialog. */
    private static final int MAX_TITLE_CODE_POINTS = 60;

    /** Longest run of combining marks, so a title cannot grow vertically out of the tab rail. */
    private static final int MAX_CONSECUTIVE_MARKS = 2;

    /**
     * Validates a session title an agent wants to write to its own tab
     * ({@code session_rename}), and returns it folded.
     *
     * <p>Unlike {@link #checkInboundText}, which is written for finding
     * bodies and deliberately permits {@code \n}, {@code \r} and {@code \t}:
     * a title is one line, and it lands somewhere a finding body never does.
     * It is the label of the tab rail and the sidebar row, and it is
     * interpolated into five confirm dialogs -- including
     * "Delete session \"...\"?" and the Start-new-conversation /
     * Delete-session pair. Text that can reorder, hide, or re-punctuate
     * itself there can make a destructive confirmation read as a reassurance.
     *
     * <p>The scan iterates code points. A {@code char} loop would miss the
     * supplementary-plane tag block U+E0020-U+E007F -- FORMAT characters
     * whose surrogates are neither FORMAT nor controls, and the current
     * invisible-text-smuggling vector.
     *
     * @return {@code title} with every Unicode space folded to U+0020, runs
     *         collapsed and the result trimmed -- the value that gets stored
     *         and compared, never the raw argument
     */
    public static String checkSessionTitle(String title) throws McpToolException {
        Objects.requireNonNull(title, "title");

        int marksInARow = 0;
        for (int i = 0; i < title.length(); ) {
            int cp = title.codePointAt(i);
            i += Character.charCount(cp);

            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.PRIVATE_USE || type == Character.UNASSIGNED
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                throw new McpToolException("A session title must be one line of visible text; "
                        + "it may not contain control, invisible or direction-changing characters "
                        + "(found U+" + String.format("%04X", cp) + ").");
            }
            if (cp == '"') {
                throw new McpToolException("A session title may not contain a double quote: drydock "
                        + "shows it inside quotes in confirmation dialogs.");
            }
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK) {
                if (++marksInARow > MAX_CONSECUTIVE_MARKS) {
                    throw new McpToolException("A session title may not stack more than "
                            + MAX_CONSECUTIVE_MARKS + " combining marks on one character.");
                }
            } else {
                marksInARow = 0;
            }
        }

        String folded = fold(title);
        if (folded.isEmpty()) {
            throw new McpToolException("A session title must not be blank.");
        }
        int codePoints = folded.codePointCount(0, folded.length());
        if (codePoints > MAX_TITLE_CODE_POINTS) {
            throw new McpToolException("A session title may be at most " + MAX_TITLE_CODE_POINTS
                    + " characters; this one is " + codePoints + ".");
        }
        return folded;
    }

    /**
     * Every Unicode space separator to U+0020, runs collapsed, then trimmed.
     *
     * <p>Not {@code strip()} or {@code \s}: {@link Character#isWhitespace} is
     * false for U+00A0, U+2007 and U+202F, so a title made entirely of
     * non-breaking spaces would otherwise pass every check and render as a
     * blank tab and a blank "Delete session" dialog.</p>
     */
    private static String fold(String title) {
        StringBuilder folded = new StringBuilder(title.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < title.length(); ) {
            int cp = title.codePointAt(i);
            i += Character.charCount(cp);
            boolean isSpace = cp == ' ' || Character.isSpaceChar(cp);
            if (isSpace) {
                if (!lastWasSpace && folded.length() > 0) {
                    folded.append(' ');
                }
                lastWasSpace = true;
            } else {
                folded.appendCodePoint(cp);
                lastWasSpace = false;
            }
        }
        int end = folded.length();
        while (end > 0 && folded.charAt(end - 1) == ' ') {
            end--;
        }
        return folded.substring(0, end);
    }
```

Add `import java.util.Objects;` if absent.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.mcp.PromptSafetyTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/PromptSafety.java app/src/test/java/app/drydock/mcp/PromptSafetyTest.java
git commit -m "A session title is one line of text that cannot lie about itself"
```

---

### Task 6: A rename budget in the session registry

Every applied rename publishes the whole session model and rebuilds the sidebar subtree, from an HTTP handler thread at whatever rate the agent picks. The refused outcomes still cost an FX hop each, so they are charged too.

**Files:**
- Modify: `app/src/main/java/app/drydock/mcp/McpSessionRegistry.java`
- Test: `app/src/test/java/app/drydock/mcp/McpSessionRegistryTest.java`

**Interfaces:**
- Produces: `MAX_RENAMES_PER_SESSION`, `chargeRename`, `refundRename`. Used by Task 10.

- [ ] **Step 1: Write the failing test**

```java
@Test
void aSessionMayRenameItselfTwentyTimes() throws Exception {
    McpSessionRegistry registry = new McpSessionRegistry();
    ManagedSessionId session = ManagedSessionId.newId();

    for (int i = 0; i < McpSessionRegistry.MAX_RENAMES_PER_SESSION; i++) {
        registry.chargeRename(session);
    }

    McpBudgetExhaustedException refused =
            assertThrows(McpBudgetExhaustedException.class, () -> registry.chargeRename(session));
    assertTrue(refused.getMessage().contains("renamed itself"),
            "message reads as a creation limit: " + refused.getMessage());
}

@Test
void aRefundedRenameCanBeRetried() throws Exception {
    McpSessionRegistry registry = new McpSessionRegistry();
    ManagedSessionId session = ManagedSessionId.newId();
    for (int i = 0; i < McpSessionRegistry.MAX_RENAMES_PER_SESSION; i++) {
        registry.chargeRename(session);
    }

    registry.refundRename(session);

    assertDoesNotThrow(() -> registry.chargeRename(session));
}
```

Match `ManagedSessionId.newId()` to however the existing registry test mints ids.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpSessionRegistryTest"`
Expected: FAIL — `MAX_RENAMES_PER_SESSION` does not exist.

- [ ] **Step 3: Implement**

```java
    /**
     * Renames one session may apply. Not about disk: {@code
     * ApplicationStateStore} coalesces saves and the activity log is a ring.
     * It bounds FX-thread work -- every rename attempt costs a {@code
     * Platform.runLater} and a turn under the state lock, dispatched from an
     * unbounded virtual-thread executor, so a loop would freeze the UI.
     */
    public static final int MAX_RENAMES_PER_SESSION = 20;

    private final Map<ManagedSessionId, AtomicInteger> renamesApplied = new ConcurrentHashMap<>();

    /**
     * Charges one rename attempt. Refused attempts are charged too -- they
     * cost the same FX hop, and each one tells the agent what is wrong, so
     * twenty is far more than it needs to learn.
     */
    public void chargeRename(ManagedSessionId sessionId) throws McpBudgetExhaustedException {
        AtomicInteger counter = renamesApplied.computeIfAbsent(sessionId, id -> new AtomicInteger());
        if (counter.incrementAndGet() > MAX_RENAMES_PER_SESSION) {
            counter.decrementAndGet();
            throw new McpBudgetExhaustedException("This session has already renamed itself "
                    + MAX_RENAMES_PER_SESSION + " times.");
        }
    }

    /** Releases a charge whose call then failed outright. Never drops below zero. */
    public void refundRename(ManagedSessionId sessionId) {
        refund(renamesApplied, sessionId);
    }
```

Not the shared `charge` helper: its message is hardcoded to *"has already created its limit of N … Ask the human to continue in one of them"*, which is nonsense for renames.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpSessionRegistryTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/McpSessionRegistry.java app/src/test/java/app/drydock/mcp/McpSessionRegistryTest.java
git commit -m "A session cannot rename itself in a loop"
```

---

### Task 7: The rename outcome and the one state transform that decides it

The pin test, the unchanged test, the collision test and the write are a single `ApplicationStateStore` transform. Anything else is load-then-save, which AGENTS.md names as a documented data-loss bug.

**Files:**
- Modify: `app/src/main/java/app/drydock/mcp/McpSessionContext.java` (add the nested `RenameKind` and `RenameOutcome` only — the method arrives in Task 9)
- Modify: `app/src/main/java/app/drydock/app/SessionManager.java`
- Test: `app/src/test/java/app/drydock/app/SessionManagerTest.java`

**Interfaces:**
- Consumes: `ManagedAgentSession.namePinned()`, `withNamePinned` (Task 4).
- Produces: `McpSessionContext.RenameKind`, `McpSessionContext.RenameOutcome`, `SessionManager.applyAgentRename(ManagedSessionId, String)`, `SessionManager.renameSession(ManagedSessionId, String, boolean)`. Used by Tasks 8, 9, 10.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void anAgentRenameAppliesAndDoesNotPin() {
    SessionManager manager = newManager();
    ManagedAgentSession session = manager.createSession(repository, "Session 1");

    RenameOutcome outcome = manager.applyAgentRename(session.id(), "Fix the login flow");

    assertEquals(RenameKind.RENAMED, outcome.kind());
    assertEquals("Fix the login flow", outcome.currentName());
    assertFalse(manager.findSession(session.id()).orElseThrow().namePinned());
    // ...so a second agent rename also succeeds
    assertEquals(RenameKind.RENAMED, manager.applyAgentRename(session.id(), "Fix the logout flow").kind());
}

@Test
void aHumanRenameWithPinBlocksLaterAgentRenames() {
    SessionManager manager = newManager();
    ManagedAgentSession session = manager.createSession(repository, "Session 1");
    manager.renameSession(session.id(), "Mine", true);

    RenameOutcome outcome = manager.applyAgentRename(session.id(), "Fix the login flow");

    assertEquals(RenameKind.PINNED, outcome.kind());
    assertEquals("Mine", outcome.currentName());
    assertEquals("Mine", manager.findSession(session.id()).orElseThrow().displayName());
}

@Test
void aHumanRenameWithoutPinLeavesTheAgentFree() {
    SessionManager manager = newManager();
    ManagedAgentSession session = manager.createSession(repository, "Session 1");
    manager.renameSession(session.id(), "Blur wrote this", false);

    assertEquals(RenameKind.RENAMED, manager.applyAgentRename(session.id(), "Fix the login flow").kind());
}

@Test
void renamingToTheCurrentNameIsUnchanged() {
    SessionManager manager = newManager();
    ManagedAgentSession session = manager.createSession(repository, "Session 1");
    manager.applyAgentRename(session.id(), "Fix the login flow");

    assertEquals(RenameKind.UNCHANGED, manager.applyAgentRename(session.id(), "Fix the login flow").kind());
}

@Test
void pinBeatsUnchangedWhenBothApply() {
    SessionManager manager = newManager();
    ManagedAgentSession session = manager.createSession(repository, "Session 1");
    manager.renameSession(session.id(), "Mine", true);

    // Asking for the name it already has, on a pinned session: PINNED wins.
    assertEquals(RenameKind.PINNED, manager.applyAgentRename(session.id(), "Mine").kind());
}

@Test
void aTitleAnotherSessionInTheSameRepositoryHoldsCollides() {
    SessionManager manager = newManager();
    ManagedAgentSession first = manager.createSession(repository, "Session 1");
    ManagedAgentSession second = manager.createSession(repository, "Session 2");
    manager.applyAgentRename(first.id(), "Fix the login flow");

    RenameOutcome outcome = manager.applyAgentRename(second.id(), "fix the LOGIN flow");

    assertEquals(RenameKind.COLLIDED, outcome.kind());
    assertEquals("Fix the login flow", outcome.currentName());
    assertEquals("Session 2", manager.findSession(second.id()).orElseThrow().displayName());
}

@Test
void theSameTitleInAnotherRepositoryIsFine() {
    SessionManager manager = newManager();
    ManagedAgentSession here = manager.createSession(repository, "Session 1");
    ManagedAgentSession there = manager.createSession(otherRepository, "Session 1");
    manager.applyAgentRename(here.id(), "Fix the login flow");

    assertEquals(RenameKind.RENAMED, manager.applyAgentRename(there.id(), "Fix the login flow").kind());
}

@Test
void collisionComparesAgainstFoldedStoredNames() {
    // The human path applies no checkSessionTitle, so a stored name can carry
    // a non-breaking space. Folding only the incoming side would let an agent
    // clone it exactly as rendered.
    SessionManager manager = newManager();
    ManagedAgentSession first = manager.createSession(repository, "Session 1");
    ManagedAgentSession second = manager.createSession(repository, "Session 2");
    manager.renameSession(first.id(), "Fix login", false);

    assertEquals(RenameKind.COLLIDED, manager.applyAgentRename(second.id(), "Fix login").kind());
}

@Test
void anExitedSessionStillHoldsItsName() {
    SessionManager manager = newManager();
    ManagedAgentSession first = manager.createSession(repository, "Session 1");
    ManagedAgentSession second = manager.createSession(repository, "Session 2");
    manager.applyAgentRename(first.id(), "Fix the login flow");
    manager.markSessionExited(first.id(), 0);

    assertEquals(RenameKind.COLLIDED, manager.applyAgentRename(second.id(), "Fix the login flow").kind());
}
```

Adapt `newManager()`, `createSession`, `repository`, `otherRepository` and `markSessionExited` to the existing `SessionManagerTest` fixtures. Also update the existing call to `renameSession(id, name)` at `SessionManagerTest.java:271` to the three-argument form.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.app.SessionManagerTest"`
Expected: FAIL — `applyAgentRename` does not exist.

- [ ] **Step 3: Declare the outcome types**

In `McpSessionContext`, beside the existing `SessionSummary` record:

```java
    /** How a {@code session_rename} call ended. */
    enum RenameKind {
        /** The name changed and the workspace republished. */
        RENAMED,
        /** The validated title was already this session's name; nothing was written. */
        UNCHANGED,
        /** A human explicitly named this session, so drydock will not rename it. */
        PINNED,
        /** Another session in the same repository already answers to that name. */
        COLLIDED
    }

    /**
     * The result of a rename attempt.
     *
     * <p>{@code currentName} is the name in force after the call -- except on
     * {@link RenameKind#COLLIDED}, where it is the colliding sibling's name,
     * which is what the refusal has to quote.</p>
     */
    record RenameOutcome(RenameKind kind, String currentName) {
    }
```

- [ ] **Step 4: Implement the transform**

In `SessionManager`, replace `renameSession` and add `applyAgentRename`:

```java
    /**
     * The human's rename. {@code pin} marks the name as theirs, which refuses
     * every later {@link #applyAgentRename}.
     *
     * <p>Pinning is a decision of the caller, not of renaming: {@code pin} is
     * true for an explicit confirm (Enter in the inline editor, OK in the
     * Rename dialog) and false for a focus-loss commit, which an agent can
     * provoke by opening a tab and stealing focus.</p>
     */
    public ManagedAgentSession renameSession(ManagedSessionId sessionId, String newDisplayName, boolean pin) {
        return updateSession(sessionId, session -> pin
                ? session.withDisplayName(newDisplayName).withNamePinned(true)
                : session.withDisplayName(newDisplayName));
    }

    /**
     * The {@code session_rename} MCP tool's write.
     *
     * <p>One transform, deliberately: the pin test, the unchanged test, the
     * collision test and the write all read the same state under {@link
     * ApplicationStateStore}'s single lock. Reading first and writing after
     * would let a human rename land in between and be silently overwritten --
     * the load-then-save shape AGENTS.md names as a data-loss bug.</p>
     *
     * <p>{@code title} must already have been through {@link
     * PromptSafety#checkSessionTitle}: this compares and stores it verbatim.</p>
     */
    public RenameOutcome applyAgentRename(ManagedSessionId sessionId, String title) {
        RenameOutcome[] result = new RenameOutcome[1];
        stateStore.update(state -> {
            ManagedAgentSession session = state.sessions().stream()
                    .filter(existing -> existing.id().equals(sessionId))
                    .findFirst()
                    .orElseThrow(() -> new UnknownSessionException(sessionId));

            if (session.namePinned()) {
                result[0] = new RenameOutcome(RenameKind.PINNED, session.displayName());
                return state;
            }
            if (session.displayName().equals(title)) {
                result[0] = new RenameOutcome(RenameKind.UNCHANGED, session.displayName());
                return state;
            }
            Optional<ManagedAgentSession> clash = state.sessions().stream()
                    .filter(other -> !other.id().equals(sessionId))
                    .filter(other -> other.repositoryId().equals(session.repositoryId()))
                    // Both sides folded: a stored name came through the human
                    // path, which applies no checkSessionTitle, so it can
                    // carry a non-breaking space that renders identically.
                    .filter(other -> PromptSafety.foldForComparison(other.displayName()).equalsIgnoreCase(title))
                    .findFirst();
            if (clash.isPresent()) {
                result[0] = new RenameOutcome(RenameKind.COLLIDED, clash.get().displayName());
                return state;
            }

            ManagedAgentSession renamed = session.withDisplayName(title);
            result[0] = new RenameOutcome(RenameKind.RENAMED, title);
            return withReplacedSession(state, renamed);
        });
        return result[0];
    }
```

Note the ordering: **PINNED, then UNCHANGED, then COLLIDED, then RENAMED**, so a pinned session asked for its own name answers `PINNED`.

`equalsIgnoreCase` rather than `toLowerCase()`: it matches the sidebar's own `String.CASE_INSENSITIVE_ORDER` sort, which is the adjacency the rule defends, and it is not locale-sensitive.

This needs a comparison-only fold exposed from `PromptSafety` (Task 5's `fold` is private). Add:

```java
    /** The folding half of {@link #checkSessionTitle}, for comparing against already-stored names. */
    public static String foldForComparison(String title) {
        return fold(title);
    }
```

Add imports to `SessionManager`: `app.drydock.mcp.McpSessionContext.RenameKind`, `app.drydock.mcp.McpSessionContext.RenameOutcome`, `app.drydock.mcp.PromptSafety`. `SessionManager` already imports from `app.drydock.mcp`.

- [ ] **Step 5: Fix the two existing `renameSession` callers**

`MainWorkspace.java:2305` — pass `true` for now; Task 8 threads the real value. `SessionManagerTest.java:271` — pass `true`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.app.SessionManagerTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "One transform decides whether an agent may rename its session"
```

---

### Task 8: Pin on an explicit confirm, never on blur

`renameField` commits on Enter **and** on focus loss, through one `Consumer<String>`. If blur pinned, an agent could pin a human's session at will: `session_start` opens and selects a tab, which takes focus from an open editor.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/OpenSessionTab.java` (`onRenamed` field and `setOnRenamed`, `:607-611`, `commitInlineRename` `:775-780`)
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` (`renameSession` `:2304`, `promptRenameSession` `:2319`, `setOnRenamed` wiring `:2684`)
- Test: none automated — the gesture-to-parameter mapping lives in a focus listener, and there is no `MainWorkspace`/`OpenSessionTab` test harness. Task 13 covers it manually. Task 7 already tests the `pin` parameter itself.

**Interfaces:**
- Consumes: `SessionManager.renameSession(id, name, pin)` (Task 7).
- Produces: `MainWorkspace.renameSession(ManagedSessionId, String, boolean)`, used by Task 12's callers and nothing else.

- [ ] **Step 1: Change the callback's shape**

In `OpenSessionTab`, change the field and setter from `Consumer<String>` to `BiConsumer<String, Boolean>`, and pass the gesture through:

```java
    private BiConsumer<String, Boolean> onRenamed = (name, pin) -> { };

    void setOnRenamed(BiConsumer<String, Boolean> onRenamed) {
        this.onRenamed = onRenamed;
    }
```

Add `import java.util.function.BiConsumer;`, and drop the `Consumer` import if it becomes unused.

- [ ] **Step 2: Split the two commit paths**

```java
        // Enter is an explicit confirm: it pins, even when the text is
        // unchanged -- a human who opened the editor, read the agent's title
        // and pressed Enter has claimed that name.
        renameField.setOnAction(e -> commitInlineRename(true));
        renameField.focusedProperty().addListener((obs, was, is) -> {
            if (!is && tabLabels.getChildren().contains(renameField)) {
                // Focus loss is not a confirm. An agent's session_start opens
                // and selects a tab, which blurs an open editor -- so pinning
                // here would let an agent pin a human's session at will.
                commitInlineRename(false);
            }
        });
```

- [ ] **Step 3: Rewrite the commit**

```java
    private void commitInlineRename(boolean pin) {
        String newName = renameField.getText() == null ? "" : renameField.getText().strip();
        cancelInlineRename();
        // Empty text cancels on both paths: MainWorkspace.renameSession has no
        // emptiness filter of its own, and the human path applies no
        // checkSessionTitle, so notifying here would blank the tab label
        // permanently -- and, on the pin path, pin the blank.
        if (!newName.isEmpty() && (pin || !newName.equals(displayName))) {
            onRenamed.accept(newName, pin);
        }
    }
```

- [ ] **Step 4: Thread `pin` through MainWorkspace**

```java
    public void renameSession(ManagedSessionId sessionId, String newDisplayName, boolean pin) {
        sessionManager.renameSession(sessionId, newDisplayName, pin);
        publishSessions();
    }
```

At `:2684`: `openTab.setOnRenamed((name, pin) -> renameSession(openTab.sessionId(), name, pin));`

In `promptRenameSession` (`:2319`), the dialog's OK is an explicit confirm:
`.ifPresent(name -> renameSession(session.id(), name, true));`

- [ ] **Step 5: Verify the suite still passes**

Run: `./gradlew :app:test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/OpenSessionTab.java app/src/main/java/app/drydock/ui/MainWorkspace.java
git commit -m "Pressing Enter on a session name claims it; clicking away does not"
```

---

### Task 9: The FX hop and the context seam

Only `publishSessions()` relabels a tab — `SessionManager` has no listeners. The state write is thread-safe off the FX thread; the publish is not.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` (new constant near `:153`, new method near `startAgentSession` `:1818`)
- Modify: `app/src/main/java/app/drydock/mcp/WorkspaceMcpSessionContext.java` (new constant near `:87`, constructor parameter near `:102`, new method near `startSession` `:525`)
- Modify: `app/src/main/java/app/drydock/mcp/McpSessionContext.java` (the `renameSession` method)
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java:1228` (construction site)
- Modify: `app/src/test/java/app/drydock/mcp/FakeMcpSessionContext.java`
- Test: `app/src/test/java/app/drydock/mcp/WorkspaceMcpSessionContextTest.java:337` (construction site)

**Interfaces:**
- Consumes: `SessionManager.applyAgentRename` (Task 7), `RenameOutcome` (Task 7).
- Produces: `McpSessionContext.renameSession(caller, title)`, used by Task 10. `FakeMcpSessionContext.setRenameOutcome(RenameOutcome)`, used by Task 10's tests.

- [ ] **Step 1: Declare the seam**

In `McpSessionContext`:

```java
    /**
     * Renames the caller's own session to an already-validated title.
     *
     * <p>Refusal is an outcome, not an exception: {@link
     * RenameKind#PINNED} and {@link RenameKind#COLLIDED} are legal answers
     * to a legal call, and the router owns the wording. Only a timeout or a
     * session that vanished mid-call throws.</p>
     */
    RenameOutcome renameSession(ManagedSessionId caller, String title) throws McpToolException;
```

- [ ] **Step 2: Implement the FX hop**

In `MainWorkspace`, beside `AGENT_SESSION_BUDGET_SECONDS`:

```java
    /**
     * Whole budget for an agent-driven rename, provably SMALLER than {@link
     * WorkspaceMcpSessionContext#RENAME_TIMEOUT_SECONDS} for the same reason
     * the session budget is smaller than its own: if the context's join could
     * expire first, the router would refund the charge while the rename went
     * on to land, and the budget would stop bounding anything.
     */
    private static final long AGENT_RENAME_BUDGET_SECONDS =
            WorkspaceMcpSessionContext.RENAME_TIMEOUT_SECONDS / 2;
```

and, beside `startAgentSession`:

```java
    /**
     * Applies an agent's {@code session_rename} and republishes, so the tab
     * and the sidebar actually relabel.
     *
     * <p>The publish is the whole reason this goes through the workspace at
     * all: {@link SessionManager} has no listeners, so a rename that stopped
     * at the state store would change the file and nothing on screen.</p>
     */
    public CompletableFuture<RenameOutcome> renameSessionFromAgent(ManagedSessionId id, String title) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(AGENT_RENAME_BUDGET_SECONDS);
        CompletableFuture<RenameOutcome> renamed = new CompletableFuture<>();
        Platform.runLater(() -> {
            // Re-checked ON the FX thread: a runLater queued behind a busy FX
            // thread must refuse rather than apply a rename whose caller has
            // already given up and had its budget refunded.
            if (expired(deadlineNanos)) {
                renamed.completeExceptionally(new IllegalStateException(
                        "Drydock was too busy to rename the session in time."));
                return;
            }
            try {
                RenameOutcome outcome = sessionManager.applyAgentRename(id, title);
                // Only a real change is worth a full republish; a refused
                // attempt must not buy the agent a sidebar rebuild.
                if (outcome.kind() == RenameKind.RENAMED) {
                    publishSessions();
                }
                renamed.complete(outcome);
            } catch (RuntimeException e) {
                // UnknownSessionException is reachable here: the session can
                // vanish between the router's liveness check and this hop.
                // Without this arm the future never completes and the HTTP
                // handler blocks for the whole join.
                renamed.completeExceptionally(e);
            }
        });
        return renamed;
    }
```

- [ ] **Step 3: Wire the context**

In `WorkspaceMcpSessionContext`, beside `START_SESSION_TIMEOUT_SECONDS`:

```java
    /**
     * Bound on {@link #renameSession}: one FX-thread hop and one state
     * transform. Public for the same reason {@link
     * #START_SESSION_TIMEOUT_SECONDS} is -- the workspace derives its own,
     * smaller budget from it rather than restating a number. Deliberately not
     * 20, so it is never confused with {@code JOIN_TIMEOUT_SECONDS}.
     */
    public static final long RENAME_TIMEOUT_SECONDS = 25;
```

Add the constructor parameter after `sessionStarter`:

```java
    private final BiFunction<ManagedSessionId, String, CompletableFuture<RenameOutcome>> sessionRenamer;
```

with the javadoc line `@param sessionRenamer bound to {@code MainWorkspace.renameSessionFromAgent}` and the usual `Objects.requireNonNull`. Then:

```java
    @Override
    public RenameOutcome renameSession(ManagedSessionId caller, String title) throws McpToolException {
        return join(sessionRenamer.apply(caller, title), RENAME_TIMEOUT_SECONDS);
    }
```

The class holds no JavaFX type and no UI import by design; this preserves that — the FX hop is on the far side of the `BiFunction`.

- [ ] **Step 4: Update both construction sites and the fake**

`DrydockApplication.java:1228`: add `mainWorkspace::renameSessionFromAgent` as the new argument.

`WorkspaceMcpSessionContextTest.java:337`: pass a stub mirroring the `sessionStarter` fake, e.g.
`(id, title) -> CompletableFuture.completedFuture(new RenameOutcome(RenameKind.RENAMED, title))`.

`FakeMcpSessionContext`:

```java
    private RenameOutcome renameOutcome = new RenameOutcome(RenameKind.RENAMED, "renamed");
    private final List<String> renameCalls = new ArrayList<>();

    /** Canned answer for the next rename -- not a pin flag: the pin lives in SessionManager's transform. */
    void setRenameOutcome(RenameOutcome renameOutcome) {
        this.renameOutcome = renameOutcome;
    }

    List<String> renameCalls() {
        return renameCalls;
    }

    @Override
    public RenameOutcome renameSession(ManagedSessionId caller, String title) {
        renameCalls.add(title);
        return renameOutcome;
    }
```

- [ ] **Step 5: Run the suite**

Run: `./gradlew :app:test`
Expected: PASS — nothing calls the new seam yet, so this only proves the wiring compiles and nothing regressed.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "An agent's rename reaches the tab it is about"
```

---

### Task 10: The `session_rename` tool

**Files:**
- Modify: `app/src/main/java/app/drydock/mcp/McpToolRouter.java` (descriptor list ~`:151`, dispatch switch ~`:169`, new handler)
- Test: `app/src/test/java/app/drydock/mcp/McpToolRouterSessionRenameTest.java` (create)

**Interfaces:**
- Consumes: `PromptSafety.checkSessionTitle` (5), `chargeRename`/`refundRename` (6), `RenameOutcome`/`RenameKind` (7), `McpSessionContext.renameSession` (9), `FakeMcpSessionContext.setRenameOutcome` (9).
- Produces: the `session_rename` tool.

- [ ] **Step 1: Write the failing tests**

Create `McpToolRouterSessionRenameTest`, modelled on `McpToolRouterSessionStartTest`'s fixture:

```java
class McpToolRouterSessionRenameTest {

    private FakeMcpSessionContext context;
    private McpSessionRegistry registry;
    private McpToolRouter router;
    private ManagedSessionId caller;

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        registry = new McpSessionRegistry();
        router = new McpToolRouter(context, registry);
        caller = ManagedSessionId.newId();
        registry.mint(caller, McpSessionRegistry.Spawn.ALLOWED);
        context.setSessionRunning(true);
    }

    private JsonValue rename(String title) throws McpToolException {
        return router.call(caller, "session_rename",
                JsonObject.empty().put("title", new JsonString(title)));
    }

    @Test
    void renamesTheCallersOwnSession() throws Exception {
        context.setRenameOutcome(new RenameOutcome(RenameKind.RENAMED, "Fix the login flow"));

        JsonValue result = rename("  Fix   the login flow ");

        assertEquals("Fix the login flow", JsonPeek.string(result, "title"));
        // The folded title crosses the seam, never the raw argument.
        assertEquals(List.of("Fix the login flow"), context.renameCalls());
    }

    @Test
    void isDeclaredInToolsListWithTitleRequired() {
        JsonValue tool = router.toolDescriptors().stream()
                .filter(d -> "session_rename".equals(JsonPeek.string(d, "name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("session_rename is not advertised"));

        assertTrue(JsonPeek.requiredNames(tool).contains("title"));
    }

    @Test
    void refusesAMissingTitle() {
        assertThrows(McpToolException.class, () ->
                router.call(caller, "session_rename", JsonObject.empty()));
    }

    @Test
    void refusesATitleOfOnlyNonBreakingSpaces() {
        // requiredStringArg's isBlank() is false for NBSP: the fold is what catches this.
        assertThrows(McpToolException.class, () -> rename("  "));
    }

    @Test
    void refusesInvisibleAndControlCharacters() {
        assertThrows(McpToolException.class, () -> rename("one\ntwo"));
        assertThrows(McpToolException.class, () -> rename("safe ‮ gnirts"));
        assertThrows(McpToolException.class, () -> rename("work 󠀡"));
        assertThrows(McpToolException.class, () -> rename("a b"));
        assertThrows(McpToolException.class, () -> rename("a\uD800b"));
        assertThrows(McpToolException.class, () -> rename("x\" safe to remove"));
    }

    @Test
    void refusesATitleOverSixtyCodePoints() {
        assertThrows(McpToolException.class, () -> rename("a".repeat(61)));
    }

    @Test
    void refusesADeadSession() {
        context.setSessionRunning(false);
        assertThrows(McpToolException.class, () -> rename("Fix the login flow"));
    }

    @Test
    void refusesAPinnedSessionAndNamesTheHumansTitle() {
        context.setRenameOutcome(new RenameOutcome(RenameKind.PINNED, "Mine"));

        McpToolException refused = assertThrows(McpToolException.class, () -> rename("Fix the login flow"));

        assertTrue(refused.getMessage().contains("Mine"), refused.getMessage());
        assertTrue(refused.getMessage().contains("human"), refused.getMessage());
    }

    @Test
    void refusesACollisionAndSaysWhatToDo() {
        context.setRenameOutcome(new RenameOutcome(RenameKind.COLLIDED, "Fix the login flow"));

        McpToolException refused = assertThrows(McpToolException.class, () -> rename("Fix the login flow"));

        assertTrue(refused.getMessage().contains("already called"), refused.getMessage());
    }

    @Test
    void reportsAnUnchangedTitleWithoutFailing() throws Exception {
        context.setRenameOutcome(new RenameOutcome(RenameKind.UNCHANGED, "Fix the login flow"));

        JsonValue result = rename("Fix the login flow");

        assertEquals("unchanged", JsonPeek.string(result, "outcome"));
    }

    @Test
    void chargesEveryOutcomeIncludingRefusals() throws Exception {
        context.setRenameOutcome(new RenameOutcome(RenameKind.PINNED, "Mine"));
        for (int i = 0; i < McpSessionRegistry.MAX_RENAMES_PER_SESSION; i++) {
            assertThrows(McpToolException.class, () -> rename("Fix the login flow"));
        }

        McpToolException exhausted = assertThrows(McpToolException.class, () -> rename("Anything"));

        assertTrue(exhausted.getMessage().contains("renamed itself"), exhausted.getMessage());
    }

    @Test
    void refundsWhenTheCallItselfFails() throws Exception {
        context.setRenameFailure(new McpToolException("Drydock was too busy."));
        for (int i = 0; i < McpSessionRegistry.MAX_RENAMES_PER_SESSION + 3; i++) {
            assertThrows(McpToolException.class, () -> rename("Fix the login flow"));
        }
        // Every one refunded, so the budget is untouched.
        context.setRenameFailure(null);
        context.setRenameOutcome(new RenameOutcome(RenameKind.RENAMED, "Fix the login flow"));
        assertDoesNotThrow(() -> rename("Fix the login flow"));
    }
}
```

Add to `FakeMcpSessionContext` the failure hook the last test needs:

```java
    private McpToolException renameFailure;

    void setRenameFailure(McpToolException renameFailure) {
        this.renameFailure = renameFailure;
    }
```
and throw it at the top of `renameSession` when non-null.

Add `JsonPeek.requiredNames(JsonValue tool)` if `JsonPeek` has no equivalent — it reads `inputSchema.required` into a `List<String>`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpToolRouterSessionRenameTest"`
Expected: FAIL — `Unknown tool: session_rename`.

- [ ] **Step 3: Declare the tool**

In `toolDescriptors()`, after the `session_start` descriptor:

```java
                descriptor("session_rename",
                        "Renames this session's own tab, which the human is watching. Call it as soon as "
                                + "you know what the work actually is -- a short title naming the work, not "
                                + "the branch -- and again if the work turns out to be something else. "
                                + "Refused if the human named this session, or if another session in this "
                                + "repository already has that title.",
                        JsonObject.empty()
                                .put("title", schemaString("Short title naming the work; at most 60 "
                                        + "characters, one line.")),
                        "title"),
```

- [ ] **Step 4: Dispatch and handle**

Add `case "session_rename" -> sessionRename(caller, arguments);` to the switch, and:

```java
    // ---- session_rename -----------------------------------------------------

    private JsonValue sessionRename(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        // Validate before charging: a malformed title is the agent's mistake
        // to fix, not a spend.
        String title = PromptSafety.checkSessionTitle(requiredStringArg(args, "title"));

        try {
            registry.chargeRename(caller);
        } catch (McpBudgetExhaustedException e) {
            throw new McpToolException(e.getMessage());
        }

        RenameOutcome outcome;
        try {
            outcome = context.renameSession(caller, title);
        } catch (McpToolException e) {
            // Only an outright failure is refunded. The refused OUTCOMES are
            // charged: each one still costs an FX hop and a turn under the
            // state lock, and each one says what is wrong, so twenty attempts
            // is far more than an agent needs to stop.
            registry.refundRename(caller);
            throw e;
        }

        return switch (outcome.kind()) {
            case RENAMED -> renameResult("renamed", outcome.currentName());
            case UNCHANGED -> renameResult("unchanged", outcome.currentName());
            case PINNED -> throw new McpToolException("This session was named by the human ('"
                    + outcome.currentName() + "'); drydock will not rename it.");
            case COLLIDED -> throw new McpToolException("Another session in this repository is already "
                    + "called '" + outcome.currentName() + "'. Choose a title that tells the two apart.");
        };
    }

    private static JsonValue renameResult(String outcome, String title) {
        return JsonObject.empty()
                .put("outcome", new JsonString(outcome))
                .put("title", new JsonString(title));
    }
```

Import `app.drydock.mcp.McpSessionContext.RenameOutcome` — same package, so no import needed for the nested types beyond `McpSessionContext.RenameOutcome` / `.RenameKind` references.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpToolRouterSessionRenameTest"`
Expected: PASS. Then `./gradlew :app:test` for the whole suite.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "session_rename: a session can say what it turned out to be doing"
```

---

### Task 11: Tell agents the tool exists

An agent reads a tool description only when it is already looking for a tool. The MCP `instructions` field — absent from `initializeResult` today — is what the client injects into the system prompt, and is why no drydock tool is ever called proactively.

**Files:**
- Modify: `app/src/main/java/app/drydock/mcp/McpServer.java` (`initializeResult`, `:336`)
- Test: `app/src/test/java/app/drydock/mcp/McpServerTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void initializeCarriesInstructionsThatNameTheRenameTool() {
    JsonValue result = initializeResultFor("2025-06-18");

    String instructions = JsonPeek.string(result, "instructions");
    assertNotNull(instructions);
    assertFalse(instructions.isBlank());
    assertTrue(instructions.contains("session_rename"), instructions);
}
```

Reuse whatever helper the existing `McpServerTest` uses to drive an `initialize` request.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpServerTest"`
Expected: FAIL — no `instructions` member.

- [ ] **Step 3: Implement**

```java
        /**
         * Injected into the hosted agent's system prompt by the client. This
         * is the only thing that makes a tool get called without the agent
         * already hunting for one -- a tool description is read at selection
         * time, not at the moment the agent learns what its work is.
         */
        private static final String INSTRUCTIONS = """
                Drydock hosts this session in a tab a human is watching. As soon as you know what \
                the work actually is, call session_rename with a short title naming the work -- not \
                the branch. Re-title it if the work turns out to be something else. Two refusals are \
                normal and both explain themselves: if the human has named the session, leave it \
                alone; if another session here already has that title, pick one that tells them apart.\
                """;

        private JsonValue initializeResult(JsonValue params) {
            return JsonObject.empty()
                    .put("protocolVersion", new JsonString(negotiatedProtocolVersion(params)))
                    .put("capabilities", JsonObject.empty().put("tools", JsonObject.empty()))
                    .put("serverInfo", JsonObject.empty()
                            .put("name", new JsonString("drydock"))
                            .put("version", new JsonString("1.0.0")))
                    .put("instructions", new JsonString(INSTRUCTIONS));
        }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpServerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/McpServer.java app/src/test/java/app/drydock/mcp/McpServerTest.java
git commit -m "Drydock tells its agents what it can do for them"
```

---

### Task 12: Stop keying two destructive dialogs on the name alone

Rule 7 blocks exact clones, but not homoglyphs or near-misses. The sidebar sorts by name, so an impostor row lands adjacent to its target — and the delete confirmation names only the session.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java:1241`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java:2098`
- Test: none automated (dialog text; no UI harness). Task 13 covers it.

- [ ] **Step 1: Add the path to the delete confirmation**

At `RepositorySidebar.java:1241`, keep the header and extend the content text so it names the directory:

```java
        // The name is agent-authored and can be a near-miss of a sibling's,
        // and the sidebar sorts by name so the impostor lands adjacent. The
        // working directory is what actually tells two sessions apart.
        confirm.setContentText(confirm.getContentText() + "\n\nWorking directory: "
                + session.workingDirectory());
```

Follow the existing precedent at `MainWorkspace.java:2159`, which already prints `"Expected directory: " + session.workingDirectory()`. If the dialog sets no content text today, set one rather than appending to null.

- [ ] **Step 2: Add it to the Start-new-conversation / Delete-session prompt**

Same change at `MainWorkspace.java:2098` — that dialog's buttons are **Start new conversation** and **Delete session**, so it needs the disambiguation more than any other.

- [ ] **Step 3: Verify the suite still passes**

Run: `./gradlew :app:test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java app/src/main/java/app/drydock/ui/MainWorkspace.java
git commit -m "A delete confirmation says which session, not just which name"
```

---

### Task 13: Manual checklist entries

Four behaviours carry the feature and none of them can be asserted: whether the client injects `instructions` at all, whether the tab relabels, whether the labels ellipsize, and whether Enter pins where blur does not.

**Files:**
- Modify: `docs/manual-terminal-checklist.md`

- [ ] **Step 1: Add the entries**

Append, matching the file's existing format:

```markdown
## session_rename

- [ ] Start a session and give it work. Within its first few turns the agent
      renames its own tab, unprompted. (Nothing else proves the client injects
      the MCP `instructions` field — every unit test passes with the feature
      inert.)
- [ ] When it does, the tab label AND the sidebar row both change, and the
      sidebar row re-sorts into its new alphabetical position.
- [ ] Rename a session by hand to 60 full-width CJK characters. The sidebar row
      and the session header both ellipsize, and the window still narrows to its
      previous minimum width. (`setMinWidth(0)` alone would satisfy the width
      check while the clamp silently did nothing — look for the ellipsis.)
- [ ] Double-click a tab to open the inline rename, then click elsewhere without
      pressing Enter. The agent can still rename that session afterwards.
- [ ] Double-click a tab, press Enter without changing the text. The agent can no
      longer rename it — its next attempt is refused, naming your title.
- [ ] With two sessions open on one repository, ask one agent to take the other's
      exact title. It is refused and told to pick something that tells them apart.
```

- [ ] **Step 2: Walk the checklist**

Actually run every entry against a built app. Record any that fail — a failing entry is a bug in Tasks 1–12, not a checklist problem.

- [ ] **Step 3: Commit**

```bash
git add docs/manual-terminal-checklist.md
git commit -m "Manual checks for what no test can see"
```

---

## Self-Review

**Spec coverage.** Every section maps to a task: validation → 5, rule 7 → 7, dialog disambiguation → 12, label sizing → 3, summarize → 1, activity log → 2, outcomes/budget → 6+7+10, pin → 4+7+8, FX path/seam → 9, instructions → 11, tests → each task, manual checklist → 13. The spec's four "deliberately not automated" items are stated in the tasks that would otherwise appear to cover them (3, 8, 9's step 5, 13).

**Type consistency.** `RenameOutcome`/`RenameKind` are declared once (Task 7, on `McpSessionContext`) and used with the same names in 9 and 10. `applyAgentRename` (manager) and `renameSessionFromAgent` (workspace) stay distinct throughout. `checkSessionTitle` returns `String` in Task 5 and its return value is what Task 10 passes across the seam and what Task 7 compares.

**Known soft spots for the implementer** — flagged rather than hidden:
- Task 1's `switch` over `Character.getType`'s `int` may need an `if` chain depending on compiler strictness.
- Task 7's `PromptSafety.foldForComparison` widens Task 5's API; it exists only so the collision check folds both sides.
- Test fixture names (`newManager()`, `sampleSession()`, `JsonPeek.requiredNames`) are written to match the existing test classes' conventions; adapt to what is actually there rather than adding parallel helpers.
