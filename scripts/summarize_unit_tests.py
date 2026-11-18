"""
Tong hop ket qua unit test (INV / INS / BOT) tu file JUnit XML va in bang giong sheet Excel.
Khong can cai them thu vien (chi dung thu vien chuan cua Python).

Chay (tu thu muc goc cua project):
    python scripts/summarize_unit_tests.py
"""
import csv
import glob
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# module -> (ten hien thi, mau file XML, regex lay ma test case tu ten test, so luong ky vong trong Excel)
MODULES = [
    ("INV", "InvoiceService", "service/invoice-service/build/test-results/test/TEST-*InvoiceServiceTest.xml",
     r"^inv0*(\d+)_", dict(total=7, passed=6, failed=1)),
    ("INS", "InsuranceClaimService", "service/insurance-service/build/test-results/test/TEST-*InsuranceClaimServiceTest.xml",
     r"^ins0*(\d+)_", dict(total=7, passed=7, failed=0)),
    ("BOT", "ChatbotService", "service/chatbot-service/test-results.xml",
     r"^test_bot_0*(\d+)_", dict(total=7, passed=7, failed=0)),
]


def read_cases(pattern):
    cases = []
    for path in sorted(glob.glob(str(ROOT / pattern))):
        for tc in ET.parse(path).getroot().iter("testcase"):
            name = tc.get("name", "").replace("()", "")
            bad = tc.find("failure")
            if bad is None:
                bad = tc.find("error")
            if bad is not None:
                status = "Fail"
                msg = (bad.get("message") or (bad.text or "")).strip().splitlines()
                detail = msg[0][:140] if msg else ""
            elif tc.find("skipped") is not None:
                status, detail = "Pending", ""
            else:
                status, detail = "Pass", ""
            cases.append((name, status, detail))
    return cases


def main():
    rows = []
    print()
    for code, title, pattern, id_re, expected in MODULES:
        cases = read_cases(pattern)
        print("=" * 100)
        print(f"{code} - {title}")
        print("=" * 100)
        if not cases:
            print(f"  (chua co file ket qua: {pattern} - hay chay test truoc)\n")
            continue
        by_id = {}
        for name, status, detail in cases:
            m = re.match(id_re, name, re.IGNORECASE)
            tc_id = f"{code}-{int(m.group(1)):02d}" if m else name
            by_id.setdefault(tc_id, []).append((name, status, detail))
        passed = failed = 0
        for tc_id in sorted(by_id):
            items = by_id[tc_id]
            ok = all(s == "Pass" for _, s, _ in items)
            status = "Pass" if ok else "Fail"
            first_bad = next((d for _, s, d in items if s != "Pass"), "")
            passed += ok
            failed += (not ok)
            print(f"  {tc_id:8} {status:5} {items[0][0][:70]}")
            if first_bad:
                print(f"           -> {first_bad}")
            rows.append([code, tc_id, status, items[0][0], first_bad])
        total = passed + failed
        match = (total, passed, failed) == (expected["total"], expected["passed"], expected["failed"])
        print(f"\n  Total={total}  Pass={passed}  Fail={failed}  Pending=0   |  Excel: "
              f"Total={expected['total']} Pass={expected['passed']} Fail={expected['failed']}  ->  "
              f"{'KHOP' if match else 'KHAC'}\n")

    out = ROOT / "unit-test-summary.csv"
    with open(out, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["Module", "Test case ID", "Result", "Method name", "Note"])
        w.writerows(rows)
    print(f"Da ghi: {out}")


if __name__ == "__main__":
    sys.exit(main())
