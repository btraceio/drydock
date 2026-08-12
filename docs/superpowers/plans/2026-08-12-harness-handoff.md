# Harness Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a drydock session be forked to a sibling session running a
different agent CLI, carrying a living handoff brief the outgoing agent
maintains over MCP.

**Architecture:** The outgoing agent keeps a `HandoffBrief` current via a new
`session_handoff` MCP tool; drydock stores it in `ApplicationState`. A fork
creates a branch at the outgoing session's `HEAD`, mints a worktree,
transplants the dirty tree, and starts a session with the chosen `AgentKind`
seeded with the brief plus facts drydock derives fresh. Nothing is ever
switched in place and the outgoing session is never written to.

**Tech Stack:** Java 21+ records and `Optional`, JavaFX for UI, the project's
hand-rolled `app.drydock.state.json` (no Jackson/Gson), JUnit 5, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-12-harness-handoff-design.md`

## Global Constraints

- **No new dependencies.** JSON goes through `app.drydock.state.json`; there
  is no mocking library, so seams are hand-written fakes (see
  `app/src/test/java/app/drydock/mcp/FakeMcpSessionContext.java`).
- **Persisted names are wire contracts.** Never rename an existing persisted
  JSON member or an `AgentKind.persistedName()`.
- **All new persisted members decode leniently.** Absent or malformed decodes
  to a stated default; never throw, never bump `schemaVersion`. This matches
  every member added since version 2 (`prState`, `branchCreatedHere`,
  `agentKind`, `namePinned`).
- **Blocking work never touches the FX thread.** Git and filesystem calls go
  on a background executor; `GitStatusService` exposes
  `CompletableFuture` publics with package-private `*Blocking` forms for
  tests. Follow that pattern exactly.
- **Refuse, never truncate.** Oversize agent input comes back with a message
  naming the slot and the limit.
- **Caps:** each brief slot 2,000 characters; the whole record 8,000.
- **Budget:** `MAX_HANDOFFS_PER_SESSION = 40` (twice
  `McpSessionRegistry.MAX_RENAMES_PER_SESSION`, because a brief is written
  repeatedly by design and a rename is written once or twice).
- **Test command:** `./gradlew :app:test --tests "<pattern>"` for a subset,
  `./gradlew :app:test` for the suite. The full suite takes 14–20 minutes —
  run subsets while working and the full suite once at the end.
- **Commit style:** sentence-case subject describing the change, as in
  `git log`. No `Co-Authored-By` unless the repo's recent commits carry one.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/app/drydock/domain/HandoffBrief.java` | **Create.** The brief record and its slot accessors. |
| `app/src/main/java/app/drydock/domain/ApplicationState.java` | **Modify.** Add `handoffBriefs` list + `withHandoffBriefs`. |
| `app/src/main/java/app/drydock/domain/ManagedAgentSession.java` | **Modify.** Add `forkedFrom` field + `withForkedFrom`. |
| `app/src/main/java/app/drydock/state/ApplicationStateCodec.java` | **Modify.** Encode/decode both, leniently. |
| `app/src/main/java/app/drydock/mcp/PromptSafety.java` | **Modify.** Add `checkHandoffSlot`. |
| `app/src/main/java/app/drydock/mcp/McpSessionContext.java` | **Modify.** Add `writeHandoff`. |
| `app/src/main/java/app/drydock/mcp/McpToolRouter.java` | **Modify.** Descriptor + `sessionHandoff` case. |
| `app/src/main/java/app/drydock/mcp/McpServer.java` | **Modify.** `AGENT_WRITE_TOOLS` + `INSTRUCTIONS`. |
| `app/src/main/java/app/drydock/mcp/McpSessionRegistry.java` | **Modify.** `chargeHandoff` / `refundHandoff`. |
| `app/src/main/java/app/drydock/mcp/WorkspaceMcpSessionContext.java` | **Modify.** Wire `writeHandoff` to the workspace. |
| `app/src/main/java/app/drydock/handoff/HandoffStaleness.java` | **Create.** Value + the "should the banner show" rule. |
| `app/src/main/java/app/drydock/handoff/HandoffSeed.java` | **Create.** Composes the successor's seed prompt. |
| `app/src/main/java/app/drydock/handoff/ForkFacts.java` | **Create.** The facts drydock derives fresh at fork time. |
| `app/src/main/java/app/drydock/git/WorktreeTransplant.java` | **Create.** All-or-nothing dirty-tree copy. |
| `app/src/main/java/app/drydock/app/SessionForkService.java` | **Create.** Orchestrates the fork with rollback. |
| `app/src/main/java/app/drydock/ui/HandoffBanner.java` | **Create.** Staleness banner with Refresh/Edit/Fork. |
| `app/src/main/java/app/drydock/ui/HandoffEditDialog.java` | **Create.** One field per slot. |

A new `app.drydock.handoff` package keeps brief composition out of both
`domain` (which holds no logic) and `ui`. `WorktreeTransplant` lives in `git`
because it is git plumbing and belongs beside `WorktreeService`.

---

### Task 1: The brief record, lineage, and persistence

**Files:**
- Create: `app/src/main/java/app/drydock/domain/HandoffBrief.java`
- Modify: `app/src/main/java/app/drydock/domain/ApplicationState.java`
- Modify: `app/src/main/java/app/drydock/domain/ManagedAgentSession.java`
- Modify: `app/src/main/java/app/drydock/state/ApplicationStateCodec.java`
- Test: `app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `HandoffBrief` (record, fields below);
  `HandoffBrief.Author` enum `{AGENT, HUMAN}`;
  `ApplicationState.handoffBriefs() -> List<HandoffBrief>`;
  `ApplicationState.withHandoffBriefs(List<HandoffBrief>) -> ApplicationState`;
  `ManagedAgentSession.forkedFrom() -> Optional<ManagedSessionId>`;
  `ManagedAgentSession.withForkedFrom(Optional<ManagedSessionId>) -> ManagedAgentSession`.

> **Note on `author`:** the spec describes a human *Edit* verb and a seed that
> labels the brief as the previous session's testimony. Those two are
> inconsistent unless the record remembers who wrote it, so `author` is added
> here. It is the one field the spec implies without naming.

- [ ] **Step 1: Write the failing codec tests**

Add to `ApplicationStateCodecTest`:

```java
@Test
void roundTripsHandoffBriefs() {
    ManagedSessionId sessionId = ManagedSessionId.newId();
    HandoffBrief brief = new HandoffBrief(
            sessionId, "Ship the fork gesture", "Wire the banner",
            Optional.of("Fork, never switch in place"),
            Optional.of("Chose briefing over transcript translation"),
            Optional.of("Rejected an API proxy: buys nothing at this bar"),
            Optional.of("Human said stop rewriting the parser"),
            Instant.parse("2026-08-12T10:15:30Z"),
            Optional.of("abc1234"),
            HandoffBrief.Author.AGENT);

    ApplicationState state = ApplicationState.empty().withHandoffBriefs(List.of(brief));

    assertEquals(List.of(brief),
            ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state)).handoffBriefs());
}

@Test
void roundTripsAbsentOptionalSlotsAsAbsent() {
    HandoffBrief minimal = new HandoffBrief(
            ManagedSessionId.newId(), "Goal", "Next",
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Instant.parse("2026-08-12T10:15:30Z"), Optional.empty(),
            HandoffBrief.Author.HUMAN);

    ApplicationState decoded = ApplicationStateCodec.fromJson(
            ApplicationStateCodec.toJson(ApplicationState.empty().withHandoffBriefs(List.of(minimal))));

    HandoffBrief actual = decoded.handoffBriefs().get(0);
    assertEquals(Optional.empty(), actual.approach());
    assertEquals(Optional.empty(), actual.writtenAtCommit());
    assertEquals(HandoffBrief.Author.HUMAN, actual.author());
}

@Test
void stateWrittenBeforeHandoffBriefsExistedDecodesToNoBriefs() {
    // A schemaVersion-2 document with no "handoffBriefs" member at all.
    String legacy = """
            {"schemaVersion":2,"repositories":[],"sessions":[],"ui":{}}
            """;
    ApplicationState decoded = ApplicationStateCodec.fromJson(JsonParser.parse(legacy));

    assertEquals(List.of(), decoded.handoffBriefs());
}

@Test
void malformedHandoffBriefEntryIsDroppedNotThrown() {
    String malformed = """
            {"schemaVersion":2,"repositories":[],"sessions":[],"ui":{},
             "handoffBriefs":[{"sessionId":"not-a-uuid","goal":"g","nextStep":"n"}]}
            """;

    assertEquals(List.of(), ApplicationStateCodec.fromJson(JsonParser.parse(malformed)).handoffBriefs());
}

@Test
void roundTripsForkedFrom() {
    ManagedSessionId parent = ManagedSessionId.newId();
    ManagedAgentSession session = sampleSession().withForkedFrom(Optional.of(parent));

    ApplicationState decoded = ApplicationStateCodec.fromJson(
            ApplicationStateCodec.toJson(ApplicationState.empty().withSessions(List.of(session))));

    assertEquals(Optional.of(parent), decoded.sessions().get(0).forkedFrom());
}

@Test
void sessionWrittenBeforeForkedFromExistedDecodesToEmpty() {
    ApplicationState decoded = ApplicationStateCodec.fromJson(
            ApplicationStateCodec.toJson(ApplicationState.empty().withSessions(List.of(sampleSession()))));

    assertEquals(Optional.empty(), decoded.sessions().get(0).forkedFrom());
}
```

`sampleSession()` — reuse the existing helper in `ApplicationStateCodecTest`
if one exists; if not, add one building a `ManagedAgentSession` with absolute
normalized paths (the record's constructor rejects anything else).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.state.ApplicationStateCodecTest"`
Expected: FAIL — `HandoffBrief` does not exist; `withHandoffBriefs` and
`withForkedFrom` are undefined.

