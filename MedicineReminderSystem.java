import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Medicine Reminder System
 * ------------------------
 * A console-based application that stores medicine schedules, shows what is due
 * today, records taken/skipped doses, tracks remaining stock and warns when
 * stock is low or a course is about to end.
 *
 * Data is saved in two CSV files (medicines.csv and doses.csv) so it persists
 * between runs. Requires Java 8 or above. No external libraries needed.
 */
public class MedicineReminderSystem {

    // ---------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------
    private static final int LOW_STOCK_DAYS = 3;       // warn when supply <= 3 days
    private static final int COURSE_END_WARN_DAYS = 2; // warn when course ends within 2 days
    private static final int DUE_BEFORE_MINUTES = 30;  // "due now" starts 30 min before the dose
    private static final int DUE_AFTER_MINUTES = 60;   // still "due now" up to 60 min after
    private static final DateTimeFormatter TIME_INPUT = DateTimeFormatter.ofPattern("H:mm");

    // ---------------------------------------------------------------
    // Dose status
    // ---------------------------------------------------------------
    enum DoseStatus {
        TAKEN, SKIPPED, MISSED, DUE_NOW, UPCOMING
    }

    // ---------------------------------------------------------------
    // Medicine model
    // ---------------------------------------------------------------
    static class Medicine {
        private final int id;
        private final String name;
        private final String dosage;
        private final int pillsPerDose;
        private final List<LocalTime> times;
        private int stock;
        private final LocalDate startDate;
        private final LocalDate endDate;

        Medicine(int id, String name, String dosage, int pillsPerDose,
                 List<LocalTime> times, int stock, LocalDate startDate, LocalDate endDate) {
            this.id = id;
            this.name = name;
            this.dosage = dosage;
            this.pillsPerDose = pillsPerDose;
            this.times = times;
            this.stock = stock;
            this.startDate = startDate;
            this.endDate = endDate;
        }

        int getId() { return id; }
        String getName() { return name; }
        String getDosage() { return dosage; }
        int getPillsPerDose() { return pillsPerDose; }
        List<LocalTime> getTimes() { return times; }
        int getStock() { return stock; }
        LocalDate getStartDate() { return startDate; }
        LocalDate getEndDate() { return endDate; }

        void setStock(int stock) { this.stock = stock; }

        boolean isActiveOn(LocalDate date) {
            return !date.isBefore(startDate) && !date.isAfter(endDate);
        }

        int daysOfSupply() {
            int perDay = pillsPerDose * times.size();
            return perDay == 0 ? 0 : stock / perDay;
        }

        String timesText() {
            return times.stream().map(LocalTime::toString).collect(Collectors.joining(" "));
        }

        String toCsv() {
            String joinedTimes = times.stream().map(LocalTime::toString).collect(Collectors.joining(";"));
            return id + "," + clean(name) + "," + clean(dosage) + "," + pillsPerDose + ","
                    + joinedTimes + "," + stock + "," + startDate + "," + endDate;
        }

        static Medicine fromCsv(String line) {
            String[] p = line.split(",");
            if (p.length != 8) {
                throw new IllegalArgumentException("Invalid medicine record: " + line);
            }
            List<LocalTime> times = new ArrayList<>();
            for (String t : p[4].split(";")) {
                times.add(LocalTime.parse(t.trim()));
            }
            return new Medicine(
                    Integer.parseInt(p[0].trim()), p[1].trim(), p[2].trim(),
                    Integer.parseInt(p[3].trim()), times, Integer.parseInt(p[5].trim()),
                    LocalDate.parse(p[6].trim()), LocalDate.parse(p[7].trim()));
        }

        private static String clean(String s) {
            return s.replace(",", " ").trim();
        }
    }

    // ---------------------------------------------------------------
    // Dose record (history log)
    // ---------------------------------------------------------------
    static class DoseRecord {
        private final int medicineId;
        private final LocalDate date;
        private final LocalTime time;
        private final DoseStatus status;

        DoseRecord(int medicineId, LocalDate date, LocalTime time, DoseStatus status) {
            this.medicineId = medicineId;
            this.date = date;
            this.time = time;
            this.status = status;
        }

        int getMedicineId() { return medicineId; }
        LocalDate getDate() { return date; }
        LocalTime getTime() { return time; }
        DoseStatus getStatus() { return status; }

        String toCsv() {
            return medicineId + "," + date + "," + time + "," + status;
        }

