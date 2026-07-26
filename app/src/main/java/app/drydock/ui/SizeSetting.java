package app.drydock.ui;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * One font-size setting the {@link SettingsModal} drives: where the slider's
 * opening value comes from, how a size is applied live, and how a size is
 * persisted. Owning the apply/persist sequencing here -- rather than in the
 * modal's slider listener and the application's anonymous {@link
 * SettingsModal.Settings} -- is what makes it testable without a JavaFX
 * toolkit (see {@code SizeSettingTest}).
 *
 * <p>{@link #persist} is handed the value the user chose, never {@link
 * #appliedSize}. The distinction is the whole point of this type: applying a
 * size can complete asynchronously (a first visit to a size regenerates the
 * stylesheet or the terminal config off-thread), while a commit can land in
 * the very same FX event as the apply that triggered it -- an arrow key or a
 * track click never sets {@code Slider.valueChanging}, so there is no drag
 * span to defer the commit past. A commit that read the applied state back
 * would then store the previous size, silently, until the next launch. Making
 * the persisted value an argument removes the ordering assumption entirely:
 * what the slider shows and what gets stored are the same number by
 * construction.</p>
 *
 * @param appliedSize the currently applied size, read once when the modal
 *                    builds the row; not consulted afterwards
 * @param applyLive   applies a size to the live UI, possibly completing
 *                    asynchronously; called on every slider tick, so it must
 *                    not block the FX thread
 * @param persist     writes a size to persistent state; called once per
 *                    discrete change or per finished drag, never per tick
 */
public record SizeSetting(DoubleSupplier appliedSize, DoubleConsumer applyLive, DoubleConsumer persist) {

    /** The size to show when the modal opens. */
    public double current() {
        return appliedSize.getAsDouble();
    }

    /**
     * One {@code Slider.valueProperty} tick. While a drag is in progress the
     * size is only applied -- persisting per tick would be pointless disk
     * traffic, and {@link #dragEnded} covers the release. A discrete change
     * (arrow key, or a click landing straight on a value) never reports a
     * drag, so it applies and persists in one go, which is correct: there is
     * no burst to debounce.
     */
    public void changed(double size, boolean dragging) {
        applyLive.accept(size);
        if (!dragging) {
            persist.accept(size);
        }
    }

    /** The end of a drag: persist where the slider came to rest. */
    public void dragEnded(double size) {
        persist.accept(size);
    }
}