- [ ] **Step 3: Create `HandoffBrief`**

```java
package app.drydock.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What one session tells its successor. Maintained during the session via the
 * {@code session_handoff} MCP tool (or written by a human in the Edit dialog)
 * and replaced wholesale on every write -- an omitted optional slot clears it.
 *
 * <p>{@code writtenAtCommit} is the {@code HEAD} the brief was written
 * against, so staleness is expressed in work done since rather than in
 * elapsed time; it is empty for a session whose branch has no commits yet.</p>
 *
 * <p>{@code author} exists because the seed labels an agent-written brief as
 * the previous session's testimony, and that label is wrong for a brief the
 * human typed.</p>
 */
public record HandoffBrief(
        ManagedSessionId sessionId,
        String goal,
        String nextStep,
        Optional<String> approach,
        Optional<String> decisions,
        Optional<String> ruledOut,
        Optional<String> corrections,
        Instant writtenAt,
        Optional<String> writtenAtCommit,
        Author author
) {

    /** Who last wrote this brief. */
    public enum Author { AGENT, HUMAN }

    public HandoffBrief {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(nextStep, "nextStep");
        Objects.requireNonNull(approach, "approach");
        Objects.requireNonNull(decisions, "decisions");
        Objects.requireNonNull(ruledOut, "ruledOut");
        Objects.requireNonNull(corrections, "corrections");
        Objects.requireNonNull(writtenAt, "writtenAt");
        Objects.requireNonNull(writtenAtCommit, "writtenAtCommit");
        Objects.requireNonNull(author, "author");

        if (goal.isBlank()) {
            throw new IllegalArgumentException("HandoffBrief goal must not be blank");
        }
        if (nextStep.isBlank()) {
            throw new IllegalArgumentException("HandoffBrief nextStep must not be blank");
        }
    }
}
```

- [ ] **Step 4: Add `handoffBriefs` to `ApplicationState`**

Add the component `List<HandoffBrief> handoffBriefs` as the **last**
component, defensively copy it in the compact constructor alongside the
others, extend `empty()` with `List.of()`, add `withHandoffBriefs`, and
update the three existing `with*` methods to pass it through.

```java
public ApplicationState withHandoffBriefs(List<HandoffBrief> newHandoffBriefs) {
    return new ApplicationState(repositories, sessions, ui, newHandoffBriefs);
}
```

- [ ] **Step 5: Add `forkedFrom` to `ManagedAgentSession`**

Add `Optional<ManagedSessionId> forkedFrom` as the last component, add
`Objects.requireNonNull(forkedFrom, "forkedFrom")`, thread it through every
existing `with*` method, and add:

```java
public ManagedAgentSession withForkedFrom(Optional<ManagedSessionId> newForkedFrom) {
    return new ManagedAgentSession(id, repositoryId, agentKind, displayName, agentSessionId, agentSessionName,
            workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber,
            branchCreatedHere, namePinned, newForkedFrom);
}
```

Extend the class javadoc: `forkedFrom` names the session this one was forked
from; it is never mutated after creation and is the whole lineage model, so
chain depth is derived by walking links rather than stored.

- [ ] **Step 6: Encode and decode both in `ApplicationStateCodec`**

In `toJson`, write a `handoffBriefs` array and add `forkedFrom` to each
session object (omit the member when empty, matching how the codec already
writes `null`-able members):

```java
private static JsonValue handoffBriefToJson(HandoffBrief brief) {
    JsonObject object = JsonObject.empty()
            .put("sessionId", new JsonString(brief.sessionId().value().toString()))
            .put("goal", new JsonString(brief.goal()))
            .put("nextStep", new JsonString(brief.nextStep()))
            .put("writtenAt", new JsonString(brief.writtenAt().toString()))
            .put("author", new JsonString(brief.author().name()));
    object = putOptionalString(object, "approach", brief.approach());
    object = putOptionalString(object, "decisions", brief.decisions());
    object = putOptionalString(object, "ruledOut", brief.ruledOut());
    object = putOptionalString(object, "corrections", brief.corrections());
    object = putOptionalString(object, "writtenAtCommit", brief.writtenAtCommit());
    return object;
}

private static JsonObject putOptionalString(JsonObject object, String key, Optional<String> value) {
    return value.map(v -> object.put(key, (JsonValue) new JsonString(v))).orElse(object);
}
```

In `fromJson`, decode leniently — **an entry that cannot be decoded is
dropped, not thrown**, consistent with the repository's rule that malformed
persisted state recovers rather than fails:

```java
private static List<HandoffBrief> handoffBriefsFrom(JsonObject root) {
    if (!(root.get("handoffBriefs") instanceof JsonArray array)) {
        return List.of();   // absent member: state written before this feature
    }
    List<HandoffBrief> briefs = new ArrayList<>();
    for (JsonValue element : array.values()) {
        handoffBriefFrom(element).ifPresent(briefs::add);
    }
    return List.copyOf(briefs);
}

private static Optional<HandoffBrief> handoffBriefFrom(JsonValue element) {
    if (!(element instanceof JsonObject object)) {
        return Optional.empty();
    }
    try {
        return Optional.of(new HandoffBrief(
                new ManagedSessionId(UUID.fromString(requiredString(object, "sessionId"))),
                requiredString(object, "goal"),
                requiredString(object, "nextStep"),
                optionalString(object, "approach"),
                optionalString(object, "decisions"),
                optionalString(object, "ruledOut"),
                optionalString(object, "corrections"),
                Instant.parse(requiredString(object, "writtenAt")),
                optionalString(object, "writtenAtCommit"),
                authorFrom(object)));
    } catch (IllegalArgumentException | DateTimeException | StateDecodeException e) {
        return Optional.empty();   // a bad brief costs a brief, never the whole state
    }
}

private static HandoffBrief.Author authorFrom(JsonObject object) {
    // Absent or unrecognized decodes to AGENT: every brief written before
    // this member existed came from session_handoff.
    return optionalString(object, "author")
            .flatMap(name -> Arrays.stream(HandoffBrief.Author.values())
                    .filter(a -> a.name().equals(name))
                    .findFirst())
            .orElse(HandoffBrief.Author.AGENT);
}
```

Decode `forkedFrom` on a session the same way — absent, malformed, or an
unparseable UUID all yield `Optional.empty()`, since a session persisted
before this member existed was not a fork.

Reuse the codec's existing `requiredString` / `optionalString` helpers if they
are present under different names; do not add duplicates.

- [ ] **Step 7: Update the codec's schema javadoc**

Add `handoffBriefs` and the session `forkedFrom` member to the schema block,
and add a migration sentence in the established voice: both were added
leniently within version 2 — absent decodes to an empty list / empty
`Optional`, a malformed brief entry is dropped rather than throwing, and
`author` defaults to `AGENT` — so no version bump was needed and downgrades
stay non-destructive.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.state.*"`
Expected: PASS, including the pre-existing codec tests (the added record
components must not have broken them).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/app/drydock/domain/HandoffBrief.java \
        app/src/main/java/app/drydock/domain/ApplicationState.java \
        app/src/main/java/app/drydock/domain/ManagedAgentSession.java \
        app/src/main/java/app/drydock/state/ApplicationStateCodec.java \
        app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java
git commit -m "A session can carry a handoff brief and remember what it was forked from"
```

---

### Task 2: Slot validation and caps

**Files:**
- Modify: `app/src/main/java/app/drydock/mcp/PromptSafety.java`
- Test: `app/src/test/java/app/drydock/mcp/PromptSafetyTest.java`

**Interfaces:**
- Consumes: `McpToolException` (existing).
- Produces:
  `PromptSafety.MAX_HANDOFF_SLOT_CHARS = 2000` (int constant);
  `PromptSafety.MAX_HANDOFF_RECORD_CHARS = 8000` (int constant);
  `PromptSafety.checkHandoffSlot(String field, String text) throws McpToolException -> String`;
  `PromptSafety.checkHandoffRecordSize(List<String> slots) throws McpToolException -> void`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void handoffSlotAcceptsMultilineBody() throws Exception {
    String body = "line one\nline two\twith a tab";
    assertEquals(body, PromptSafety.checkHandoffSlot("goal", body));
}

@Test
void handoffSlotRejectsControlCharacters() {
    McpToolException e = assertThrows(McpToolException.class,
            () -> PromptSafety.checkHandoffSlot("goal", "before after"));
    assertTrue(e.getMessage().contains("goal"));
}

@Test
void handoffSlotRejectsOversizeNamingSlotAndLimit() {
    String tooLong = "x".repeat(PromptSafety.MAX_HANDOFF_SLOT_CHARS + 1);

    McpToolException e = assertThrows(McpToolException.class,
            () -> PromptSafety.checkHandoffSlot("ruledOut", tooLong));

    assertTrue(e.getMessage().contains("ruledOut"), e.getMessage());
    assertTrue(e.getMessage().contains(String.valueOf(PromptSafety.MAX_HANDOFF_SLOT_CHARS)), e.getMessage());
}

@Test
void handoffSlotCountsCodePointsNotChars() throws Exception {
    // Emoji are surrogate pairs: a char-based cap would reject half of a
    // brief that is well inside the limit a human would count.
    String emoji = "🚀".repeat(PromptSafety.MAX_HANDOFF_SLOT_CHARS / 2);
    assertEquals(emoji, PromptSafety.checkHandoffSlot("goal", emoji));
}

@Test
void handoffRecordRejectsSlotsThatFitIndividuallyButNotTogether() {
    String slot = "x".repeat(PromptSafety.MAX_HANDOFF_SLOT_CHARS);
    List<String> six = List.of(slot, slot, slot, slot, slot, slot);   // 12,000 > 8,000

    McpToolException e = assertThrows(McpToolException.class,
            () -> PromptSafety.checkHandoffRecordSize(six));

    assertTrue(e.getMessage().contains(String.valueOf(PromptSafety.MAX_HANDOFF_RECORD_CHARS)), e.getMessage());
}

@Test
void handoffRecordAcceptsSlotsWithinTheWholeRecordCap() throws Exception {
    PromptSafety.checkHandoffRecordSize(List.of("a".repeat(100), "b".repeat(100)));
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.mcp.PromptSafetyTest"`
Expected: FAIL — `checkHandoffSlot` and the constants are undefined.