        static DoseRecord fromCsv(String line) {
            String[] p = line.split(",");
            if (p.length != 4) {
                throw new IllegalArgumentException("Invalid dose record: " + line);
            }
            return new DoseRecord(
                    Integer.parseInt(p[0].trim()),
                    LocalDate.parse(p[1].trim()),
                    LocalTime.parse(p[2].trim()),
                    DoseStatus.valueOf(p[3].trim()));
        }
    }

    // ---------------------------------------------------------------
    // One line of today's schedule
    // ---------------------------------------------------------------
    static class ScheduleEntry {
        final Medicine medicine;
        final LocalTime time;
        final DoseStatus status;

        ScheduleEntry(Medicine medicine, LocalTime time, DoseStatus status) {
            this.medicine = medicine;
            this.time = time;
            this.status = status;
        }

        boolean isPending() {
            return status != DoseStatus.TAKEN && status != DoseStatus.SKIPPED;
        }
    }

    // ---------------------------------------------------------------
    // Reminder manager (business logic + persistence)
    // ---------------------------------------------------------------
    static class ReminderManager {
        private final List<Medicine> medicines = new ArrayList<>();
        private final List<DoseRecord> records = new ArrayList<>();
        private final Path medicinePath;
        private final Path dosePath;
        private int nextId = 1;

        ReminderManager(String medicineFile, String doseFile) {
            this.medicinePath = Paths.get(medicineFile);
            this.dosePath = Paths.get(doseFile);
            load();
        }

