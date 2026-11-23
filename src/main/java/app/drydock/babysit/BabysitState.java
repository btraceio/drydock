package app.drydock.babysit;

/** Where a babysitting engagement stands; persisted by {@link #name()}, so never rename a constant. */
public enum BabysitState {
    WATCHING,
    ACTING,
    ESCALATED,
    STOPPED
}
