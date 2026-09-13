package com.chalkline.support;

import com.chalkline.domain.Organisation;

import java.util.List;

/**
 * The shape of one organisation's weekly grid: which days are columns and
 * which time slots are rows.
 *
 * Per-organisation rather than global, because a business school running
 * evening classes and a science faculty starting at 08:00 are both customers.
 */
public record ScheduleGrid(List<String> days, List<String> slots) {

    public static ScheduleGrid of(Organisation organisation) {
        return new ScheduleGrid(organisation.getDayList(), organisation.getSlotList());
    }

    public int dayCount() {
        return days.size();
    }

    public int slotCount() {
        return slots.size();
    }

    public boolean isValidDay(int dayIndex) {
        return dayIndex >= 0 && dayIndex < days.size();
    }

    public boolean isValidSlot(int slotIndex) {
        return slotIndex >= 0 && slotIndex < slots.size();
    }

    /** Falls back to a readable placeholder if the configured list shrank under existing data. */
    public String dayName(int dayIndex) {
        return isValidDay(dayIndex) ? days.get(dayIndex) : "Day " + (dayIndex + 1);
    }

    public String slotName(int slotIndex) {
        return isValidSlot(slotIndex) ? slots.get(slotIndex) : "Slot " + (slotIndex + 1);
    }

    /**
     * The start time of a slot, parsed out of its label ("09:00 - 10:00").
     * Used to turn the grid into real calendar events. Returns null when the
     * label is not a time range, which is allowed -- labels are free text.
     */
    public String startTime(int slotIndex) {
        return timePart(slotName(slotIndex), 0);
    }

    public String endTime(int slotIndex) {
        return timePart(slotName(slotIndex), 1);
    }

    private String timePart(String label, int index) {
        String[] parts = label.split("-");
        if (parts.length != 2) {
            return null;
        }
        String candidate = parts[index].trim();
        return candidate.matches("\\d{1,2}:\\d{2}") ? candidate : null;
    }
}
