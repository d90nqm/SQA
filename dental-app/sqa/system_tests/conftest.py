"""Shared Selenium and HTTP helpers for the Group 5 system-test tabs."""
from __future__ import annotations

import os
import json
import subprocess
import urllib.error
import urllib.request
import uuid
from datetime import date, timedelta
from pathlib import Path

import pytest
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.common.keys import Keys
from selenium.common.exceptions import StaleElementReferenceException
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait


BASE_URL = os.getenv("SQA_BASE_URL", "http://localhost:3000").rstrip("/")
API_URL = os.getenv("SQA_API_URL", "http://localhost:8080").rstrip("/")
TIMEOUT = int(os.getenv("SQA_TIMEOUT", "20"))
EVIDENCE_DIR = Path(__file__).parent / "evidence"


def pytest_addoption(parser):
    parser.addoption("--sqa-role", choices=("doctor", "receptionist", "both"), default="both")


def _login(role: str):
    phone = os.getenv(f"SQA_{role.upper()}_PHONE")
    if not phone:
        phone = "0000000002" if role == "doctor" else "0000000001"
    password = os.getenv(f"SQA_{role.upper()}_PASSWORD")
    if not password:
        pytest.skip(f"Credentials missing: run run_sqa.ps1 with -PromptForCredentials or set SQA_{role.upper()}_PASSWORD")
    driver = webdriver.Chrome()
    wait = WebDriverWait(driver, TIMEOUT)
    driver.get(f"{BASE_URL}/login")
    wait.until(EC.visibility_of_element_located((By.CSS_SELECTOR, 'input[placeholder="Nhập số điện thoại"]'))).send_keys(phone)
    driver.find_element(By.CSS_SELECTOR, 'input[placeholder="Nhập mật khẩu"]').send_keys(password)
    driver.find_element(By.XPATH, '//button[normalize-space()="Đăng nhập"]').click()
    wait.until(lambda d: "/doctor" in d.current_url if role == "doctor" else "/receptionist" in d.current_url)
    return driver


@pytest.fixture(scope="session")
def doctor():
    driver = _login("doctor")
    yield driver
    driver.quit()


@pytest.fixture(scope="session")
def receptionist():
    driver = _login("receptionist")
    yield driver
    driver.quit()


@pytest.fixture(scope="session")
def http():
    class Response:
        def __init__(self, status_code, body):
            self.status_code = status_code
            self.text = body
            self.ok = 200 <= status_code < 400

        def json(self):
            return json.loads(self.text) if self.text else None

    class Session:
        def request(self, method, url, *, json_body=None, timeout=20):
            data = json.dumps(json_body).encode("utf-8") if json_body is not None else None
            req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    return Response(resp.status, resp.read().decode("utf-8", errors="replace"))
            except urllib.error.HTTPError as err:
                return Response(err.code, err.read().decode("utf-8", errors="replace"))

        def get(self, url, *, timeout=20):
            return self.request("GET", url, timeout=timeout)

        def post(self, url, *, json=None, timeout=20):
            return self.request("POST", url, json_body=json, timeout=timeout)

    return Session()


@pytest.fixture
def wait():
    return WebDriverWait


def save_evidence(driver, case_id: str):
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    driver.save_screenshot(str(EVIDENCE_DIR / f"{case_id}.png"))


def open_history_list(driver):
    # Navigate inside the authenticated SPA. Hard-reloading a deep link can race
    # App.tsx's session restoration and send the browser back to /doctor.
    menu = driver.find_elements(By.XPATH, '//button[normalize-space()="Tạo đơn thuốc"]')
    if menu:
        menu[0].click()
    else:
        driver.get(f"{BASE_URL}/doctor")
        WebDriverWait(driver, TIMEOUT).until(
            EC.element_to_be_clickable((By.XPATH, '//button[normalize-space()="Tạo đơn thuốc"]'))
        ).click()
    WebDriverWait(driver, TIMEOUT).until(
        EC.visibility_of_element_located((By.XPATH, '//h1[normalize-space()="Tạo đơn thuốc"]'))
    )
    WebDriverWait(driver, TIMEOUT).until(
        lambda d: "Đang tải dữ liệu" not in d.find_element(By.TAG_NAME, "body").text
    )