- [ ] **Step 3: Implement in `PromptSafety`**

```java
/** Per-slot cap, in code points. See the handoff design's "Refuse, never truncate". */
public static final int MAX_HANDOFF_SLOT_CHARS = 2000;

/** Whole-record cap, in code points; binds before six full slots would. */
public static final int MAX_HANDOFF_RECORD_CHARS = 8000;

/**
 * Validates one {@code session_handoff} slot. Unlike {@link
 * #checkSessionTitle}, a slot is a body: {@code \n}, {@code \r} and {@code
 * \t} are permitted, exactly as for finding bodies. The text is returned
 * unchanged -- there is no folding, because a brief's line structure is
 * meaningful to the successor reading it.
 */
public static String checkHandoffSlot(String field, String text) throws McpToolException {
    checkInboundText(field, text);
    int codePoints = text.codePointCount(0, text.length());
    if (codePoints > MAX_HANDOFF_SLOT_CHARS) {
        throw new McpToolException(field + " is " + codePoints + " characters; the limit is "
                + MAX_HANDOFF_SLOT_CHARS + ". Shorten it -- drydock will not truncate a brief, "
                + "because a clipped slot is a brief that lies.");
    }
    return text;
}

/** Refuses a record whose slots each fit but which together exceed the whole-record cap. */
public static void checkHandoffRecordSize(List<String> slots) throws McpToolException {
    int total = 0;
    for (String slot : slots) {
        total += slot.codePointCount(0, slot.length());
    }
    if (total > MAX_HANDOFF_RECORD_CHARS) {
        throw new McpToolException("The whole brief is " + total + " characters; the limit is "
                + MAX_HANDOFF_RECORD_CHARS + ". Shorten the longest slots.");
    }
}
```

If `checkInboundText`'s existing signature differs (e.g. it returns the text,
or takes arguments in another order), adapt the call rather than changing that
method — it is used by the review tools and its contract is settled.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.mcp.PromptSafetyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/PromptSafety.java \
        app/src/test/java/app/drydock/mcp/PromptSafetyTest.java
git commit -m "Handoff slots are validated as bodies and refused when oversize"
```

---

### Task 3: The `session_handoff` MCP tool

**Files:**
- Modify: `app/src/main/java/app/drydock/mcp/McpSessionContext.java`
- Modify: `app/src/main/java/app/drydock/mcp/McpSessionRegistry.java`
- Modify: `app/src/main/java/app/drydock/mcp/McpToolRouter.java`
- Modify: `app/src/main/java/app/drydock/mcp/McpServer.java`
- Modify: `app/src/main/java/app/drydock/mcp/WorkspaceMcpSessionContext.java`
- Modify: `app/src/test/java/app/drydock/mcp/FakeMcpSessionContext.java`
- Create: `app/src/test/java/app/drydock/mcp/McpToolRouterSessionHandoffTest.java`

**Interfaces:**
- Consumes: `HandoffBrief`, `HandoffBrief.Author` (Task 1);
  `PromptSafety.checkHandoffSlot`, `checkHandoffRecordSize` (Task 2).
- Produces:
  `McpSessionContext.writeHandoff(ManagedSessionId caller, HandoffDraft draft) throws McpToolException -> HandoffBrief`;
  `McpSessionContext.HandoffDraft` record
  `(String goal, String nextStep, Optional<String> approach, Optional<String> decisions, Optional<String> ruledOut, Optional<String> corrections)`;
  `McpSessionRegistry.MAX_HANDOFFS_PER_SESSION = 40`;
  `McpSessionRegistry.chargeHandoff(ManagedSessionId) throws McpBudgetExhaustedException`;
  `McpSessionRegistry.refundHandoff(ManagedSessionId)`.

The draft carries only what the agent supplies. `writtenAt`,
`writtenAtCommit` and `author` are stamped by the implementation, never by
the caller — an agent must not be able to backdate its own brief.

- [ ] **Step 1: Write the failing router tests**

Create `McpToolRouterSessionHandoffTest`, modelled on
`McpToolRouterSessionRenameTest` (read that file first for the fixture
style — how it builds a router over `FakeMcpSessionContext` and calls
`router.call(caller, tool, args)`):

```java
@Test
void writesABriefFromTheRequiredSlotsAlone() throws Exception {
    JsonValue result = router.call(caller, "session_handoff", JsonObject.empty()
            .put("goal", new JsonString("Ship the fork gesture"))
            .put("nextStep", new JsonString("Wire the banner")));

    assertEquals("written", JsonPeek.string(result, "outcome"));
    HandoffBrief stored = context.lastHandoff().orElseThrow();
    assertEquals("Ship the fork gesture", stored.goal());
    assertEquals(Optional.empty(), stored.approach());
    assertEquals(HandoffBrief.Author.AGENT, stored.author());
}

@Test
void anOmittedOptionalSlotClearsTheStoredValue() throws Exception {
    router.call(caller, "session_handoff", JsonObject.empty()
            .put("goal", new JsonString("g"))
            .put("nextStep", new JsonString("n"))
            .put("ruledOut", new JsonString("tried the proxy")));
    assertTrue(context.lastHandoff().orElseThrow().ruledOut().isPresent());

    router.call(caller, "session_handoff", JsonObject.empty()
            .put("goal", new JsonString("g"))
            .put("nextStep", new JsonString("n")));

    assertEquals(Optional.empty(), context.lastHandoff().orElseThrow().ruledOut());
}

@Test
void refusesAMissingRequiredSlot() {
    McpToolException e = assertThrows(McpToolException.class,
            () -> router.call(caller, "session_handoff",
                    JsonObject.empty().put("goal", new JsonString("g"))));

    assertTrue(e.getMessage().contains("nextStep"), e.getMessage());
}

@Test
void refusesABlankRequiredSlot() {
    assertThrows(McpToolException.class,
            () -> router.call(caller, "session_handoff", JsonObject.empty()
                    .put("goal", new JsonString("   "))
                    .put("nextStep", new JsonString("n"))));
}

@Test
void refusesAnOversizeSlotWithoutStoringAnything() {
    assertThrows(McpToolException.class,
            () -> router.call(caller, "session_handoff", JsonObject.empty()
                    .put("goal", new JsonString("x".repeat(PromptSafety.MAX_HANDOFF_SLOT_CHARS + 1)))
                    .put("nextStep", new JsonString("n"))));

    assertEquals(Optional.empty(), context.lastHandoff());
}

@Test
void aRefusedCallIsNotCharged() throws Exception {
    for (int i = 0; i < McpSessionRegistry.MAX_HANDOFFS_PER_SESSION; i++) {
        assertThrows(McpToolException.class,
                () -> router.call(caller, "session_handoff", JsonObject.empty()));
    }

    // The budget is intact, because validation happens before the charge.
    JsonValue result = router.call(caller, "session_handoff", JsonObject.empty()
            .put("goal", new JsonString("g"))
            .put("nextStep", new JsonString("n")));
    assertEquals("written", JsonPeek.string(result, "outcome"));
}

@Test
void refusesOnceTheBudgetIsExhausted() throws Exception {
    JsonObject valid = JsonObject.empty()
            .put("goal", new JsonString("g"))
            .put("nextStep", new JsonString("n"));
    for (int i = 0; i < McpSessionRegistry.MAX_HANDOFFS_PER_SESSION; i++) {
        router.call(caller, "session_handoff", valid);
    }

    assertThrows(McpToolException.class, () -> router.call(caller, "session_handoff", valid));
}

@Test
void refusesWhenTheSessionHasEnded() {
    context.setSessionRunning(false);

    assertThrows(McpToolException.class,
            () -> router.call(caller, "session_handoff", JsonObject.empty()
                    .put("goal", new JsonString("g"))
                    .put("nextStep", new JsonString("n"))));
}
```

Add to `McpServerTest`:

```java
@Test
void sessionHandoffIsClassifiedAsAWrite() {
    assertEquals(McpActivityLog.Direction.INBOUND, McpServer.directionOf("session_handoff"));
}

@Test
void instructionsTellTheAgentToKeepTheHandoffCurrent() {
    assertTrue(McpServer.instructions().contains("session_handoff"));
}
```

If `INSTRUCTIONS` is private with no accessor, add a package-private
`static String instructions()` returning it rather than making the field
public.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpToolRouterSessionHandoffTest" --tests "app.drydock.mcp.McpServerTest"`
Expected: FAIL — unknown tool `session_handoff`.

- [ ] **Step 3: Add the context seam**

In `McpSessionContext`:

```java
/** What an agent supplies to {@code session_handoff}; the stamps are drydock's. */
record HandoffDraft(String goal, String nextStep, Optional<String> approach, Optional<String> decisions,
                    Optional<String> ruledOut, Optional<String> corrections) {
}

/**
 * Replaces the caller's handoff brief with {@code draft}, stamping {@code
 * writtenAt}, {@code writtenAtCommit} (the caller's current HEAD, empty if
 * its branch has no commits) and {@code author = AGENT}, then persists.
 *
 * <p>Wholesale replacement: an absent optional slot in {@code draft} clears
 * whatever was stored there. Throws only for a timeout or a session that
 * vanished mid-call -- there is no refusal outcome, because unlike a rename
 * a brief cannot collide with or be pinned by anything.</p>
 */
HandoffBrief writeHandoff(ManagedSessionId caller, HandoffDraft draft) throws McpToolException;
```

In `FakeMcpSessionContext`, store the last written brief and expose
`Optional<HandoffBrief> lastHandoff()` plus a `setSessionRunning(boolean)` if
one does not already exist.

- [ ] **Step 4: Add the budget**

In `McpSessionRegistry`, mirror the rename budget exactly:

```java
/**
 * Twice MAX_RENAMES_PER_SESSION: a brief is rewritten at every checkpoint by
 * design, where a name is written once or twice. Still bounded, because an
 * agent that has written 40 briefs is looping, not working.
 */
public static final int MAX_HANDOFFS_PER_SESSION = 40;

public void chargeHandoff(ManagedSessionId sessionId) throws McpBudgetExhaustedException {
    // Same counter mechanics as chargeRename -- copy that method's body,
    // substituting the handoff counter map and MAX_HANDOFFS_PER_SESSION.
}

public void refundHandoff(ManagedSessionId sessionId) {
    // As refundRename.
}
```

- [ ] **Step 5: Add the descriptor and the router case**

In `McpToolRouter.descriptors()`, after `session_rename`:

```java
descriptor("session_handoff",
        "Records what this session would tell a successor, so the human can hand the work to a "
                + "different agent at any moment. Keep it current as you work -- you are writing "
                + "for whoever picks this up, not for the human. Every call REPLACES the whole "
                + "brief: an omitted optional slot is cleared, not preserved.",
        JsonObject.empty()
                .put("goal", schemaString("What this session is trying to achieve."))
                .put("nextStep", schemaString("What the successor should do first."))
                .put("approach", schemaString("The shape of the current solution."))
                .put("decisions", schemaString("Choices made, and why."))
                .put("ruledOut", schemaString("What was tried or considered and rejected, with the "
                        + "reason -- the part a successor cannot reconstruct from the code."))
                .put("corrections", schemaString("What the human pushed back on.")),
        "goal", "nextStep"),
```

Add `case "session_handoff" -> sessionHandoff(caller, arguments);` and:

```java
private JsonValue sessionHandoff(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
    requireLiveSession(caller);
    JsonObject args = asObject(arguments);

    // Validate before charging: a malformed brief is the agent's mistake to
    // fix, not a spend. (Same rule as session_rename.)
    String goal = PromptSafety.checkHandoffSlot("goal", requiredStringArg(args, "goal"));
    String nextStep = PromptSafety.checkHandoffSlot("nextStep", requiredStringArg(args, "nextStep"));
    Optional<String> approach = optionalSlot(args, "approach");
    Optional<String> decisions = optionalSlot(args, "decisions");
    Optional<String> ruledOut = optionalSlot(args, "ruledOut");
    Optional<String> corrections = optionalSlot(args, "corrections");

    List<String> present = new ArrayList<>(List.of(goal, nextStep));
    approach.ifPresent(present::add);
    decisions.ifPresent(present::add);
    ruledOut.ifPresent(present::add);
    corrections.ifPresent(present::add);
    PromptSafety.checkHandoffRecordSize(present);

    if (goal.isBlank() || nextStep.isBlank()) {
        throw new McpToolException("goal and nextStep must not be blank: a brief with no goal or no "
                + "next step tells a successor nothing.");
    }

    try {
        registry.chargeHandoff(caller);
    } catch (McpBudgetExhaustedException e) {
        throw new McpToolException(e.getMessage());
    }

    HandoffBrief written;
    try {
        written = context.writeHandoff(caller, new McpSessionContext.HandoffDraft(
                goal, nextStep, approach, decisions, ruledOut, corrections));
    } catch (McpToolException e) {
        registry.refundHandoff(caller);   // an outright failure is refunded
        throw e;
    }

    return JsonObject.empty()
            .put("outcome", new JsonString("written"))
            .put("writtenAt", new JsonString(written.writtenAt().toString()));
}

/** An optional slot: absent or blank means "clear this slot", not "keep it". */
private static Optional<String> optionalSlot(JsonObject args, String key) throws McpToolException {
    if (!(args.get(key) instanceof JsonString text) || text.value().isBlank()) {
        return Optional.empty();
    }
    return Optional.of(PromptSafety.checkHandoffSlot(key, text.value()));
}
```

Use the router's existing `requiredStringArg` and `asObject` helpers; do not
add parallel ones.

- [ ] **Step 6: Classify it and advertise it**

In `McpServer`, add `"session_handoff"` to `AGENT_WRITE_TOOLS`, and append to
`INSTRUCTIONS`:

```java
Keep session_handoff current as you work: this session may be handed to a \
different agent at any moment, and the brief is all your successor gets. \
Write it for them, not for the human -- especially what you ruled out and \
why, which they cannot recover from the code.\
```

- [ ] **Step 7: Wire the workspace implementation**

In `WorkspaceMcpSessionContext`, add a `handoffWriter` collaborator in the
same shape as the existing `sessionRenamer`
(`BiFunction<ManagedSessionId, HandoffDraft, CompletableFuture<HandoffBrief>>`),
bound in `MainWorkspace` to a new `writeHandoffFromAgent` method that hops to
the FX thread, stamps `Instant.now()`, resolves the caller's `HEAD` off the FX
thread via `GitStatusService`, stores the brief into `ApplicationState` and
republishes. Join with a timeout constant beside `RENAME_TIMEOUT_SECONDS`.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.mcp.*"`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/ app/src/test/java/app/drydock/mcp/
git commit -m "Agents keep a handoff brief current through session_handoff"
```

---

### Task 4: Staleness

**Files:**
- Create: `app/src/main/java/app/drydock/handoff/HandoffStaleness.java`
- Create: `app/src/test/java/app/drydock/handoff/HandoffStalenessTest.java`

**Interfaces:**
- Consumes: `HandoffBrief` (Task 1).
- Produces:
  `HandoffStaleness` record `(int commitsSince, int changedFiles, boolean briefMissing)`;
  `HandoffStaleness.of(Optional<HandoffBrief>, int commitsSince, int changedFiles) -> HandoffStaleness`;
  `HandoffStaleness.shouldWarn() -> boolean`;
  `HandoffStaleness.describe() -> String`.

Git counting is the caller's job (Task 7 supplies the numbers from
`git rev-list --count <commit>..HEAD` and `git diff --name-only <commit>`);
this class is pure so it is testable without a repository.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void warnsWhenNoBriefWasEverWritten() {
    HandoffStaleness staleness = HandoffStaleness.of(Optional.empty(), 0, 0);

    assertTrue(staleness.shouldWarn());
    assertTrue(staleness.describe().contains("No handoff brief"), staleness.describe());
}

@Test
void doesNotWarnWhenNothingHasChangedSinceTheBriefWasWritten() {
    assertFalse(HandoffStaleness.of(Optional.of(brief()), 0, 0).shouldWarn());
}

@Test
void doesNotWarnOnElapsedTimeAlone() {
    // A session idle for a day has a brief that is still perfectly accurate.
    HandoffBrief old = brief();   // writtenAt is a year in the past
    assertFalse(HandoffStaleness.of(Optional.of(old), 0, 0).shouldWarn());
}

@Test
void warnsAndCountsWorkWhenCommitsOrFilesMoved() {
    HandoffStaleness staleness = HandoffStaleness.of(Optional.of(brief()), 9, 40);

    assertTrue(staleness.shouldWarn());
    assertEquals("Brief written 9 commits and 40 changed files ago", staleness.describe());
}

@Test
void describesSingularCountsWithoutPlurals() {
    assertEquals("Brief written 1 commit and 1 changed file ago",
            HandoffStaleness.of(Optional.of(brief()), 1, 1).describe());
}

@Test
void describesFilesOnlyWhenNoCommitsWereMade() {
    assertEquals("Brief written 3 changed files ago",
            HandoffStaleness.of(Optional.of(brief()), 0, 3).describe());
}
```

`brief()` builds a `HandoffBrief` with `writtenAt` a year in the past, so the
elapsed-time test is meaningful.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.handoff.HandoffStalenessTest"`
Expected: FAIL — `HandoffStaleness` does not exist.

- [ ] **Step 3: Implement**

```java
package app.drydock.handoff;

import app.drydock.domain.HandoffBrief;

import java.util.Optional;

/**
 * How far a session's work has moved since its handoff brief was written.
 *
 * <p>Measured in work rather than in clock time on purpose: "9 commits and 40
 * changed files ago" tells the human whether the brief still describes the
 * tree, where "two hours old" does not. A session idle for a day has a brief
 * that is still perfectly accurate, so elapsed time never raises the
 * warning.</p>
 */
public record HandoffStaleness(int commitsSince, int changedFiles, boolean briefMissing) {

    public static HandoffStaleness of(Optional<HandoffBrief> brief, int commitsSince, int changedFiles) {
        if (brief.isEmpty()) {
            return new HandoffStaleness(0, 0, true);
        }
        return new HandoffStaleness(commitsSince, changedFiles, false);
    }

    public boolean shouldWarn() {
        return briefMissing || commitsSince > 0 || changedFiles > 0;
    }

    public String describe() {
        if (briefMissing) {
            return "No handoff brief has been written for this session";
        }
        if (!shouldWarn()) {
            return "Brief is current";
        }
        String commits = commitsSince + (commitsSince == 1 ? " commit" : " commits");
        String files = changedFiles + (changedFiles == 1 ? " changed file" : " changed files");
        if (commitsSince == 0) {
            return "Brief written " + files + " ago";
        }
        if (changedFiles == 0) {
            return "Brief written " + commits + " ago";
        }
        return "Brief written " + commits + " and " + files + " ago";
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.handoff.HandoffStalenessTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/handoff/HandoffStaleness.java \
        app/src/test/java/app/drydock/handoff/HandoffStalenessTest.java
git commit -m "Handoff staleness is measured in work done, not in elapsed time"
```

