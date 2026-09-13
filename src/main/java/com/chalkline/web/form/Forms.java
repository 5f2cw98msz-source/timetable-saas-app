package com.chalkline.web.form;

import com.chalkline.domain.Role;
import com.chalkline.domain.ShareLink;

/**
 * Form backing objects, grouped in one file because each is a handful of
 * fields with no behaviour. Validation lives in the services, so there is one
 * place where a rule can be found rather than two that can disagree.
 */
public final class Forms {

    private Forms() {
    }

    /** Sign up a new institution and its first administrator. */
    public static class SignUp {
        private String organisationName = "";
        private String displayName = "";
        private String email = "";
        private String password = "";

        public String getOrganisationName() { return organisationName; }
        public void setOrganisationName(String v) { this.organisationName = v; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }
        public String getEmail() { return email; }
        public void setEmail(String v) { this.email = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
    }

    /** A lecturer joining an institution that already exists. */
    public static class Join {
        private String displayName = "";
        private String email = "";
        private String password = "";

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }
        public String getEmail() { return email; }
        public void setEmail(String v) { this.email = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
    }

    public static class Slot {
        private int day;
        private int slot;
        private String lecturer = "";
        private Long courseId;
        private Long roomId;

        public int getDay() { return day; }
        public void setDay(int v) { this.day = v; }
        public int getSlot() { return slot; }
        public void setSlot(int v) { this.slot = v; }
        public String getLecturer() { return lecturer; }
        public void setLecturer(String v) { this.lecturer = v; }
        public Long getCourseId() { return courseId; }
        public void setCourseId(Long v) { this.courseId = v; }
        public Long getRoomId() { return roomId; }
        public void setRoomId(Long v) { this.roomId = v; }
    }

    public static class Password {
        private String currentPassword = "";
        private String newPassword = "";
        private String confirmPassword = "";

        public String getCurrentPassword() { return currentPassword; }
        public void setCurrentPassword(String v) { this.currentPassword = v; }
        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String v) { this.newPassword = v; }
        public String getConfirmPassword() { return confirmPassword; }
        public void setConfirmPassword(String v) { this.confirmPassword = v; }
    }

    public static class Profile {
        private String displayName = "";
        private boolean notifyOnChange;

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }
        public boolean isNotifyOnChange() { return notifyOnChange; }
        public void setNotifyOnChange(boolean v) { this.notifyOnChange = v; }
    }

    public static class OrganisationProfile {
        private String name = "";
        private String accentColour = "";

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getAccentColour() { return accentColour; }
        public void setAccentColour(String v) { this.accentColour = v; }
    }

    public static class Scheduling {
        private String days = "";
        private String slots = "";
        private boolean preventRoomClashes = true;
        private boolean allowSelfRegistration = true;

        public String getDays() { return days; }
        public void setDays(String v) { this.days = v; }
        public String getSlots() { return slots; }
        public void setSlots(String v) { this.slots = v; }
        public boolean isPreventRoomClashes() { return preventRoomClashes; }
        public void setPreventRoomClashes(boolean v) { this.preventRoomClashes = v; }
        public boolean isAllowSelfRegistration() { return allowSelfRegistration; }
        public void setAllowSelfRegistration(boolean v) { this.allowSelfRegistration = v; }
    }

    public static class Member {
        private String email = "";
        private String displayName = "";
        private String password = "";
        private Role role = Role.LECTURER;

        public String getEmail() { return email; }
        public void setEmail(String v) { this.email = v; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
        public Role getRole() { return role; }
        public void setRole(Role v) { this.role = v; }
    }

    public static class RoomEntry {
        private String name = "";
        private Integer capacity;
        private String facilities = "";

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public Integer getCapacity() { return capacity; }
        public void setCapacity(Integer v) { this.capacity = v; }
        public String getFacilities() { return facilities; }
        public void setFacilities(String v) { this.facilities = v; }
    }

    public static class CourseEntry {
        private String code = "";
        private String name = "";
        private Integer expectedStudents;

        public String getCode() { return code; }
        public void setCode(String v) { this.code = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public Integer getExpectedStudents() { return expectedStudents; }
        public void setExpectedStudents(Integer v) { this.expectedStudents = v; }
    }

    public static class Share {
        private String label = "";
        private ShareLink.Scope scope = ShareLink.Scope.WHOLE_ORGANISATION;
        private String lecturerEmail = "";

        public String getLabel() { return label; }
        public void setLabel(String v) { this.label = v; }
        public ShareLink.Scope getScope() { return scope; }
        public void setScope(ShareLink.Scope v) { this.scope = v; }
        public String getLecturerEmail() { return lecturerEmail; }
        public void setLecturerEmail(String v) { this.lecturerEmail = v; }
    }
}