        List<Medicine> getMedicines() {
            return medicines.stream()
                    .sorted(Comparator.comparing(Medicine::getName, String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toList());
        }

        Medicine findById(int id) {
            for (Medicine m : medicines) {
                if (m.getId() == id) return m;
            }
            return null;
        }

        Medicine addMedicine(String name, String dosage, int pillsPerDose,
                             List<LocalTime> times, int stock, int durationDays) {
            LocalDate start = LocalDate.now();
            LocalDate end = start.plusDays(durationDays - 1L);
            Medicine m = new Medicine(nextId++, name, dosage, pillsPerDose, times, stock, start, end);
            medicines.add(m);
            saveAll();
            return m;
        }

        boolean removeMedicine(int id) {
            boolean removed = medicines.removeIf(m -> m.getId() == id);
            if (removed) {
                records.removeIf(r -> r.getMedicineId() == id);
                saveAll();
            }
            return removed;
        }

        boolean refill(int id, int pills) {
            Medicine m = findById(id);
            if (m == null) return false;
            m.setStock(m.getStock() + pills);
            saveAll();
            return true;
        }

        private DoseRecord findRecord(int medicineId, LocalDate date, LocalTime time) {
            for (DoseRecord r : records) {
                if (r.getMedicineId() == medicineId && r.getDate().equals(date) && r.getTime().equals(time)) {
                    return r;
                }
            }
            return null;
        }

        /** Builds today's schedule, sorted by time, with a computed status for each dose. */
        List<ScheduleEntry> todaySchedule() {
            LocalDate today = LocalDate.now();
            LocalTime now = LocalTime.now();
            List<ScheduleEntry> list = new ArrayList<>();
            for (Medicine m : medicines) {
                if (!m.isActiveOn(today)) continue;
                for (LocalTime t : m.getTimes()) {
                    DoseRecord rec = findRecord(m.getId(), today, t);
                    DoseStatus status;
                    if (rec != null) {
                        status = rec.getStatus();
                    } else {
                        long diff = ChronoUnit.MINUTES.between(now, t); // positive = in the future
                        if (diff > DUE_BEFORE_MINUTES) status = DoseStatus.UPCOMING;
                        else if (diff >= -DUE_AFTER_MINUTES) status = DoseStatus.DUE_NOW;
                        else status = DoseStatus.MISSED;
                    }
                    list.add(new ScheduleEntry(m, t, status));
                }
            }
            list.sort(Comparator.comparing((ScheduleEntry e) -> e.time)
                    .thenComparing(e -> e.medicine.getName(), String.CASE_INSENSITIVE_ORDER));
            return list;
        }

        /** Records a dose as TAKEN or SKIPPED. Returns an error message, or null on success. */
        String recordDose(ScheduleEntry entry, DoseStatus status) {
            LocalDate today = LocalDate.now();
            if (findRecord(entry.medicine.getId(), today, entry.time) != null) {
                return "This dose is already recorded.";
            }
            if (status == DoseStatus.TAKEN) {
                if (entry.medicine.getStock() < entry.medicine.getPillsPerDose()) {
                    return "Not enough stock left. Refill this medicine first.";
                }
                entry.medicine.setStock(entry.medicine.getStock() - entry.medicine.getPillsPerDose());
            }
            records.add(new DoseRecord(entry.medicine.getId(), today, entry.time, status));
            saveAll();
            return null;
        }

        /** Percentage of scheduled doses (up to now) that were marked TAKEN. -1 if nothing was due yet. */
        double adherence(Medicine m) {
            LocalDateTime now = LocalDateTime.now();
            LocalDate last = m.getEndDate().isBefore(now.toLocalDate()) ? m.getEndDate() : now.toLocalDate();
            int expected = 0;
            for (LocalDate d = m.getStartDate(); !d.isAfter(last); d = d.plusDays(1)) {
                for (LocalTime t : m.getTimes()) {
                    if (d.isBefore(now.toLocalDate()) || t.isBefore(now.toLocalTime())) {
                        expected++;
                    }
                }
            }
            if (expected == 0) return -1;
            int taken = 0;
            for (DoseRecord r : records) {
                if (r.getMedicineId() == m.getId() && r.getStatus() == DoseStatus.TAKEN) taken++;
            }
            return Math.min(100.0, taken * 100.0 / expected);
        }

        int countByStatus(Medicine m, DoseStatus status) {
            int c = 0;
            for (DoseRecord r : records) {
                if (r.getMedicineId() == m.getId() && r.getStatus() == status) c++;
            }
            return c;
        }

        /** Collects all current alert messages. */
        List<String> getAlerts() {
            List<String> alerts = new ArrayList<>();
            LocalDate today = LocalDate.now();

            for (ScheduleEntry e : todaySchedule()) {
                if (e.status == DoseStatus.MISSED) {
                    alerts.add("MISSED DOSE : " + e.medicine.getName() + " (" + e.medicine.getDosage()
                            + ") at " + e.time);
                } else if (e.status == DoseStatus.DUE_NOW) {
                    alerts.add("DUE NOW     : " + e.medicine.getName() + " (" + e.medicine.getDosage()
                            + ") at " + e.time);
                }
            }

            for (Medicine m : getMedicines()) {
                if (m.getEndDate().isBefore(today)) {
                    alerts.add("COURSE ENDED: " + m.getName() + " finished on " + m.getEndDate()
                            + " (you can remove it)");
                    continue;
                }
                if (!m.isActiveOn(today)) continue;

                if (m.getStock() < m.getPillsPerDose()) {
                    alerts.add("OUT OF STOCK: " + m.getName() + " - refill needed");
                } else if (m.daysOfSupply() <= LOW_STOCK_DAYS) {
                    alerts.add("LOW STOCK   : " + m.getName() + " - about " + m.daysOfSupply()
                            + " day(s) of supply left (" + m.getStock() + " pills)");
                }

                long daysToEnd = ChronoUnit.DAYS.between(today, m.getEndDate());
                if (daysToEnd <= COURSE_END_WARN_DAYS) {
                    alerts.add("COURSE ENDS : " + m.getName()
                            + (daysToEnd == 0 ? " ends today" : " ends in " + daysToEnd + " day(s)"));
                }
            }
            return alerts;
        }

        // ---------- persistence ----------
        private void load() {
            try {
                if (Files.exists(medicinePath)) {
                    for (String line : Files.readAllLines(medicinePath, StandardCharsets.UTF_8)) {
                        if (line.trim().isEmpty()) continue;
                        try {
                            Medicine m = Medicine.fromCsv(line);
                            medicines.add(m);
                            nextId = Math.max(nextId, m.getId() + 1);
                        } catch (RuntimeException ex) {
                            System.out.println("Skipping bad medicine record -> " + ex.getMessage());
                        }
                    }
                }
                if (Files.exists(dosePath)) {
                    for (String line : Files.readAllLines(dosePath, StandardCharsets.UTF_8)) {
                        if (line.trim().isEmpty()) continue;
                        try {
                            records.add(DoseRecord.fromCsv(line));
                        } catch (RuntimeException ex) {
                            System.out.println("Skipping bad dose record -> " + ex.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("Could not read data files: " + e.getMessage());
            }
        }

        private void saveAll() {
            try {
                Files.write(medicinePath,
                        medicines.stream().map(Medicine::toCsv).collect(Collectors.toList()),
                        StandardCharsets.UTF_8);
                Files.write(dosePath,
                        records.stream().map(DoseRecord::toCsv).collect(Collectors.toList()),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.out.println("Could not save data: " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------
    // Console UI
    // ---------------------------------------------------------------
    private static final Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        ReminderManager manager = new ReminderManager("medicines.csv", "doses.csv");

        System.out.println("=========================================");
        System.out.println("        MEDICINE REMINDER SYSTEM         ");
        System.out.println("=========================================");

        showAlerts(manager);

        boolean running = true;
        while (running) {
            printMenu();
            int choice = readInt("Enter choice: ");
            switch (choice) {
                case 1: addMedicine(manager); break;
                case 2: showTodaySchedule(manager); break;
                case 3: markDose(manager); break;
                case 4: showAlerts(manager); break;
                case 5: listMedicines(manager); break;
                case 6: refillStock(manager); break;
                case 7: adherenceReport(manager); break;
                case 8: removeMedicine(manager); break;
                case 0:
                    running = false;
                    System.out.println("Stay healthy! Data saved.");
                    break;
                default:
                    System.out.println("Invalid choice. Try again.");
            }
        }
    }

    private static void printMenu() {
        System.out.println("\n---------------- MENU ----------------");
        System.out.println("1. Add medicine");
        System.out.println("2. Today's schedule");
        System.out.println("3. Mark a dose (taken / skipped)");
        System.out.println("4. View alerts");
        System.out.println("5. View all medicines");
        System.out.println("6. Refill stock");
        System.out.println("7. Adherence report");
        System.out.println("8. Remove medicine");
        System.out.println("0. Exit");
        System.out.println("--------------------------------------");
    }

    private static void addMedicine(ReminderManager m) {
        System.out.println("\n-- Add New Medicine --");
        String name = readNonEmpty("Medicine name: ");
        String dosage = readNonEmpty("Strength / dosage (e.g. 500mg): ");
        int pills = readPositiveInt("Pills per dose: ");
        List<LocalTime> times = readTimes("Dose times, comma separated (e.g. 08:00, 14:00, 21:00): ");
        int stock = readPositiveInt("Pills currently in stock: ");
        int days = readPositiveInt("Course length in days: ");

        Medicine med = m.addMedicine(name, dosage, pills, times, stock, days);
        System.out.println("Added " + med.getName() + " (ID " + med.getId() + "), course ends on "
                + med.getEndDate() + ".");
        if (med.getStock() < med.getPillsPerDose() * med.getTimes().size() * days) {
            System.out.println("Note: current stock will not cover the whole course.");
        }
    }

    private static void showTodaySchedule(ReminderManager m) {
        List<ScheduleEntry> schedule = m.todaySchedule();
        System.out.println("\n== TODAY'S SCHEDULE (" + LocalDate.now() + ") ==");
        if (schedule.isEmpty()) {
            System.out.println("No doses scheduled for today.");
            return;
        }
        String fmt = "%-4s %-7s %-20s %-10s %-6s %-10s%n";
        System.out.printf(fmt, "NO.", "TIME", "MEDICINE", "DOSAGE", "PILLS", "STATUS");
        System.out.println(repeat('-', 62));
        int i = 1;
        for (ScheduleEntry e : schedule) {
            System.out.printf(fmt, i++, e.time, trim(e.medicine.getName(), 20),
                    trim(e.medicine.getDosage(), 10), e.medicine.getPillsPerDose(), e.status);
        }
    }

    private static void markDose(ReminderManager m) {
        List<ScheduleEntry> pending = m.todaySchedule().stream()
                .filter(ScheduleEntry::isPending)
                .collect(Collectors.toList());
        if (pending.isEmpty()) {
            System.out.println("\nNo pending doses for today.");
            return;
        }
        System.out.println("\n== PENDING DOSES ==");
        for (int i = 0; i < pending.size(); i++) {
            ScheduleEntry e = pending.get(i);
            System.out.printf("%d. %s  %s (%s)  [%s]%n", i + 1, e.time, e.medicine.getName(),
                    e.medicine.getDosage(), e.status);
        }
        int pick = readInt("Select dose number (0 to cancel): ");
        if (pick == 0) return;
        if (pick < 1 || pick > pending.size()) {
            System.out.println("Invalid selection.");
            return;
        }
        int action = readInt("1 = Taken, 2 = Skipped: ");
        DoseStatus status;
        if (action == 1) status = DoseStatus.TAKEN;
        else if (action == 2) status = DoseStatus.SKIPPED;
        else {
            System.out.println("Invalid option.");
            return;
        }
        String error = m.recordDose(pending.get(pick - 1), status);
        System.out.println(error == null ? "Dose recorded as " + status + "." : error);
    }

    private static void showAlerts(ReminderManager m) {
        List<String> alerts = m.getAlerts();
        System.out.println("\n== ALERTS ==");
        if (alerts.isEmpty()) {
            System.out.println("No alerts right now. You're all set!");
            return;
        }
        for (String a : alerts) {
            System.out.println("! " + a);
        }
    }

    private static void listMedicines(ReminderManager m) {
        List<Medicine> list = m.getMedicines();
        System.out.println("\n== ALL MEDICINES ==");
        if (list.isEmpty()) {
            System.out.println("No medicines added yet.");
            return;
        }
        String fmt = "%-4s %-18s %-9s %-6s %-18s %-6s %-11s %-11s%n";
        System.out.printf(fmt, "ID", "NAME", "DOSAGE", "PILLS", "TIMES", "STOCK", "START", "END");
        System.out.println(repeat('-', 90));
        for (Medicine med : list) {
            System.out.printf(fmt, med.getId(), trim(med.getName(), 18), trim(med.getDosage(), 9),
                    med.getPillsPerDose(), trim(med.timesText(), 18), med.getStock(),
                    med.getStartDate(), med.getEndDate());
        }
    }

    private static void refillStock(ReminderManager m) {
        int id = readInt("Medicine ID to refill: ");
        int pills = readPositiveInt("Pills to add: ");
        System.out.println(m.refill(id, pills) ? "Stock updated." : "Medicine ID not found.");
    }

    private static void adherenceReport(ReminderManager m) {
        List<Medicine> list = m.getMedicines();
        System.out.println("\n== ADHERENCE REPORT ==");
        if (list.isEmpty()) {
            System.out.println("No medicines added yet.");
            return;
        }
        String fmt = "%-18s %-8s %-9s %-10s%n";
        System.out.printf(fmt, "MEDICINE", "TAKEN", "SKIPPED", "ADHERENCE");
        System.out.println(repeat('-', 48));
        for (Medicine med : list) {
            double pct = m.adherence(med);
            System.out.printf(fmt, trim(med.getName(), 18),
                    m.countByStatus(med, DoseStatus.TAKEN),
                    m.countByStatus(med, DoseStatus.SKIPPED),
                    pct < 0 ? "N/A" : String.format("%.0f%%", pct));
        }
    }

    private static void removeMedicine(ReminderManager m) {
        int id = readInt("Medicine ID to remove: ");
        System.out.println(m.removeMedicine(id) ? "Medicine and its history removed." : "Medicine ID not found.");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------
    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "~";
    }

    private static String repeat(char c, int n) {
        return new String(new char[n]).replace('\0', c);
    }

    private static String readNonEmpty(String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = scanner.nextLine().trim();
            if (!s.isEmpty()) return s;
            System.out.println("Input cannot be empty.");
        }
    }

    private static int readInt(String prompt) {
        while (true) {
            System.out.print(prompt);
            try {
                return Integer.parseInt(scanner.nextLine().trim());
            } catch (NumberFormatException e) {
                System.out.println("Please enter a valid number.");
            }
        }
    }

    private static int readPositiveInt(String prompt) {
        while (true) {
            int n = readInt(prompt);
            if (n > 0) return n;
            System.out.println("Value must be greater than zero.");
        }
    }

    private static List<LocalTime> readTimes(String prompt) {
        while (true) {
            System.out.print(prompt);
            String line = scanner.nextLine().trim();
            try {
                TreeSet<LocalTime> set = new TreeSet<>();
                for (String part : line.split(",")) {
                    set.add(LocalTime.parse(part.trim(), TIME_INPUT));
                }
                return new ArrayList<>(set);
            } catch (DateTimeParseException e) {
                System.out.println("Invalid time. Use 24-hour HH:mm, e.g. 08:00, 21:30.");
            }
        }
    }
}