def open_prescription_form(driver):
    open_history_list(driver)
    buttons = driver.find_elements(By.XPATH, '//button[normalize-space()="Kê đơn"]')
    if not buttons:
        pytest.skip("Prerequisite missing: doctor B has no eligible appointment/medical-history record")
    buttons[0].click()
    WebDriverWait(driver, TIMEOUT).until(
        EC.visibility_of_element_located((By.XPATH, '//h1[normalize-space()="Kê Đơn Thuốc"]'))
    )


def medicine_search(driver):
    return driver.find_element(By.CSS_SELECTOR, 'input[placeholder="Tìm kiếm thuốc theo tên hoặc mô tả..."]')


def choose_medicine(driver, query="Amox", index=0):
    field = medicine_search(driver)
    field.clear()
    field.send_keys(query)
    WebDriverWait(driver, TIMEOUT).until(
        lambda d: d.find_elements(By.XPATH, f'//button[contains(., "{query}")]')
        or "Không tìm thấy thuốc phù hợp" in d.find_element(By.TAG_NAME, "body").text
    )
    candidates = driver.find_elements(By.XPATH, '//button[.//p[contains(@class,"font-semibold")]]')
    candidates = [b for b in candidates if b.is_displayed()]
    if len(candidates) <= index:
        pytest.skip(f"Prerequisite missing: suitable medicine for search {query!r}")
    selected_name = candidates[index].text.splitlines()[0]
    candidates[index].click()
    return selected_name


def expand_first_item(driver):
    headings = driver.find_elements(By.TAG_NAME, "h3")
    container = _item_control_container(headings[0]) if headings else None
    if container is None:
        pytest.skip("Prerequisite missing: no medicine added to the prescription")
    container.find_elements(By.TAG_NAME, "button")[0].click()
    WebDriverWait(driver, TIMEOUT).until(EC.presence_of_element_located((By.CSS_SELECTOR, 'input[placeholder="VD: 1 viên/lần"]')))


def remove_first_item(driver):
    headings = driver.find_elements(By.TAG_NAME, "h3")
    container = _item_control_container(headings[0]) if headings else None
    if container is None:
        pytest.skip("Prerequisite missing: no medicine added to the prescription")
    buttons = container.find_elements(By.TAG_NAME, "button")
    buttons[-1].click()


def _item_control_container(heading):
    # The medication header row has two controls: expand and remove.
    for ancestor in heading.find_elements(By.XPATH, "./ancestor::div"):
        buttons = ancestor.find_elements(By.TAG_NAME, "button")
        if len(buttons) == 2:
            return ancestor
    return None


def _psql(database: str, sql: str):
    container = os.getenv("SQA_POSTGRES_CONTAINER", "dental-local-postgres-1")
    result = subprocess.run(
        ["docker", "exec", container, "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", database, "-t", "-A", "-F", "|", "-c", sql],
        capture_output=True,
        text=True,
        timeout=30,
    )
    if result.returncode:
        raise RuntimeError(f"SQA fixture SQL failed for {database}: {result.stderr.strip()}")
    return result.stdout.strip()


def create_sqa_medicine(request, name: str, quantity: int, expire_date: date | None = None):
    """Create one isolated medicine/lot fixture and clean it after the testcase."""
    medicine_id = str(uuid.uuid4())
    lot_id = str(uuid.uuid4())
    lot_no = f"SQA-{uuid.uuid4().hex[:20]}"
    safe_name = name.replace("'", "''")[:100]
    expiry_sql = "NULL" if expire_date is None else f"'{expire_date.isoformat()}'"
    sql = f"""
BEGIN;
INSERT INTO medicine (id, name, unit, description, sale_price)
VALUES ('{medicine_id}', '{safe_name}', 'Viên', 'Temporary SQA system-test fixture', 1000);
INSERT INTO inventory_lot (id, lot_no, expire_date, quantity_on_hand, cost_price, medicine_id)
VALUES ('{lot_id}', '{lot_no}', {expiry_sql}, {int(quantity)}, 500, '{medicine_id}');
COMMIT;
"""
    _psql("inventory-service", sql)

    def cleanup():
        cleanup_sql = f"""
BEGIN;
DELETE FROM stock_ledger
WHERE inventory_lot_id = '{lot_id}'
   OR reference_id IN (SELECT id FROM dispense_item WHERE inventory_lot_id = '{lot_id}');
DELETE FROM dispense_item WHERE inventory_lot_id = '{lot_id}';
DELETE FROM dispense_order WHERE id NOT IN (SELECT DISTINCT dispense_order_id FROM dispense_item WHERE dispense_order_id IS NOT NULL)
  AND id IN (SELECT dispense_order_id FROM dispense_item WHERE inventory_lot_id = '{lot_id}');
DELETE FROM inventory_lot WHERE id = '{lot_id}';
DELETE FROM medicine WHERE id = '{medicine_id}';
COMMIT;
"""
        _psql("inventory-service", cleanup_sql)

    request.addfinalizer(cleanup)
    return {"medicine_id": medicine_id, "lot_id": lot_id, "name": name, "quantity": quantity}


