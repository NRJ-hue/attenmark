const assert = require("assert");
const XLSX = require("xlsx");
const {
  extractRollNo,
  formatDateKey,
  formatTimePart,
  formatSessionDateHeader,
  detectPendingReviews,
  buildAttendanceMatrix,
  generateAttendanceWorkbook
} = require("../attendance_exporter");

describe("Excel Attendance Export Engine", function () {
  const sampleCourse = {
    classCode: "CS101",
    name: "Introduction to Algorithms",
    facultyEmail: "faculty@test.com",
    enrolledStudents: [
      "25bcs254@iiitdwd.ac.in",
      "25bcs101@iiitdwd.ac.in",
      "25bcs002@iiitdwd.ac.in"
    ]
  };

  const oct26_10am = new Date("2026-10-26T10:00:00.000Z").getTime();
  const oct26_2pm = new Date("2026-10-26T14:00:00.000Z").getTime();
  const oct27_10am = new Date("2026-10-27T10:00:00.000Z").getTime();

  const sessions = [
    { sessionId: "sess-1", courseCode: "CS101", startedAt: oct26_10am },
    { sessionId: "sess-2", courseCode: "CS101", startedAt: oct26_2pm },
    { sessionId: "sess-3", courseCode: "CS101", startedAt: oct27_10am }
  ];

  describe("1. Roll Number Extraction", function () {
    it("should extract roll number from standard institutional emails", function () {
      assert.strictEqual(extractRollNo("25bcs254@iiitdwd.ac.in"), "25bcs254");
      assert.strictEqual(extractRollNo("23BCS101@IIITDWD.AC.IN"), "23bcs101");
    });

    it("should handle whitespace and case-insensitivity", function () {
      assert.strictEqual(extractRollNo("  21bcs042@gmail.com  "), "21bcs042");
    });

    it("should fallback cleanly if input does not contain @", function () {
      assert.strictEqual(extractRollNo("25bcs099"), "25bcs099");
    });

    it("should return UNKNOWN for null, undefined, or empty strings", function () {
      assert.strictEqual(extractRollNo(null), "UNKNOWN");
      assert.strictEqual(extractRollNo(undefined), "UNKNOWN");
      assert.strictEqual(extractRollNo(""), "UNKNOWN");
      assert.strictEqual(extractRollNo("   "), "UNKNOWN");
    });
  });

  describe("2. Date and Header Formatting", function () {
    it("should format timestamps to DD-MMM-YYYY readable dates", function () {
      const formatted = formatDateKey(oct26_10am);
      assert.strictEqual(formatted, "26-Oct-2026");
    });

    it("should format session date header with no time if session date is unique", function () {
      const singleSessionList = [sessions[2]]; // Oct 27 only
      const header = formatSessionDateHeader(sessions[2], singleSessionList);
      assert.strictEqual(header, "27-Oct-2026");
    });

    it("should distinguish multiple sessions on the same date with session time", function () {
      const header1 = formatSessionDateHeader(sessions[0], sessions);
      const header2 = formatSessionDateHeader(sessions[1], sessions);

      assert(header1.startsWith("26-Oct-2026 ("), `Expected ${header1} to include time`);
      assert(header2.startsWith("26-Oct-2026 ("), `Expected ${header2} to include time`);
      assert.notStrictEqual(header1, header2, "Headers for same-day sessions must be distinct");
    });
  });

  describe("3. Pending Review Detection", function () {
    it("should detect unresolved REVIEW and PENDING records", function () {
      const records = [
        { sessionId: "sess-1", studentEmail: "25bcs254@iiitdwd.ac.in", status: "APPROVED" },
        { sessionId: "sess-1", studentEmail: "25bcs101@iiitdwd.ac.in", status: "REVIEW" }
      ];
      const manual = [
        { sessionId: "sess-2", studentEmail: "25bcs002@iiitdwd.ac.in", status: "PENDING" }
      ];

      const { count, pendingList } = detectPendingReviews(records, manual);
      assert.strictEqual(count, 2);
      assert.strictEqual(pendingList.length, 2);
    });

    it("should return count 0 when all records are decided (APPROVED or REJECTED)", function () {
      const records = [
        { sessionId: "sess-1", studentEmail: "25bcs254@iiitdwd.ac.in", status: "APPROVED" },
        { sessionId: "sess-1", studentEmail: "25bcs101@iiitdwd.ac.in", status: "REJECTED" }
      ];
      const { count } = detectPendingReviews(records, []);
      assert.strictEqual(count, 0);
    });
  });

  describe("4. Attendance Matrix Construction", function () {
    it("should map APPROVED to 1, REJECTED to 0, and unsubmitted/absent to 0", function () {
      const records = [
        // 25bcs002: sess-1 APPROVED (1), sess-2 absent (0), sess-3 REJECTED (0)
        { sessionId: "sess-1", studentEmail: "25bcs002@iiitdwd.ac.in", status: "APPROVED" },
        { sessionId: "sess-3", studentEmail: "25bcs002@iiitdwd.ac.in", status: "REJECTED" },
        // 25bcs101: sess-1 APPROVED (1), sess-2 APPROVED (1), sess-3 absent (0)
        { sessionId: "sess-1", studentEmail: "25bcs101@iiitdwd.ac.in", status: "APPROVED" },
        { sessionId: "sess-2", studentEmail: "25bcs101@iiitdwd.ac.in", status: "APPROVED" }
      ];

      const matrix = buildAttendanceMatrix(sampleCourse, sessions, records, [], { isProvisional: false });

      // Roll No column + 3 sessions = 4 columns
      assert.strictEqual(matrix.headers.length, 4);
      assert.strictEqual(matrix.headers[0], "Roll No");

      // Sorted by roll number: 25bcs002, 25bcs101, 25bcs254
      assert.strictEqual(matrix.rows.length, 3);

      const row002 = matrix.rows.find((r) => r[0] === "25bcs002");
      assert.deepStrictEqual(row002, ["25bcs002", 1, 0, 0]);

      const row101 = matrix.rows.find((r) => r[0] === "25bcs101");
      assert.deepStrictEqual(row101, ["25bcs101", 1, 1, 0]);

      const row254 = matrix.rows.find((r) => r[0] === "25bcs254");
      // Entirely absent
      assert.deepStrictEqual(row254, ["25bcs254", 0, 0, 0]);
    });

    it("should include unenrolled students who have submission records in the matrix", function () {
      const records = [
        { sessionId: "sess-1", studentEmail: "25bcs999@iiitdwd.ac.in", status: "APPROVED" }
      ];

      const matrix = buildAttendanceMatrix(sampleCourse, [sessions[0]], records, []);
      const row999 = matrix.rows.find((r) => r[0] === "25bcs999");
      assert(row999, "Expected student 25bcs999 to be present in exported matrix");
      assert.strictEqual(row999[1], 1);
    });

    it("should filter sessions by date range when provided", function () {
      const matrix = buildAttendanceMatrix(
        sampleCourse,
        sessions,
        [],
        [],
        { dateFrom: "2026-10-27", dateTo: "2026-10-27" }
      );

      // Only Oct 27 session should be included
      assert.strictEqual(matrix.sessions.length, 1);
      assert.strictEqual(matrix.headers.length, 2);
      assert.strictEqual(matrix.headers[1], "27-Oct-2026");
    });

    it("should mark pending reviews as REVIEW in provisional export and PENDING in standard export", function () {
      const records = [
        { sessionId: "sess-1", studentEmail: "25bcs002@iiitdwd.ac.in", status: "REVIEW" }
      ];

      const stdMatrix = buildAttendanceMatrix(sampleCourse, [sessions[0]], records, [], { isProvisional: false });
      const provMatrix = buildAttendanceMatrix(sampleCourse, [sessions[0]], records, [], { isProvisional: true });

      assert.strictEqual(stdMatrix.rows[0][1], "PENDING");
      assert.strictEqual(provMatrix.rows[0][1], "REVIEW");
      assert.strictEqual(stdMatrix.hasPendingReviews, true);
    });

    it("should handle course with 0 sessions gracefully", function () {
      const matrix = buildAttendanceMatrix(sampleCourse, [], [], []);
      assert.strictEqual(matrix.sessions.length, 0);
      assert.strictEqual(matrix.headers.length, 1);
      assert.strictEqual(matrix.headers[0], "Roll No");
    });
  });

  describe("5. True Binary XLSX Workbook Generation", function () {
    it("should generate a valid XLSX binary workbook with metadata, frozen panes, and correct cells", function () {
      const records = [
        { sessionId: "sess-1", studentEmail: "25bcs254@iiitdwd.ac.in", status: "APPROVED" },
        { sessionId: "sess-2", studentEmail: "25bcs254@iiitdwd.ac.in", status: "APPROVED" },
        { sessionId: "sess-1", studentEmail: "25bcs002@iiitdwd.ac.in", status: "APPROVED" }
      ];

      const exportResult = generateAttendanceWorkbook(sampleCourse, sessions, records, [], {}, XLSX);
      const wb = exportResult.wb;

      assert(wb, "Workbook must be returned");
      assert(exportResult.filename.startsWith("CS101_Attendance_"), "Filename should start with course code");
      assert(exportResult.filename.endsWith(".xlsx"), "Filename must end with .xlsx");

      // Verify binary output buffer
      const buffer = XLSX.write(wb, { type: "buffer", bookType: "xlsx" });
      assert(buffer instanceof Buffer, "XLSX.write should return a Buffer");
      assert(buffer.length > 500, "XLSX buffer should have non-trivial size");

      // OpenXML PK Zip magic number verification (0x50, 0x4B, 0x03, 0x04)
      assert.strictEqual(buffer[0], 0x50, "Magic byte 0 must be 'P'");
      assert.strictEqual(buffer[1], 0x4b, "Magic byte 1 must be 'K'");
      assert.strictEqual(buffer[2], 0x03, "Magic byte 2 must be 0x03");
      assert.strictEqual(buffer[3], 0x04, "Magic byte 3 must be 0x04");

      // Read back workbook to verify internal integrity
      const parsedWb = XLSX.read(buffer, { type: "buffer" });
      const sheetName = parsedWb.SheetNames[0];
      assert(sheetName.includes("CS101 Attendance"), `Sheet name was ${sheetName}`);

      const sheet = parsedWb.Sheets[sheetName];
      assert(sheet, "Parsed sheet should exist");

      // Verify SheetJS freeze panes
      assert(exportResult.ws["!freeze"], "Freeze configuration should exist");
      assert.strictEqual(exportResult.ws["!freeze"].xSplit, "1", "Should freeze Roll No column");
      assert.strictEqual(exportResult.ws["!freeze"].ySplit, "1", "Should freeze header row");

      // Verify Column widths
      assert(exportResult.ws["!cols"], "Column widths should be defined");
      assert.strictEqual(exportResult.ws["!cols"][0].wch, 18, "Roll No column width");

      // Verify Props
      assert(wb.Props, "Workbook properties should exist");
      assert.strictEqual(wb.Props.Title, "CS101 Attendance");
      assert.strictEqual(wb.Props.Author, "faculty@test.com");
      assert(wb.Props.Comments.includes("Note on Enrollment History"), "Should include documented enrollment limitation");
    });

    it("should tag provisional workbooks in title, metadata, and filename", function () {
      const records = [
        { sessionId: "sess-1", studentEmail: "25bcs254@iiitdwd.ac.in", status: "REVIEW" }
      ];

      const exportResult = generateAttendanceWorkbook(sampleCourse, sessions, records, [], { isProvisional: true }, XLSX);

      assert(exportResult.filename.includes("_PROVISIONAL.xlsx"), `Filename: ${exportResult.filename}`);
      assert.strictEqual(exportResult.wb.Props.Title, "[PROVISIONAL] CS101 Attendance");
      assert(exportResult.wb.Props.Comments.includes("PROVISIONAL EXPORT"), "Comments must note provisional status");
    });

    it("should handle course with 0 sessions without throwing and generate informative workbook", function () {
      const exportResult = generateAttendanceWorkbook(sampleCourse, [], [], [], {}, XLSX);
      const buffer = XLSX.write(exportResult.wb, { type: "buffer", bookType: "xlsx" });

      assert.strictEqual(buffer[0], 0x50);
      assert.strictEqual(buffer[1], 0x4b);

      const parsedWb = XLSX.read(buffer, { type: "buffer" });
      const sheet = parsedWb.Sheets[parsedWb.SheetNames[0]];
      const json = XLSX.utils.sheet_to_json(sheet);
      assert(json.length > 0, "Should have rows indicating no sessions conducted");
    });
  });
});
