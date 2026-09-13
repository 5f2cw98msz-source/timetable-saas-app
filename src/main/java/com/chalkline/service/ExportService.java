package com.chalkline.service;

import com.chalkline.domain.Organisation;
import com.chalkline.domain.TimetableEntry;
import com.chalkline.support.ScheduleGrid;
import org.springframework.stereotype.Service;

import java.util.List;

/** Timetables as CSV, for spreadsheets and for anything else that reads them. */
@Service
public class ExportService {

    public String toCsv(Organisation organisation, List<TimetableEntry> entries) {
        ScheduleGrid grid = ScheduleGrid.of(organisation);

        StringBuilder csv = new StringBuilder("Day,Start,End,Course code,Course name,Room,Lecturer,Email\n");
        for (TimetableEntry entry : entries) {
            csv.append(cell(grid.dayName(entry.getDayIndex()))).append(',')
               .append(cell(grid.startTime(entry.getSlotIndex()))).append(',')
               .append(cell(grid.endTime(entry.getSlotIndex()))).append(',')
               .append(cell(entry.getCourse().getCode())).append(',')
               .append(cell(entry.getCourse().getName())).append(',')
               .append(cell(entry.getRoom().getName())).append(',')
               .append(cell(entry.getLecturer().getDisplayName())).append(',')
               .append(cell(entry.getLecturer().getEmail())).append('\n');
        }
        return csv.toString();
    }

    /**
     * Quotes a value for CSV.
     *
     * The leading apostrophe on anything starting with = + - or @ stops
     * Excel and Sheets treating the cell as a formula. A course called
     * "=cmd" should be text, not something a spreadsheet tries to run.
     */
    private String cell(String value) {
        if (value == null) {
            return "";
        }
        String safe = value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            safe = "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
