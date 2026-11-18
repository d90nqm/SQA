# System Test Results

Workbook: `5_system_test.xlsx` — tabs `CP_01`, `CP_02`, and `FM_00`.

## Overall

| Tab | Cases | Pass | Fail | Skip |
| --- | ---: | ---: | ---: | ---: |
| CP_01 | 24 | 20 | 4 | 0 |
| CP_02 | 45 | 29 | 5 | 11 |
| FM_00 | 7 | 4 | 2 | 1 |
| **Total** | **76** | **53** | **11** | **12** |

## Failed cases

| Test case | Result observed |
| --- | --- |
| CP_01_UI_13 | Searching by patient ID `00000101` did not return the patient. |
| CP_01_FN_05 | The UI shows `10:00:00 13/11/2026`; the case expects `YYYY-MM-DD HH:MM`. |
| CP_01_FN_06 | No date-sort control is present. |
| CP_01_FN_07 | No pagination controls are present. |
| CP_02_UI_12 | Dosage default is blank instead of `1 viên/lần`. |
| CP_02_UI_15 | Frequency default is blank instead of `3 lần/ngày`. |
| CP_02_UI_18 | Duration default is blank instead of `7 ngày`. |
| CP_02_UI_21 | Directions default is blank. |
| CP_02_UI_24 | Save button is disabled while the prescription is empty; the case expects it enabled. |
| FM_00_02 | Invoice creation accepted unit price 0 and returned HTTP 201. |
| FM_00_03 | Invoice creation accepted unit price −100 and returned HTTP 201. |

## Skipped cases and prerequisites

- **CP_02_FN_05:** the two named Amoxicillin brands are not in the local medicine catalog.
- **CP_02_FN_07–08:** no low-stock or out-of-stock medicine fixture is available.
- **CP_02_FN_11–15:** requires controlled concurrent stock/Saga/WebSocket scenarios and a final workflow-status oracle.
- **CP_02_FN_16–18:** requires isolated inventory lots at the exact expiry boundaries.
- **FM_00_UI_03:** requires a disposable appointment with an active BHYT claim and covered service fixture.

## Test data created

Two local appointment and medical-history fixtures were added for doctor B so CP_01 could exercise partial-name search, symptom display, and visit date:

- Lê Anh Tuấn — symptom `Chảy máu chân răng`.
- Lê Thu Hoa — symptom `SQA fixture: ê buốt răng khi uống lạnh`.

These are local test records and remain available for reruns. Invoice records created by FM_00 are deleted by the test cleanup; the database was checked after execution.

## Reports

- [CP_01 JUnit report](results_cp01.xml)
- [CP_02 JUnit report](results_cp02.xml)
- [FM_00 JUnit report](results_fm.xml)

Run the same tabs again with `run_sqa.ps1 -PromptForCredentials` in PowerShell. The password prompt is secure; credentials are not written to the test files.
