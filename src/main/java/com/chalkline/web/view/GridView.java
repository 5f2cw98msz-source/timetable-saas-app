package com.chalkline.web.view;

import java.util.List;

/**
 * Everything the timetable page needs, prepared in the controller so the
 * template contains no logic beyond looping.
 */
public record GridView(
        String scopeLabel,
        String scopeHint,

        /** False for the combined overview, which is read-only. */
        boolean editable,

        /** Email of the lecturer being shown; null in the combined view. */
        String selectedLecturer,

        boolean combined,

        List<LecturerOption> lecturers,
        List<String> days,
        List<RowView> rows,

        int classCount
) {

    public record LecturerOption(String email, String displayName, boolean selected) {}

    public record RowView(int slotIndex, String label, List<CellView> cells) {}

    public record CellView(int dayIndex, int slotIndex, List<EntryView> entries) {
        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    public record EntryView(
            Long id,
            String courseCode,
            String courseName,
            String colour,
            String roomName,
            String lecturerName,
            String lecturerEmail,

            /**
             * Set when the course expects more students than the room seats.
             * Shown as a quiet warning rather than blocking the booking --
             * the person scheduling usually knows something we do not.
             */
            String capacityWarning
    ) {
        public boolean hasCapacityWarning() {
            return capacityWarning != null;
        }
    }
}