def set_sqa_lot_quantity(lot_id: str, quantity: int):
    lot_id = str(uuid.UUID(lot_id))
    _psql("inventory-service", f"UPDATE inventory_lot SET quantity_on_hand = {int(quantity)} WHERE id = '{lot_id}';")


def create_disposable_rx_case(driver, http, request, case_id: str):
    """Clone a current doctor-B appointment/history into a disposable SQA record."""
    current_user = driver.execute_script("return localStorage.getItem('currentUser')")
    if not current_user:
        raise RuntimeError("Doctor session has no currentUser in localStorage")
    doctor_id = str(uuid.UUID(json.loads(current_user)["id"]))
    appointment_id, history_id = str(uuid.uuid4()), str(uuid.uuid4())
    marker = f"SQA-{case_id}-{uuid.uuid4().hex[:8]}"
    source = _psql(
        "appointment-service",
        "SELECT a.id::text || '|' || a.patient_id::text || '|' || COALESCE(ams.medical_service_id::text,'') "
        "FROM appointment a "
        "LEFT JOIN appointment_medical_service ams ON ams.appointment_id=a.id "
        f"WHERE a.doctor_id='{doctor_id}' AND a.status='IN_PROGRESS' ORDER BY a.created_at DESC LIMIT 1;",
    )
    if not source:
        raise RuntimeError("Cannot create isolated SQA appointment: doctor B has no IN_PROGRESS appointment to copy")
    source_appointment_id, patient_id, service_id = source.split("|", 2)
    source_appointment_id = str(uuid.UUID(source_appointment_id))
    patient_id = str(uuid.UUID(patient_id))
    service_id = str(uuid.UUID(service_id)) if service_id else str(uuid.uuid4())

    _psql(
        "appointment-service",
        f"""
BEGIN;
INSERT INTO appointment (id, doctor_id, patient_id, appointment_start_time, appointment_end_time, status, created_at, updated_at)
SELECT '{appointment_id}', doctor_id, patient_id, NOW() + INTERVAL '2 days', NOW() + INTERVAL '2 days 30 minutes', 'IN_PROGRESS', NOW(), NOW()
FROM appointment WHERE id = '{source_appointment_id}';
INSERT INTO appointment_medical_service (appointment_id, medical_service_id)
SELECT '{appointment_id}', medical_service_id FROM appointment_medical_service WHERE appointment_id = '{source_appointment_id}';
COMMIT;
""",
    )
    _psql(
        "patient-service",
        f"""
INSERT INTO medical_history (id, appointment_id, created_at, symptoms, updated_at, patient_profile_id)
SELECT '{history_id}', '{appointment_id}', NOW(), '{marker}', NOW(), patient_profile_id
FROM medical_history WHERE appointment_id = '{source_appointment_id}' ORDER BY created_at DESC LIMIT 1;
""",
    )

    invoice_id, invoice_item_id = str(uuid.uuid4()), str(uuid.uuid4())
    invoice_payload = {
        "id": invoice_id,
        "receptionistId": "00000000-0000-0000-0000-000000000301",
        "appointmentId": appointment_id,
        "currency": "VND",
        "items": [{
            "id": invoice_item_id,
            "referenceId": service_id,
            "serviceType": "MEDICAL_SERVICE",
            "quantity": 1,
            "description": f"Temporary {marker} visit",
            "unitPrice": 100000,
        }],
    }
    response = http.post(f"{API_URL}/invoice-service/invoices", json=invoice_payload, timeout=30)
    if response.status_code != 201:
        _cleanup_disposable_rx_case(appointment_id, history_id)
        raise RuntimeError(f"Cannot create disposable SQA invoice for {case_id}: HTTP {response.status_code} {response.text}")

    request.addfinalizer(lambda: _cleanup_disposable_rx_case(appointment_id, history_id))
    return {"appointment_id": appointment_id, "medical_history_id": history_id, "patient_id": patient_id, "marker": marker, "invoice_id": invoice_id}


