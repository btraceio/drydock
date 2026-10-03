package app.drydock.ui.review;

import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourStep;

/** What the step panel shows for the current step. */
record StepView(TourStep step, int number, int total, StepProgress progress) {
}
