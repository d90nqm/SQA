"""FM_00 — invoice flow and validation cases from the workbook."""
import pytest
import os
import subprocess
from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

from conftest import API_URL, BASE_URL, TIMEOUT, clone_bhyt_invoice, create_invoice_payload, save_evidence


CASES = ["FM_00_UI_01", "FM_00_UI_02", "FM_00_UI_03", "FM_00_01", "FM_00_02", "FM_00_03", "FM_00_04"]


def _create(http, price, request):
    payload = create_invoice_payload(price)
    response = http.post(f"{API_URL}/invoice-service/invoices", json=payload, timeout=30)
    if response.status_code == 201:
        invoice_id = payload["id"]

        def cleanup_test_invoice():
            container = os.getenv("SQA_POSTGRES_CONTAINER", "dental-local-postgres-1")
            query = f"DELETE FROM invoice WHERE id='{invoice_id}'"
            result = subprocess.run(
                ["docker", "exec", container, "psql", "-U", "postgres", "-d", "invoice-service", "-c", query],
                capture_output=True, text=True, timeout=30,
            )
            if result.returncode:
                print(f"Test invoice cleanup could not run for {invoice_id}: {result.stderr.strip()}")

        request.addfinalizer(cleanup_test_invoice)
    return payload, response


@pytest.mark.parametrize("case_id", CASES, ids=CASES)
def test_fm_00(case_id, http, request):
    if case_id in ("FM_00_UI_01", "FM_00_UI_02", "FM_00_UI_03"):
        d = request.getfixturevalue("receptionist")
        if case_id == "FM_00_UI_03":
            invoice_id, expected = clone_bhyt_invoice(request, http)
            menu = d.find_elements(By.XPATH, '//button[contains(normalize-space(.),"Thanh toán hóa đơn")]')
            if menu:
                menu[0].click()
            else:
                d.get(f"{BASE_URL}/receptionist/invoices")
            try:
                search = WebDriverWait(d, TIMEOUT).until(
                    EC.visibility_of_element_located((By.XPATH, '//input[contains(@placeholder,"mã hóa đơn")]'))
                )
            except Exception as error:
                raise AssertionError(f"Invoice list did not open. URL={d.current_url}; page={d.find_element(By.TAG_NAME, 'body').text}") from error
            search.send_keys(invoice_id[-8:])
            row = f"//tr[.//*[contains(translate(normalize-space(.),'abcdef','ABCDEF'),'{invoice_id[-8:].upper()}')]]"
            WebDriverWait(d, TIMEOUT).until(EC.presence_of_element_located((By.XPATH, row)))
            d.find_element(By.XPATH, row + "//button[normalize-space()='Chi tiết']").click()
        else:
            invoices = http.get(f"{API_URL}/invoice-service/invoices", timeout=20)
            assert invoices.ok, f"Invoice list API unavailable: HTTP {invoices.status_code}"
            rows = invoices.json()
            assert rows, "No seeded invoice exists for the invoice-detail UI check"
            invoice_id = rows[0]["id"]
            d.get(f"{BASE_URL}/receptionist/invoices/{invoice_id}")
        WebDriverWait(d, TIMEOUT).until(lambda x: invoice_id[-8:] in x.find_element(By.TAG_NAME, "body").text or "Hóa đơn" in x.find_element(By.TAG_NAME, "body").text)
        if case_id == "FM_00_UI_03":
            WebDriverWait(d, TIMEOUT).until(
                lambda x: "Bảo hiểm chi trả" in x.find_element(By.TAG_NAME, "body").text
                and "Bệnh nhân thanh toán" in x.find_element(By.TAG_NAME, "body").text
            )
        text = d.find_element(By.TAG_NAME, "body").text
        assert "Hóa đơn" in text or "Tổng" in text
        if case_id == "FM_00_UI_01":
            assert any(label in text for label in ("Thanh toán", "PENDING", "PAID", "Tổng tiền"))
        else:
            assert "Tổng" in text and len(text) > 150
        if case_id == "FM_00_UI_03":
            assert "Bảo hiểm chi trả" in text
            assert "Bệnh nhân thanh toán" in text
            insurance_value = f"{expected['insuranceTotalPay']:,}".replace(",", ".")
            patient_value = f"{expected['patientTotalPay']:,}".replace(",", ".")
            assert insurance_value in text, f"Expected insurance share {insurance_value} to appear in invoice"
            assert patient_value in text, f"Expected patient share {patient_value} to appear in invoice"
        save_evidence(d, case_id)
        return

    if case_id == "FM_00_01":
        payload, response = _create(http, 350000, request)
        assert response.status_code == 201, f"Expected invoice creation, got HTTP {response.status_code}: {response.text}"
        invoice = response.json()
        assert invoice["totalAmount"] == 350000
        assert invoice["patientTotalPay"] == 350000
        assert invoice["items"][0]["quantity"] == 1 and invoice["items"][0]["unitPrice"] == 350000
        return

    if case_id in ("FM_00_02", "FM_00_03"):
        price = 0 if case_id == "FM_00_02" else -100
        _, response = _create(http, price, request)
        assert response.status_code >= 400, (
            f"Invalid price {price} should be rejected; service returned HTTP {response.status_code}"
        )
        return

    if case_id == "FM_00_04":
        payload, response = _create(http, 350000, request)
        assert response.status_code == 201, response.text
        created = response.json()
        fetched_response = http.get(f"{API_URL}/invoice-service/invoices/{created['id']}", timeout=20)
        assert fetched_response.ok, f"Cannot reload invoice: HTTP {fetched_response.status_code}"
        fetched = fetched_response.json()
        assert fetched["totalAmount"] == sum(i["quantity"] * i["unitPrice"] for i in fetched["items"])
        assert fetched["items"] == created["items"]
        query = (
            "SELECT i.total_amount, COUNT(ii.id), COALESCE(SUM(ii.quantity * ii.unit_price),0) "
            "FROM invoice i LEFT JOIN invoice_item ii ON ii.invoice_id=i.id "
            f"WHERE i.id='{created['id']}' GROUP BY i.id"
        )
        container = os.getenv("SQA_POSTGRES_CONTAINER", "dental-local-postgres-1")
        db_check = subprocess.run(
            ["docker", "exec", container, "psql", "-U", "postgres", "-d", "invoice-service", "-t", "-A", "-F", "|", "-c", query],
            check=True, capture_output=True, text=True, timeout=30,
        )
        db_values = db_check.stdout.strip().split("|")
        assert len(db_values) == 3, f"Invoice row/item rows were not found in DB: {db_check.stdout!r}"
        db_total, db_item_count, db_item_total = map(int, db_values)
        assert db_total == created["totalAmount"]
        assert db_item_count == len(created["items"])
        assert db_item_total == db_total
        return

    raise AssertionError(f"No automation mapping for {case_id}")