def _cleanup_disposable_rx_case(appointment_id: str, history_id: str):
    appointment_id = str(uuid.UUID(appointment_id))
    history_id = str(uuid.UUID(history_id))
    _psql(
        "inventory-service",
        f"""
BEGIN;
DELETE FROM stock_ledger WHERE reference_id IN (
  SELECT di.id FROM dispense_item di JOIN dispense_order d ON d.id=di.dispense_order_id WHERE d.medical_history_id='{history_id}'
);
DELETE FROM dispense_order WHERE medical_history_id = '{history_id}';
COMMIT;
""",
    )
    _psql("invoice-service", f"DELETE FROM invoice WHERE appointment_id = '{appointment_id}';")
    _psql("patient-service", f"DELETE FROM medical_history WHERE id = '{history_id}';")
    _psql("appointment-service", f"DELETE FROM appointment_medical_service WHERE appointment_id = '{appointment_id}'; DELETE FROM appointment WHERE id = '{appointment_id}';")


def open_prescription_form_for_marker(driver, marker: str):
    open_history_list(driver)
    locator = (By.CSS_SELECTOR, 'input[placeholder="Tìm kiếm theo tên bệnh nhân, chẩn đoán..."]')
    for attempt in range(3):
        search = WebDriverWait(driver, TIMEOUT).until(EC.element_to_be_clickable(locator))
        try:
            search.click()
            search.send_keys(Keys.CONTROL, "a")
            search.send_keys(marker)
            break
        except StaleElementReferenceException:
            if attempt == 2:
                raise
            open_history_list(driver)
    target = f"//div[contains(@class,'group') and .//*[contains(normalize-space(.),'{marker}')]]//button[normalize-space()='Kê đơn']"
    WebDriverWait(driver, TIMEOUT).until(EC.element_to_be_clickable((By.XPATH, target))).click()
    WebDriverWait(driver, TIMEOUT).until(EC.visibility_of_element_located((By.XPATH, '//h1[normalize-space()="Kê Đơn Thuốc"]')))


def clone_bhyt_invoice(request, http):
    """Clone an insured invoice into a disposable invoice record for UI coverage checks."""
    new_id = str(uuid.uuid4())
    source_id = _psql("invoice-service", "SELECT id FROM invoice WHERE COALESCE(insurance_total_pay,0)>0 ORDER BY id LIMIT 1;")
    if not source_id:
        raise RuntimeError("No seeded invoice with an insurance payment exists to clone for FM_00_UI_03")
    source_id = str(uuid.UUID(source_id))
    _psql(
        "invoice-service",
        f"""
BEGIN;
INSERT INTO invoice (id, appointment_id, currency, insurance_claim_id, insurance_total_pay, issue_at, paid_at, patient_total_pay, receptionist_id, status, total_amount, update_at)
SELECT '{new_id}', appointment_id, currency, insurance_claim_id, insurance_total_pay, NOW(), NOW(), patient_total_pay, receptionist_id, status, total_amount, NOW()
FROM invoice WHERE id = '{source_id}';
INSERT INTO invoice_item (id, claim_item_id, description, insurance_pay_amount, patient_pay_amount, quantity, reference_id, service_type, unit_price, invoice_id)
SELECT gen_random_uuid(), claim_item_id, description, insurance_pay_amount, patient_pay_amount, quantity, reference_id, service_type, unit_price, '{new_id}'
FROM invoice_item WHERE invoice_id = '{source_id}';
COMMIT;
""",
    )
    request.addfinalizer(lambda: _psql("invoice-service", f"DELETE FROM invoice WHERE id = '{new_id}';"))
    response = http.get(f"{API_URL}/invoice-service/invoices/{new_id}", timeout=20)
    if not response.ok:
        raise RuntimeError(f"Cloned BHYT invoice is not available through the API: HTTP {response.status_code}")
    return new_id, response.json()


def create_invoice_payload(unit_price: int, *, quantity: int = 1):
    import uuid
    return {
        "id": str(uuid.uuid4()),
        "receptionistId": str(uuid.uuid4()),
        "appointmentId": str(uuid.uuid4()),
        "currency": "VND",
        "items": [{
            "id": str(uuid.uuid4()),
            "referenceId": str(uuid.uuid4()),
            "serviceType": "Dental",
            "quantity": quantity,
            "description": "SQA disposable test item",
            "unitPrice": unit_price,
        }],
    }