---

### Task 5: Seed composition

**Files:**
- Create: `app/src/main/java/app/drydock/handoff/ForkFacts.java`
- Create: `app/src/main/java/app/drydock/handoff/HandoffSeed.java`
- Create: `app/src/test/java/app/drydock/handoff/HandoffSeedTest.java`

**Interfaces:**
- Consumes: `HandoffBrief`, `HandoffBrief.Author` (Task 1).
- Produces:
  `ForkFacts` record `(String branch, String baseBranch, List<String> commitSubjects, List<String> changedFiles, List<String> openIntents)`;
  `HandoffSeed.compose(Optional<HandoffBrief> brief, ForkFacts facts) -> String`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void labelsAnAgentBriefAsThePreviousSessionsTestimony() {
    String seed = HandoffSeed.compose(Optional.of(agentBrief()), facts());

    assertTrue(seed.contains("reported by the previous session"), seed);
    assertTrue(seed.contains("Ship the fork gesture"), seed);
}

@Test
void labelsAHumanBriefAsWrittenByTheHuman() {
    String seed = HandoffSeed.compose(Optional.of(humanBrief()), facts());

    assertTrue(seed.contains("written by the human"), seed);
    assertFalse(seed.contains("reported by the previous session"), seed);
}

@Test
void statesPlainlyWhenNoBriefWasRecorded() {
    String seed = HandoffSeed.compose(Optional.empty(), facts());

    assertTrue(seed.contains("No handoff brief was recorded"), seed);
}

@Test
void alwaysIncludesTheFactsDrydockDerivedItself() {
    String seed = HandoffSeed.compose(Optional.empty(), facts());

    assertTrue(seed.contains("feat/fork"), seed);          // branch
    assertTrue(seed.contains("main"), seed);               // base
    assertTrue(seed.contains("Add the banner"), seed);     // commit subject
    assertTrue(seed.contains("MainWorkspace.java"), seed); // changed file
}

@Test
void separatesDerivedFactsFromTestimony() {
    String seed = HandoffSeed.compose(Optional.of(agentBrief()), facts());

    assertTrue(seed.indexOf("derived by drydock") >= 0, seed);
    // The successor must be able to tell which half is checkable.
    assertNotEquals(seed.indexOf("derived by drydock"), seed.indexOf("reported by the previous session"));
}

@Test
void omitsAbsentOptionalSlotsRatherThanPrintingEmptyHeadings() {
    HandoffBrief minimal = new HandoffBrief(
            ManagedSessionId.newId(), "Goal", "Next",
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Instant.now(), Optional.empty(), HandoffBrief.Author.AGENT);

    String seed = HandoffSeed.compose(Optional.of(minimal), facts());

    assertFalse(seed.contains("Ruled out"), seed);
    assertFalse(seed.contains("Corrections"), seed);
}

