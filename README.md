# Medicine Reminder System

A lightweight, console-based Java application that helps you **never miss a dose**. It stores medicine schedules, shows what is due today, records taken/skipped doses, tracks remaining stock, and warns you before you run out or a course ends.

## Features

- Add medicines with name, strength, pills per dose, dose times, stock and course length
- Today's schedule, sorted by time, with live status:
  | Status   | Meaning                                              |
  |----------|------------------------------------------------------|
  | UPCOMING | Dose is more than 30 minutes away                    |
  | DUE_NOW  | Within 30 min before to 60 min after the dose time   |
  | MISSED   | More than 60 minutes late and not recorded           |
  | TAKEN    | You marked it as taken                               |
  | SKIPPED  | You marked it as skipped                             |
- Marking a dose as taken automatically reduces stock
- Alerts shown on startup:
  - Doses due now or missed
  - Low stock (3 days of supply or less) and out-of-stock
  - Courses ending within 2 days, and finished courses
- Refill stock, view all medicines, remove medicines
- Adherence report (taken, skipped, and percentage of scheduled doses taken)
- Data saved in CSV files - no database required
- Input validation for times and numbers

## Tech Stack

- Java 8 or above
- Java standard library only (no external dependencies)

## Getting Started

### Prerequisites
- JDK 8+ installed (`java -version` to check)

### Compile
```bash
javac MedicineReminderSystem.java
```

### Run
```bash
java MedicineReminderSystem
```

## Usage

```
=========================================
        MEDICINE REMINDER SYSTEM
=========================================

---------------- MENU ----------------
1. Add medicine
2. Today's schedule
3. Mark a dose (taken / skipped)
4. View alerts
5. View all medicines
6. Refill stock
7. Adherence report
8. Remove medicine
0. Exit
--------------------------------------
```

### Sample output

```
== TODAY'S SCHEDULE (2026-10-18) ==
NO.  TIME    MEDICINE             DOSAGE     PILLS  STATUS
--------------------------------------------------------------
1    08:00   Metformin            500mg      1      TAKEN
2    14:00   Amoxicillin          250mg      2      DUE_NOW
3    21:00   Metformin            500mg      1      UPCOMING

== ALERTS ==
! DUE NOW     : Amoxicillin (250mg) at 14:00
! LOW STOCK   : Metformin - about 2 day(s) of supply left (4 pills)
! COURSE ENDS : Amoxicillin ends in 1 day(s)
```

## Project Structure

```
.
├── MedicineReminderSystem.java   # Complete application source
├── README.md                     # Project documentation
├── medicines.csv                 # Auto-generated: medicine list
└── doses.csv                     # Auto-generated: dose history
```

## Data Format

`medicines.csv`
```
id,name,dosage,pillsPerDose,times,stock,startDate,endDate
1,Amoxicillin,250mg,2,08:00;14:00;21:00,30,2026-10-18,2026-10-24
```

`doses.csv`
```
medicineId,date,time,status
1,2026-10-18,08:00,TAKEN
```

## How It Works

1. On launch, medicines and dose history are loaded from the CSV files.
2. Today's schedule is built from each active medicine's dose times.
3. Each dose gets a status by comparing its time with the current time and the dose history.
4. Marking a dose taken reduces stock by the pills-per-dose amount.
5. Alerts are generated from the schedule, remaining supply and course end dates.
6. Every change is saved automatically.

## Future Improvements

- Desktop popup or sound notifications at dose time
- Email / SMS reminders
- Support for family members (multiple profiles)
- Food instructions ("after meals") and drug interaction notes
- GUI using JavaFX or Swing
- Export adherence reports to PDF

## Disclaimer

This project is for learning and personal organisation only. It is not medical advice. Always follow your doctor's or pharmacist's instructions.

## License

This project is open source and available under the [MIT License](https://opensource.org/licenses/MIT).