@Test
void omitsEmptyFactSectionsRatherThanPrintingEmptyHeadings() {
    ForkFacts bare = new ForkFacts("feat/fork", "main", List.of(), List.of(), List.of());

    String seed = HandoffSeed.compose(Optional.empty(), bare);

    assertFalse(seed.contains("Commits"), seed);
    assertFalse(seed.contains("Open review intents"), seed);
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.handoff.HandoffSeedTest"`
Expected: FAIL — `HandoffSeed` and `ForkFacts` do not exist.

- [ ] **Step 3: Implement `ForkFacts`**

```java
package app.drydock.handoff;

import java.util.List;
import java.util.Objects;

/**
 * What drydock knows about the forked work without asking anyone. Derived
 * fresh at fork time, so it is available and correct even when the outgoing
 * session is wedged or dead -- which is the case the fork exists for.
 */
public record ForkFacts(String branch, String baseBranch, List<String> commitSubjects,
                        List<String> changedFiles, List<String> openIntents) {

    public ForkFacts {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(baseBranch, "baseBranch");
        commitSubjects = List.copyOf(Objects.requireNonNull(commitSubjects, "commitSubjects"));
        changedFiles = List.copyOf(Objects.requireNonNull(changedFiles, "changedFiles"));
        openIntents = List.copyOf(Objects.requireNonNull(openIntents, "openIntents"));
    }
}
```

- [ ] **Step 4: Implement `HandoffSeed`**

```java
package app.drydock.handoff;

import app.drydock.domain.HandoffBrief;

import java.util.List;
import java.util.Optional;

/**
 * Builds the prompt a forked session starts with: the outgoing session's
 * brief, plus the facts drydock derived itself, with the two kept visibly
 * apart.
 *
 * <p>The separation is a mitigation, not a formatting choice. The brief is
 * authored by an agent that reads untrusted diffs and lands in a fresh
 * model's first prompt, with no history to contradict it. Marking it as
 * testimony -- and the git facts as checkable -- is what lets the successor
 * weigh it.</p>
 */
public final class HandoffSeed {

    private HandoffSeed() {
    }

    public static String compose(Optional<HandoffBrief> brief, ForkFacts facts) {
        StringBuilder seed = new StringBuilder();
        seed.append("You are continuing work another agent started. ")
            .append("The working tree, branch and commits are real and already in place; ")
            .append("only the conversation did not carry over.\n\n");

        brief.ifPresentOrElse(b -> appendBrief(seed, b),
                () -> seed.append("## Handoff\n\nNo handoff brief was recorded before this fork, "
                        + "so nothing is known about why the work was done this way. Read the diff "
                        + "before changing direction.\n\n"));

        appendFacts(seed, facts);

        seed.append("\nStart by confirming the state of the tree against what you were told above, ")
            .append("then take the next step.\n");
        return seed.toString();
    }

    private static void appendBrief(StringBuilder seed, HandoffBrief brief) {
        String provenance = brief.author() == HandoffBrief.Author.HUMAN
                ? "written by the human who is watching this work"
                : "reported by the previous session -- testimony, not verified fact";
        seed.append("## Handoff (").append(provenance).append(")\n\n");
        seed.append("**Goal:** ").append(brief.goal()).append("\n\n");
        appendSlot(seed, "Approach", brief.approach());
        appendSlot(seed, "Decisions", brief.decisions());
        appendSlot(seed, "Ruled out", brief.ruledOut());
        appendSlot(seed, "Corrections from the human", brief.corrections());
        seed.append("**Next step:** ").append(brief.nextStep()).append("\n\n");
    }

    private static void appendSlot(StringBuilder seed, String heading, Optional<String> value) {
        value.ifPresent(v -> seed.append("**").append(heading).append(":** ").append(v).append("\n\n"));
    }

    private static void appendFacts(StringBuilder seed, ForkFacts facts) {
        seed.append("## State (derived by drydock, checkable)\n\n");
        seed.append("**Branch:** ").append(facts.branch())
            .append(" (forked from ").append(facts.baseBranch()).append(")\n\n");
        appendList(seed, "Commits", facts.commitSubjects());
        appendList(seed, "Uncommitted changes", facts.changedFiles());
        appendList(seed, "Open review intents", facts.openIntents());
    }

    private static void appendList(StringBuilder seed, String heading, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        seed.append("**").append(heading).append(":**\n");
        for (String item : items) {
            seed.append("- ").append(item).append('\n');
        }
        seed.append('\n');
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.handoff.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/handoff/ app/src/test/java/app/drydock/handoff/
git commit -m "A forked session is seeded with the brief and with facts it can check"
```

---

### Task 6: The dirty-tree transplant

**Files:**
- Create: `app/src/main/java/app/drydock/git/WorktreeTransplant.java`
- Create: `app/src/test/java/app/drydock/git/WorktreeTransplantTest.java`

**Interfaces:**
- Consumes: `GitExecutableLocator`, `GitCommandFailedException`,
  `ProcessRunner` (all existing in `app.drydock.git`).
- Produces:
  `WorktreeTransplant(GitExecutableLocator locator)` constructor;
  `WorktreeTransplant.transplantBlocking(Path source, Path destination) -> int`
  (returns the number of files carried over; throws
  `GitCommandFailedException` on any failure).

Package-private-`*Blocking` naming follows `GitStatusService`. This class has
no executor of its own: Task 7 calls it from the fork's background task.

- [ ] **Step 1: Write the failing tests**

These need real repositories. Follow the fixture style already used in
`app/src/test/java/app/drydock/git/` — read one existing git test first to
find the helper that creates a temp repo and runs git in it, and reuse it.

```java
@Test
void carriesTrackedEditsAcrossUncommitted() throws Exception {
    Path source = repoWithCommit("a.txt", "original");
    Files.writeString(source.resolve("a.txt"), "edited");
    Path destination = worktreeOf(source, "fork");

    transplant.transplantBlocking(source, destination);

    assertEquals("edited", Files.readString(destination.resolve("a.txt")));
    assertTrue(git(destination, "status", "--porcelain").contains("a.txt"),
            "the change must land uncommitted so the review rail sees a diff");
}

@Test
void carriesUntrackedFiles() throws Exception {
    Path source = repoWithCommit("a.txt", "original");
    Files.writeString(source.resolve("new.txt"), "brand new");
    Path destination = worktreeOf(source, "fork");

    transplant.transplantBlocking(source, destination);

    assertEquals("brand new", Files.readString(destination.resolve("new.txt")));
}

@Test
void carriesDeletions() throws Exception {
    Path source = repoWithCommit("a.txt", "original");
    Files.delete(source.resolve("a.txt"));
    Path destination = worktreeOf(source, "fork");

    transplant.transplantBlocking(source, destination);

    assertFalse(Files.exists(destination.resolve("a.txt")));
}

@Test
void carriesBinaryContent() throws Exception {
    Path source = repoWithCommit("a.txt", "original");
    byte[] bytes = {0, 1, 2, (byte) 0xFF, 0, 3};
    Files.write(source.resolve("blob.bin"), bytes);
    Path destination = worktreeOf(source, "fork");

    transplant.transplantBlocking(source, destination);

    assertArrayEquals(bytes, Files.readAllBytes(destination.resolve("blob.bin")));
}

@Test
void skipsIgnoredFiles() throws Exception {
    Path source = repoWithCommit(".gitignore", "build/\n");
    Files.createDirectory(source.resolve("build"));
    Files.writeString(source.resolve("build/out.o"), "artifact");
    Path destination = worktreeOf(source, "fork");

    transplant.transplantBlocking(source, destination);

    assertFalse(Files.exists(destination.resolve("build/out.o")));
}

@Test
void carriesNothingAndSucceedsWhenTheSourceIsClean() throws Exception {
    Path source = repoWithCommit("a.txt", "original");
    Path destination = worktreeOf(source, "fork");

    assertEquals(0, transplant.transplantBlocking(source, destination));
}

@Test
void worksWhenTheSourceBranchHasNoCommitsYet() throws Exception {
    Path source = emptyRepo();                       // git init, nothing committed
    Files.writeString(source.resolve("first.txt"), "content");
    Path destination = emptyRepo();

    transplant.transplantBlocking(source, destination);

    assertEquals("content", Files.readString(destination.resolve("first.txt")));
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.git.WorktreeTransplantTest"`
Expected: FAIL — `WorktreeTransplant` does not exist.

- [ ] **Step 3: Implement**

```java
package app.drydock.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies one worktree's uncommitted state onto another, leaving it
 * uncommitted so the review rail sees a real diff.
 *
 * <p>Two moves, because git has no single command for this: tracked changes
 * (including deletions and binary content) cross as a {@code git diff HEAD
 * --binary} patch applied in the destination, and untracked non-ignored files
 * are copied byte-for-byte.</p>
 *
 * <p><strong>All-or-nothing is the caller's job, not this class's.</strong>
 * Any failure throws, and {@code SessionForkService} responds by removing the
 * destination worktree and its branch. A half-populated worktree that looks
 * like a successful fork is worse than a visible failure, because the human
 * would start working in it.</p>
 */
public final class WorktreeTransplant {

    private final GitExecutableLocator locator;

    public WorktreeTransplant(GitExecutableLocator locator) {
        this.locator = locator;
    }

    /**
     * @return how many files were carried over
     * @throws GitCommandFailedException if any step fails; the destination is
     *         then in an undefined state and must be discarded by the caller
     */
    public int transplantBlocking(Path source, Path destination) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        int carried = applyTrackedChanges(git, source, destination);
        carried += copyUntracked(git, source, destination);
        return carried;
    }

    private int applyTrackedChanges(Path git, Path source, Path destination) {
        // A repository with no commits has no HEAD to diff against; every file
        // in it is untracked, so copyUntracked carries the whole tree.
        ProcessResult head = run(List.of(git.toString(), "-C", source.toString(),
                "rev-parse", "--verify", "HEAD"));
        if (head.exitCode() != 0) {
            return 0;
        }

        ProcessResult diff = run(List.of(git.toString(), "-C", source.toString(),
                "diff", "HEAD", "--binary"));
        if (diff.exitCode() != 0) {
            throw new GitCommandFailedException(List.of("git", "diff", "HEAD", "--binary"),
                    diff.exitCode(), ProcessRunner.excerpt(diff.stderr()));
        }
        if (diff.stdout().isBlank()) {
            return 0;
        }

        ProcessResult apply = runWithStdin(List.of(git.toString(), "-C", destination.toString(),
                "apply", "--binary", "-"), diff.stdout());
        if (apply.exitCode() != 0) {
            throw new GitCommandFailedException(List.of("git", "apply", "--binary"),
                    apply.exitCode(), ProcessRunner.excerpt(apply.stderr()));
        }
        return countPatchedFiles(diff.stdout());
    }

    private int copyUntracked(Path git, Path source, Path destination) {
        ProcessResult listed = run(List.of(git.toString(), "-C", source.toString(),
                "ls-files", "--others", "--exclude-standard", "-z"));
        if (listed.exitCode() != 0) {
            throw new GitCommandFailedException(List.of("git", "ls-files", "--others"),
                    listed.exitCode(), ProcessRunner.excerpt(listed.stderr()));
        }

        int copied = 0;
        for (String relative : listed.stdout().split(" ")) {
            if (relative.isEmpty()) {
                continue;
            }
            Path from = source.resolve(relative);
            Path to = destination.resolve(relative);
            try {
                Path parent = to.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            } catch (IOException e) {
                throw new GitCommandFailedException(List.of("copy", relative), -1,
                        e.getMessage() == null ? "could not copy untracked file" : e.getMessage());
            }
        }
        return copied;
    }

    /** Counts "diff --git" headers: one per file the patch touches. */
    private static int countPatchedFiles(String patch) {
        int count = 0;
        for (String line : patch.split("\n")) {
            if (line.startsWith("diff --git ")) {
                count++;
            }
        }
        return count;
    }
}
```

`run` and `runWithStdin` follow `GitStatusService`'s private `run` helpers. If
`ProcessRunner` has no stdin-feeding form, add one there rather than spawning
a process by hand here — `ProcessRunner` is the single place process spawning
lives.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.git.WorktreeTransplantTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/git/WorktreeTransplant.java \
        app/src/test/java/app/drydock/git/WorktreeTransplantTest.java
git commit -m "A worktree's uncommitted state can be carried onto a sibling"
```

---

### Task 7: The fork operation

**Files:**
- Create: `app/src/main/java/app/drydock/app/SessionForkService.java`
- Create: `app/src/test/java/app/drydock/app/SessionForkServiceTest.java`

**Interfaces:**
- Consumes: `HandoffBrief` (Task 1), `HandoffStaleness` (Task 4),
  `ForkFacts` + `HandoffSeed.compose` (Task 5),
  `WorktreeTransplant.transplantBlocking` (Task 6),
  `GitStatusService.createWorktreeBlocking`, `WorktreeNaming`, `BranchNames`.
- Produces:
  `SessionForkService.fork(ManagedAgentSession outgoing, AgentKind target) -> CompletableFuture<ManagedSessionId>`;
  `SessionForkService.forkBlocking(ManagedAgentSession outgoing, AgentKind target) -> ManagedSessionId`;
  `SessionForkService.staleness(ManagedAgentSession) -> CompletableFuture<HandoffStaleness>`;
  `SessionForkService.Launcher` interface (the seam the test's fake implements):

```java
/**
 * Starts the forked session. A seam rather than a direct call into
 * MainWorkspace, for the same reason McpSessionContext is one: the build has
 * no mocking library, and a service that reached into FX could only be
 * exercised on the FX thread.
 */
public interface Launcher {
    ManagedSessionId start(Path worktree, AgentKind kind, String seedPrompt, ManagedSessionId forkedFrom);
}
```

- [ ] **Step 1: Write the failing tests**

```java
@Test
void forksOntoANewBranchAtTheOutgoingHead() throws Exception {
    ManagedAgentSession outgoing = sessionWithCommits("feat/work", 2);

    ManagedSessionId forked = service.forkBlocking(outgoing, AgentKind.CODEX);

    ManagedAgentSession created = sessions.byId(forked).orElseThrow();
    assertEquals(AgentKind.CODEX, created.agentKind());
    assertEquals(Optional.of(outgoing.id()), created.forkedFrom());
    assertNotEquals(outgoing.workingDirectory(), created.workingDirectory());
}

@Test
void leavesTheOutgoingSessionAndItsWorktreeUntouched() throws Exception {
    ManagedAgentSession outgoing = sessionWithCommits("feat/work", 1);
    String before = git(outgoing.workingDirectory(), "status", "--porcelain");

    service.forkBlocking(outgoing, AgentKind.CODEX);

    assertEquals(before, git(outgoing.workingDirectory(), "status", "--porcelain"));
    assertEquals(outgoing, sessions.byId(outgoing.id()).orElseThrow());
}

@Test
void carriesTheOutgoingDirtyTreeIntoTheFork() throws Exception {
    ManagedAgentSession outgoing = sessionWithCommits("feat/work", 1);
    Files.writeString(outgoing.workingDirectory().resolve("wip.txt"), "half done");

    ManagedSessionId forked = service.forkBlocking(outgoing, AgentKind.CODEX);

    Path forkedTree = sessions.byId(forked).orElseThrow().workingDirectory();
    assertEquals("half done", Files.readString(forkedTree.resolve("wip.txt")));
}

@Test
void forkingToTheSameAgentIsAllowed() throws Exception {
    ManagedAgentSession outgoing = sessionWithCommits("feat/work", 1);   // CLAUDE

    ManagedSessionId forked = service.forkBlocking(outgoing, AgentKind.CLAUDE);

    assertEquals(AgentKind.CLAUDE, sessions.byId(forked).orElseThrow().agentKind());
}

@Test
void theForkStartsWithNoBriefOfItsOwn() throws Exception {
    ManagedAgentSession outgoing = sessionWithCommits("feat/work", 1);
    briefs.put(brief(outgoing.id()));

    ManagedSessionId forked = service.forkBlocking(outgoing, AgentKind.CODEX);

    assertEquals(Optional.empty(), briefs.forSession(forked));
}

@Test
void seedsTheForkWithTheBriefWhenOneExists() throws Exception {
    ManagedAgentSession outgoing = sessionWithCommits("feat/work", 1);
    briefs.put(brief(outgoing.id()));   // goal: "Ship the fork gesture"

    service.forkBlocking(outgoing, AgentKind.CODEX);

    assertTrue(launcher.lastPrompt().orElseThrow().contains("Ship the fork gesture"));
}

@Test
void seedsTheForkAndSaysSoWhenNoBriefExists() throws Exception {
    ManagedAgentSession outgoing = sessionWithCommits("feat/work", 1);

    service.forkBlocking(outgoing, AgentKind.CODEX);

    assertTrue(launcher.lastPrompt().orElseThrow().contains("No handoff brief was recorded"));
}

@Test
void suffixesTheBranchNameWhenItCollides() throws Exception {
    ManagedAgentSession outgoing = sessionWithCommits("feat/work", 1);
    git(repoRoot, "branch", "feat/work-codex");   // the natural name is taken

    ManagedSessionId forked = service.forkBlocking(outgoing, AgentKind.CODEX);

    assertNotEquals("feat/work-codex", branchOf(sessions.byId(forked).orElseThrow()));
}

@Test
void removesTheWorktreeAndBranchWhenTheTransplantFails() throws Exception {
    ManagedAgentSession outgoing = sessionWithCommits("feat/work", 1);
    transplant.failNext();

    assertThrows(GitCommandFailedException.class, () -> service.forkBlocking(outgoing, AgentKind.CODEX));

    assertFalse(git(repoRoot, "worktree", "list").contains("feat/work-codex"));
    assertFalse(git(repoRoot, "branch", "--list", "feat/work-codex").contains("feat/work-codex"));
    assertEquals(0, launcher.startCount(), "no session may be started for a failed fork");
}

@Test
void forksFromTheBaseWhenTheBranchHasNoCommitsYet() throws Exception {
    ManagedAgentSession outgoing = sessionWithCommits("feat/work", 0);

    ManagedSessionId forked = service.forkBlocking(outgoing, AgentKind.CODEX);

    assertTrue(launcher.lastPrompt().orElseThrow().contains("forked from"));
    assertTrue(sessions.byId(forked).isPresent());
}
```

`transplant`, `launcher` and `briefs` are hand-written fakes in the test file
(there is no mocking library). `launcher` records `lastPrompt()`,
`startCount()`, and the `AgentKind` it was asked for.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.app.SessionForkServiceTest"`
Expected: FAIL — `SessionForkService` does not exist.

- [ ] **Step 3: Implement the fork, with rollback**

```java
package app.drydock.app;

/**
 * Forks a session onto a sibling worktree running a chosen agent.
 *
 * <p>Every switch is a fork. Nothing is switched in place, no live tab is
 * operated on, and the outgoing session, its branch and its worktree are
 * never written to -- so a fork that fails cannot cost work. The rollback
 * below exists for the one thing a failure can leave behind: a
 * half-populated destination.</p>
 */
public final class SessionForkService {

    public ManagedSessionId forkBlocking(ManagedAgentSession outgoing, AgentKind target) {
        Path repositoryRoot = repositoryRootOf(outgoing);
        Optional<String> head = resolveHead(outgoing.workingDirectory());   // empty: no commits yet
        String branch = availableBranchName(repositoryRoot, outgoing, target);
        Path directory = worktreeNaming.directoryFor(repositoryRoot, branch);

        Path created = gitStatusService.createWorktreeBlocking(
                repositoryRoot, directory, branch, head.or(() -> Optional.of(baseBranchOf(outgoing))));

        try {
            initSubmodules(created);
            transplant.transplantBlocking(outgoing.workingDirectory(), created);
            String seed = HandoffSeed.compose(briefs.forSession(outgoing.id()), factsFor(outgoing, branch));
            return launcher.start(created, target, seed, outgoing.id());
        } catch (RuntimeException e) {
            rollback(repositoryRoot, created, branch);
            throw e;
        }
    }

    /**
     * A fresh worktree's submodules are empty, and a successor that inherits a
     * repository which will not build has been handed a different problem than
     * the one it was briefed on. Local only -- the objects are already in the
     * shared {@code .git/modules}, so this never touches the network.
     */
    private void initSubmodules(Path worktree) {
        ProcessResult result = run(List.of(git().toString(), "-C", worktree.toString(),
                "submodule", "update", "--init", "--recursive"));
        if (result.exitCode() != 0) {
            throw new GitCommandFailedException(List.of("git", "submodule", "update", "--init"),
                    result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
    }

    /**
     * Best-effort: a rollback that itself fails must not mask the original
     * failure, which is the one the human needs to read.
     */
    private void rollback(Path repositoryRoot, Path worktree, String branch) {
        try {
            run(List.of(git().toString(), "-C", repositoryRoot.toString(),
                    "worktree", "remove", "--force", worktree.toString()));
            run(List.of(git().toString(), "-C", repositoryRoot.toString(), "branch", "-D", branch));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Could not roll back the failed fork at " + worktree + ": " + e);
        }
    }

    /** {@code <outgoing branch>-<agent>}, suffixed with -2, -3, ... until free. */
    private String availableBranchName(Path repositoryRoot, ManagedAgentSession outgoing, AgentKind target) {
        String base = branchOf(outgoing) + "-" + target.persistedName();
        if (!branchExists(repositoryRoot, base)) {
            return base;
        }
        for (int suffix = 2; suffix < 100; suffix++) {
            String candidate = base + "-" + suffix;
            if (!branchExists(repositoryRoot, candidate)) {
                return candidate;
            }
        }
        throw new GitCommandFailedException(List.of("git", "branch"), -1,
                "no free branch name near " + base);
    }
}
```

And the staleness query:

```java
/**
 * Counts the work done since the brief was written. A brief with no
 * writtenAtCommit was written when its session had no commits, so there is
 * no range to count from: every uncommitted file is the whole story.
 */
public HandoffStaleness stalenessBlocking(ManagedAgentSession session) {
    Optional<HandoffBrief> brief = briefs.forSession(session.id());
    if (brief.isEmpty()) {
        return HandoffStaleness.of(Optional.empty(), 0, 0);
    }
    Optional<String> since = brief.get().writtenAtCommit();
    if (since.isEmpty()) {
        return HandoffStaleness.of(brief, 0, countLines(gitOut(session, "status", "--porcelain")));
    }
    // --end-of-options: a stored commit-ish must never be parsed as a flag.
    int commits = parseCountOrZero(gitOut(session, "rev-list", "--count",
            "--end-of-options", since.get() + "..HEAD"));
    int files = countLines(gitOut(session, "diff", "--name-only", "--end-of-options", since.get()));
    return HandoffStaleness.of(brief, commits, files);
}
```

`gitOut` runs git in the session's working directory and returns stdout,
returning `""` on a non-zero exit — a commit that no longer exists (history
rewritten under the brief) must degrade to "not stale", not throw and break
the tab. `parseCountOrZero` and `countLines` are private helpers; a blank
string counts as zero lines.

Validate the composed branch name through the existing `BranchNames` helper
before using it, so a name derived from an agent-authored session title cannot
smuggle git refspec syntax.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.app.SessionForkServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/app/SessionForkService.java \
        app/src/test/java/app/drydock/app/SessionForkServiceTest.java
git commit -m "Forking a session mints a sibling worktree and carries the work into it"
```

---

### Task 8: The UI — banner, dialog, and the Fork gesture

**Files:**
- Create: `app/src/main/java/app/drydock/ui/HandoffBanner.java`
- Create: `app/src/main/java/app/drydock/ui/HandoffEditDialog.java`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java`
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java`
- Create: `app/src/test/java/app/drydock/ui/HandoffBannerTest.java`

**Interfaces:**
- Consumes: `HandoffStaleness` (Task 4), `HandoffBrief` (Task 1),
  `SessionForkService.fork` / `.staleness` (Task 7),
  `AgentRegistry.agents()` / `isAvailable` / `describeSearched` (existing).
- Produces: no new API for later tasks — this is the last task.

- [ ] **Step 1: Write the failing banner tests**

Follow the FX test style already in `app/src/test/java/app/drydock/ui/` (read
`ReviewNarrowLayoutTest` or `RailLayoutTest` first for how a scene is built in
a test). Test only what is assertable without pixels:

```java
@Test
void showsNothingWhenTheBriefIsCurrent() {
    HandoffBanner banner = new HandoffBanner();
    banner.update(HandoffStaleness.of(Optional.of(brief()), 0, 0), true);

    assertFalse(banner.isVisible());
}

@Test
void showsTheWorkDoneSinceTheBriefWasWritten() {
    HandoffBanner banner = new HandoffBanner();
    banner.update(HandoffStaleness.of(Optional.of(brief()), 9, 40), true);

    assertTrue(banner.isVisible());
    assertEquals("Brief written 9 commits and 40 changed files ago", banner.messageText());
}

@Test
void enablesRefreshWhenTheOutgoingSessionIsAlive() {
    HandoffBanner banner = new HandoffBanner();
    banner.update(HandoffStaleness.of(Optional.of(brief()), 1, 1), true);

    assertFalse(banner.refreshButton().isDisabled());
}

@Test
void disablesRefreshWithAReasonWhenTheSessionIsNotRunning() {
    HandoffBanner banner = new HandoffBanner();
    banner.update(HandoffStaleness.of(Optional.of(brief()), 1, 1), false);

    Button refresh = banner.refreshButton();
    assertTrue(refresh.isDisabled());
    // A Refresh that appears to work against a dead session is worse than none.
    assertTrue(refresh.getTooltip().getText().toLowerCase(Locale.ROOT).contains("not running"),
            refresh.getTooltip().getText());
}

@Test
void offersEditAndForkEvenWithNoBriefAtAll() {
    HandoffBanner banner = new HandoffBanner();
    banner.update(HandoffStaleness.of(Optional.empty(), 0, 0), false);

    assertTrue(banner.isVisible());
    assertFalse(banner.editButton().isDisabled());
    assertFalse(banner.forkButton().isDisabled());
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.ui.HandoffBannerTest"`
Expected: FAIL — `HandoffBanner` does not exist.

- [ ] **Step 3: Build the banner**

A `HBox` with a message `Label` and three buttons, `visible`/`managed` bound
to `shouldWarn()`. `update(HandoffStaleness, boolean sessionRunning)` sets the
message from `describe()` and disables *Refresh* with a tooltip naming the
reason when `sessionRunning` is false. *Edit* and *Fork* are never disabled —
they are exactly what a human needs when the session is dead.

Follow the existing chip/banner CSS classes rather than inline styles; check
how the review banners are styled and match them.

- [ ] **Step 4: Build the edit dialog**

`HandoffEditDialog` — one `TextArea` per slot, `goal` and `nextStep` marked
required with OK disabled while either is blank, and a character counter that
turns red past `PromptSafety.MAX_HANDOFF_SLOT_CHARS`. On OK, write the brief
with `author = HUMAN`, `writtenAt = Instant.now()` and the session's current
`HEAD`, and **do not** charge the session's MCP budget — that budget bounds an
agent, not a human.

- [ ] **Step 5: Wire Refresh**

*Refresh* writes a prompt into the outgoing session's terminal through the
same path a seeded prompt takes, asking it to call `session_handoff` now. It
is a request, not a command: nothing waits on it, and the banner clears only
when a brief actually lands — which it does through the normal state
republish, since the banner reads from `ApplicationState`.

- [ ] **Step 6: Wire the Fork gesture**

Add "Fork to…" to the session tab's context menu and the sidebar row's,
building the submenu from `AgentRegistry.agents()`. Unavailable agents are
shown **disabled** with `describeSearched()` as the tooltip, exactly as the
existing agent picker does. Selecting one calls `SessionForkService.fork(...)`
off the FX thread and opens the new tab on completion; on failure, show the
error rather than leaving the gesture silently dead.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.ui.*"`
Expected: PASS.

- [ ] **Step 8: Verify the visual state with a real window**

The banner's layout and the disabled-*Refresh* tooltip are visual state with
no headless-FX harness behind them. Per `docs/architecture.md`, drive the app
with a diag script and capture the in-app `shot:` scene snapshot (**not**
`screencapture`, which fails for an agent session hosted inside Drydock's own
terminal). Confirm by eye:

- the banner text is not truncated at a narrow tab width;
- *Refresh* is visibly disabled and its tooltip states the reason;
- the "Fork to…" submenu shows unavailable agents greyed with a tooltip.

Record in the implementation report exactly what was checked this way, and
state plainly that these three points carry no automated test.

- [ ] **Step 9: Run the full suite**

Run: `./gradlew :app:test`
Expected: PASS. This takes 14–20 minutes; run it in the foreground.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/app/drydock/ui/ app/src/test/java/app/drydock/ui/
git commit -m "The human can refresh, edit, or fork from a stale handoff brief"
```

---

---

## Blocking prerequisite: Codex and Pi cannot reach drydock's MCP server

`SessionManager.mcpConfigFor` (`app/src/main/java/app/drydock/app/SessionManager.java:199-203`)
returns empty unless `provider.supportsMcpConfig()`, and both
`PiAgentProvider:87` and `CodexAgentProvider:87` return `false` — "No
`--mcp-config` equivalent: Drydock's per-session MCP file is Claude-specific."

So a Pi or Codex session never has a token minted and never receives an MCP
config. Drydock's tools are unreachable from those harnesses, which is why
their tabs do not rename themselves. It is not instruction-following.

**Consequence for this plan:** Tasks 1–7 are sound, but `session_handoff` is
callable only from Claude sessions. The living brief would therefore work on
the first hop and break on the second — a Claude session forks to Codex, and
the Codex session can never write a brief for the *next* hop. Shipping Tasks
1–8 alone yields a one-way door.

### What the two CLIs actually offer (investigated 2026-08-12)

Against the installed binaries — Codex at `/usr/local/bin/codex`, Pi
`@earendil-works/pi-coding-agent` 0.84.1.

**Codex: reachable, per-session, with one contained server change.**

- `codex -c/--config <key=value>` overrides any config value per invocation
  using a dotted TOML path, so `mcp_servers.drydock` can be set **on the
  command line** — no mutation of the user's `~/.codex/config.toml`, which
  keeps the per-session shape drydock already relies on.
- `codex mcp add <NAME> --url <URL>` confirms streamable-HTTP MCP servers are
  supported, which is what drydock runs (`McpConfigWriter:117-119` writes
  `type: "http"`, `url`, `headers`).
- **The blocker is the auth header, and it is small.** Codex offers only
  `--bearer-token-env-var <ENV_VAR>`, i.e. `Authorization: Bearer <token>`.
  Drydock sends its token in a custom header, `X-Drydock-Session-Token`
  (`McpConfigWriter.java:66`). For Codex to authenticate, drydock's MCP server
  must **also** accept `Authorization: Bearer` carrying the same session
  token. That is a contained change on the server side.
- Trade-off to decide deliberately: the token then lives in the session
  process's environment rather than in an owner-readable file. It is not on
  disk, but it is visible to anything that can read that process's
  environment.

**Pi: no MCP by design, and it will not arrive.**

Pi's own README states it outright:

> **No MCP.** Build CLI tools with READMEs (see Skills), or build an extension
> that adds MCP support.

and `docs/usage.md`: *"It intentionally does not include built-in MCP,
sub-agents, permission popups, plan mode, to-dos, or background bash. You can
build or install those workflows as extensions or packages."* The code agrees —
no `mcp` dependency, no config key, no flag, and the only occurrence of the
string in `dist/` is a comment about "MCP bridges" as a kind of extension.

This is a design stance, not a gap, so waiting for it is not a plan.

**But Pi documents the route drydock should take, and it is cheaper than MCP.**
Pi's stated alternative is *CLI tools with READMEs*, surfaced through its
skills mechanism (`--skill <path>`, plus discovery under
`~/.pi/agent/` and `.pi/`). So the handoff reaches Pi as:

- a small `drydock handoff` CLI that writes the same `HandoffBrief` through the
  same path `session_handoff` uses, and
- a skill file drydock passes with `--skill`, telling the agent when to call it.

Authentication gets *simpler*, not harder: drydock spawns the process, so the
CLI can take its session token from an environment variable or a file drydock
wrote, with no HTTP auth negotiation at all.

Pi also offers `--append-system-prompt <text>` — a direct instruction lever
needing neither MCP nor a skill — and `--session-id`, which drydock already
uses.

Note the CLI route is **harness-agnostic**: any agent that can run bash can
call it, so it doubles as the fallback for every future provider that does not
speak MCP.

### Consequence: writing a brief is not one mechanism

Three harnesses reach drydock three ways — a config file (Claude), command-line
config overrides (Codex), a CLI plus a skill (Pi) — and only the first exists
today. Until they do, a Pi session cannot write a brief, so forking *from* Pi
falls to the floor the design already specifies: drydock-derived facts plus the
human's *Edit* verb, with the seed stating outright that no brief was recorded.
Forking *to* Pi is unaffected, since a seed is just a prompt. Pi is a
**degraded source and a full destination** until the CLI route lands, and the
UI should say so rather than let it be discovered.

### Recommended order

1. **Codex.** Accept `Authorization: Bearer` in drydock's MCP server alongside
   `X-Drydock-Session-Token`, and teach `CodexAgentProvider` to emit the `-c`
   flags. Note this is not `--mcp-config`, so `AgentProvider.supportsMcpConfig`
   is the wrong name for the capability once two providers reach the server by
   different mechanisms — expect to generalize it to something like
   "how this provider is given drydock's tools".
2. **Tasks 1–8**, which at that point work fully on Claude and Codex and
   degrade honestly on Pi.
3. **The `drydock handoff` CLI + Pi skill.** Worth doing on its own merits even
   before Pi needs it, because it is harness-agnostic: it is the fallback for
   every provider that will not speak MCP, and Pi is unlikely to be the last.

Do **not** start Task 1 without deciding whether step 1 lands first.

## Notes for the implementer

**The spec is the authority.** Where this plan and
`docs/superpowers/specs/2026-08-12-harness-handoff-design.md` disagree, the
spec wins — raise the conflict rather than picking one.

**Three invariants the tests exist to protect.** If a change makes one of
these awkward, the change is wrong:

1. The outgoing session, its branch and its worktree are never written to.
2. A failed fork leaves no worktree, no branch, and no started session.
3. Oversize or malformed agent input is refused with a message naming the
   slot and the limit — never truncated, never silently accepted.

**What is deliberately absent.** No transcript is parsed, no rollout format is
read, and no request passes through drydock. If a task seems to need one of
those, the design has been misread — see "What was cut" in the spec.
