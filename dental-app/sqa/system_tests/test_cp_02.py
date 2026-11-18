"""CP_02 — all 45 workbook cases for Create Prescription."""
import pytest
import re
import time
import uuid
from datetime import date, timedelta
from selenium.webdriver.common.by import By
from selenium.webdriver.common.keys import Keys
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

from conftest import (
    API_URL,
    TIMEOUT,
    _psql,
    choose_medicine,
    create_disposable_rx_case,
    create_sqa_medicine,
    expand_first_item,
    medicine_search,
    open_prescription_form,
    open_prescription_form_for_marker,
    remove_first_item,
    save_evidence,
    set_sqa_lot_quantity,
)


CASES = [
    *(f"CP_02_UI_{i:02}" for i in range(1, 27)),
    *(f"CP_02_FN_{i:02}" for i in range(1, 20)),
]


def _added_names(d):
    return [e.text.strip() for e in d.find_elements(By.XPATH, '//h3[contains(@class,"font-semibold")]') if e.is_displayed()]


def _add_and_expand(d, query="Amox"):
    name = choose_medicine(d, query)
    WebDriverWait(d, TIMEOUT).until(lambda x: name in x.find_element(By.TAG_NAME, "body").text)
    expand_first_item(d)
    return name


def _status_for_history(http, history_id):
    response = http.get(
        f"{API_URL}/prescription-billing-service/prescription-billings/medical-history/{history_id}/status",
        timeout=15,
    )
    assert response.ok, f"Prescription status API returned HTTP {response.status_code}: {response.text}"
    return response.json().get("status", "NONE")


def _wait_for_rx_success(d, http, context):
    WebDriverWait(d, TIMEOUT).until(
        lambda driver: "Tạo đơn thuốc thành công" in driver.find_element(By.TAG_NAME, "body").text
        or _status_for_history(http, context["medical_history_id"]) == "RELEASED"
    )
    assert _status_for_history(http, context["medical_history_id"]) == "RELEASED", (
        "Success notification appeared, but the backend did not reach RELEASED"
    )


def _wait_for_rx_stock_failure(d):
    try:
        WebDriverWait(d, TIMEOUT).until(
            lambda driver: "không đủ số lượng tồn kho" in driver.find_element(By.TAG_NAME, "body").text.lower()
            or "Không thể tạo đơn thuốc" in driver.find_element(By.TAG_NAME, "body").text
            or "Lỗi kỹ thuật" in driver.find_element(By.TAG_NAME, "body").text
        )
    except Exception as error:
        raise AssertionError(f"No stock-failure notification. URL={d.current_url}; page={d.find_element(By.TAG_NAME, 'body').text}") from error
    body = d.find_element(By.TAG_NAME, "body").text.lower()
    assert "không đủ số lượng tồn kho" in body or "lỗi kỹ thuật" in body


def _fill_directions(d):
    for selector, value in (
        ('input[placeholder="VD: 1 viên/lần"]', "1 viên/lần"),
        ('input[placeholder="VD: 3 lần/ngày"]', "3 lần/ngày"),
        ('input[placeholder="VD: 7 ngày"]', "7 ngày"),
        ('textarea[placeholder^="VD: Uống sau ăn"]', "Uống sau ăn"),
    ):
        control = d.find_element(By.CSS_SELECTOR, selector)
        control.clear()
        control.send_keys(value)


def _submit_button(d):
    return d.find_element(By.XPATH, '//button[contains(.,"Lưu đơn thuốc")]')


def _set_quantity(d, value: int):
    field = d.find_element(By.CSS_SELECTOR, 'input[type="number"]')
    field.click()
    field.send_keys(Keys.CONTROL, "a")
    field.send_keys(str(value))
    field.send_keys(Keys.TAB)
    assert field.get_attribute("value") == str(value), f"Could not set medicine quantity to {value}"


@pytest.mark.parametrize("case_id", CASES, ids=CASES)
def test_cp_02(case_id, doctor, http, request):
    d = doctor

    # Seed disposable records before the form loads its medicine catalog.
    rx_context = None
    fixture_medicine = None
    if case_id == "CP_02_FN_05":
        create_sqa_medicine(request, f"Amoxicillin Stella 500mg SQA-{uuid.uuid4().hex[:6]}", 50)
        create_sqa_medicine(request, f"Amoxicillin Mekophar 500mg SQA-{uuid.uuid4().hex[:6]}", 50)
    elif case_id == "CP_02_FN_07":
        fixture_medicine = create_sqa_medicine(request, f"SQA test low stock {uuid.uuid4().hex[:6]}", 2)
    elif case_id == "CP_02_FN_08":
        fixture_medicine = create_sqa_medicine(request, f"SQA test out of stock {uuid.uuid4().hex[:6]}", 0)
    elif case_id in ("CP_02_FN_11", "CP_02_FN_12", "CP_02_FN_13", "CP_02_FN_14", "CP_02_FN_15"):
        rx_context = create_disposable_rx_case(d, http, request, case_id)
        quantity = 5 if case_id == "CP_02_FN_11" else 50
        fixture_medicine = create_sqa_medicine(request, f"SQA {case_id} medicine {uuid.uuid4().hex[:6]}", quantity)
    elif case_id in ("CP_02_FN_16", "CP_02_FN_17", "CP_02_FN_18"):
        days = {"CP_02_FN_16": -1, "CP_02_FN_17": 0, "CP_02_FN_18": 1}[case_id]
        rx_context = create_disposable_rx_case(d, http, request, case_id)
        fixture_medicine = create_sqa_medicine(
            request,
            f"SQA {case_id} expiry medicine {uuid.uuid4().hex[:6]}",
            5,
            date.today() + timedelta(days=days),
        )

    if rx_context:
        open_prescription_form_for_marker(d, rx_context["marker"])
        # CreatePrescription connects and subscribes to the doctor's WebSocket topic on mount.
        time.sleep(1)
    else:
        open_prescription_form(d)
    body = d.find_element(By.TAG_NAME, "body")

    if case_id == "CP_02_UI_01":
        assert "Kê Đơn Thuốc" in body.text and "Tìm kiếm và thêm thuốc" in body.text
    elif case_id == "CP_02_UI_02":
        assert "Thông Tin Bệnh Nhân" in body.text and medicine_search(d).is_displayed()
        assert d.find_element(By.XPATH, '//button[contains(.,"Lưu đơn thuốc")]').is_displayed()
        _add_and_expand(d)
        for ph in ("VD: 1 viên/lần", "VD: 3 lần/ngày", "VD: 7 ngày"):
            assert d.find_element(By.CSS_SELECTOR, f'input[placeholder="{ph}"]').is_displayed()
    elif case_id == "CP_02_UI_03":
        active = d.switch_to.active_element
        active.send_keys(Keys.TAB)
        assert d.switch_to.active_element != active
    elif case_id == "CP_02_UI_04":
        medicine_search(d).send_keys("Amox")
        WebDriverWait(d, TIMEOUT).until(lambda x: "Amox" in x.find_element(By.TAG_NAME, "body").text)
        assert "Amox" in d.find_element(By.TAG_NAME, "body").text
    elif case_id == "CP_02_UI_05":
        medicine_search(d).send_keys("WWW-SQA-NOT-FOUND")
        WebDriverWait(d, TIMEOUT).until(lambda x: "Không tìm thấy thuốc phù hợp" in x.find_element(By.TAG_NAME, "body").text)
        assert "Không tìm thấy thuốc phù hợp" in d.find_element(By.TAG_NAME, "body").text
    elif case_id == "CP_02_UI_06":
        choose_medicine(d, "Amox")
        assert medicine_search(d).get_attribute("value") == ""
        assert _added_names(d)
    elif case_id == "CP_02_UI_07":
        assert "Chưa có thuốc trong đơn" in body.text
    elif case_id == "CP_02_UI_08":
        field = medicine_search(d)
        field.send_keys("a")
        WebDriverWait(d, TIMEOUT).until(lambda x: x.find_elements(By.XPATH, '//button[.//p[contains(@class,"font-semibold")]]'))
        assert d.find_elements(By.XPATH, '//button[.//p[contains(@class,"font-semibold")]]')
    elif case_id == "CP_02_UI_09":
        _add_and_expand(d)
        qty = d.find_element(By.CSS_SELECTOR, 'input[type="number"]')
        assert qty.get_attribute("value") == "1"
    elif case_id == "CP_02_UI_10":
        _add_and_expand(d)
        qty = d.find_element(By.CSS_SELECTOR, 'input[type="number"]')
        qty.send_keys("abc**")
        assert qty.get_attribute("value").isdigit()
    elif case_id == "CP_02_UI_11":
        _add_and_expand(d)
        qty = d.find_element(By.CSS_SELECTOR, 'input[type="number"]')
        qty.send_keys(Keys.CONTROL, "a"); qty.send_keys("009"); qty.send_keys(Keys.TAB)
        assert qty.get_attribute("value") == "9"
    elif case_id in ("CP_02_UI_12", "CP_02_UI_13", "CP_02_UI_14",
                     "CP_02_UI_15", "CP_02_UI_16", "CP_02_UI_17",
                     "CP_02_UI_18", "CP_02_UI_19", "CP_02_UI_20",
                     "CP_02_UI_21", "CP_02_UI_22", "CP_02_UI_23"):
        _add_and_expand(d)
        field_map = {
            "CP_02_UI_12": ("input[placeholder='VD: 1 viên/lần']", "1 viên/lần", "default"),
            "CP_02_UI_13": ("input[placeholder='VD: 1 viên/lần']", "10 viên/lần", "input"),
            "CP_02_UI_14": ("input[placeholder='VD: 1 viên/lần']", "Liều dùng dài " + "x"*55, "input"),
            "CP_02_UI_15": ("input[placeholder='VD: 3 lần/ngày']", "3 lần/ngày", "default"),
            "CP_02_UI_16": ("input[placeholder='VD: 3 lần/ngày']", "4 lần/ngày", "input"),
            "CP_02_UI_17": ("input[placeholder='VD: 3 lần/ngày']", "Tần suất dài " + "x"*55, "input"),
            "CP_02_UI_18": ("input[placeholder='VD: 7 ngày']", "7 ngày", "default"),
            "CP_02_UI_19": ("input[placeholder='VD: 7 ngày']", "10 ngày", "input"),
            "CP_02_UI_20": ("input[placeholder='VD: 7 ngày']", "Thời gian dài " + "x"*55, "input"),
            "CP_02_UI_21": ("textarea[placeholder^='VD: Uống sau ăn']", "", "default"),
            "CP_02_UI_22": ("textarea[placeholder^='VD: Uống sau ăn']", "Dùng sau ăn 1 tiếng", "input"),
            "CP_02_UI_23": ("textarea[placeholder^='VD: Uống sau ăn']", "Hướng dẫn dài " + "x"*55, "input"),
        }
        selector, value, mode = field_map[case_id]
        control = d.find_element(By.CSS_SELECTOR, selector)
        if mode == "default":
            if case_id == "CP_02_UI_21":
                assert control.get_attribute("value"), "Expected a non-empty default direction"
            else:
                assert control.get_attribute("value") == value, f"Expected default value {value!r}, got {control.get_attribute('value')!r}"
        else:
            control.send_keys(value)
            assert control.get_attribute("value") == value
    elif case_id == "CP_02_UI_24":
        button = d.find_element(By.XPATH, '//button[contains(.,"Lưu đơn thuốc")]')
        assert button.is_enabled(), "Save button is disabled before an item is added"
    elif case_id == "CP_02_UI_25":
        name = choose_medicine(d, "Amox")
        assert name in d.find_element(By.TAG_NAME, "body").text
        remove_first_item(d)
        assert name not in _added_names(d), "Removed medicine remains in the prescription list"
    elif case_id == "CP_02_UI_26":
        _add_and_expand(d)
        d.find_element(By.XPATH, '//button[contains(.,"1 viên x 3 lần/ngày x 7 ngày")]').click()
        assert d.find_element(By.CSS_SELECTOR, 'input[placeholder="VD: 1 viên/lần"]').get_attribute("value")
        assert d.find_element(By.CSS_SELECTOR, 'input[placeholder="VD: 3 lần/ngày"]').get_attribute("value")
        assert d.find_element(By.CSS_SELECTOR, 'input[placeholder="VD: 7 ngày"]').get_attribute("value")
    elif case_id == "CP_02_FN_01":
        name = choose_medicine(d, "Amoxicillin")
        assert name in _added_names(d) and medicine_search(d).get_attribute("value") == ""
    elif case_id == "CP_02_FN_02":
        name = choose_medicine(d, "Amoxicillin")
        choose_medicine(d, "Amoxicillin")
        assert _added_names(d).count(name) == 1
        assert "Thuốc này đã có trong đơn" in body.text
    elif case_id in ("CP_02_FN_03", "CP_02_FN_04"):
        first = choose_medicine(d, "Amox")
        second = choose_medicine(d, "Para")
        assert first != second and len(_added_names(d)) >= 2
        if case_id == "CP_02_FN_03":
            third = choose_medicine(d, "a", index=2)
            assert len(_added_names(d)) >= 3 and third not in (first, second)
    elif case_id == "CP_02_FN_05":
        choose_medicine(d, "Amoxicillin Stella")
        choose_medicine(d, "Amoxicillin Mekophar")
        assert len(_added_names(d)) == 2
    elif case_id == "CP_02_FN_06":
        allergy = d.find_elements(By.XPATH, '//*[contains(.,"CẢNH BÁO DỊ ỨNG")]')
        if not allergy:
            pytest.skip("Prerequisite missing: patient fixture has no allergy record")
        choose_medicine(d, "Amoxicillin")
        assert "dị ứng" in d.find_element(By.TAG_NAME, "body").text.lower()
    elif case_id == "CP_02_FN_07":
        medicine_search(d).send_keys("test")
        choices = d.find_elements(By.XPATH, '//button[.//span[contains(.,"Sắp hết")]]')
        target = [choice for choice in choices if fixture_medicine["name"] in choice.text]
        assert target, f"Low-stock fixture {fixture_medicine['name']} was not shown"
        target[0].click()
        assert fixture_medicine["name"] in _added_names(d)
    elif case_id == "CP_02_FN_08":
        medicine_search(d).send_keys("test")
        choices = d.find_elements(By.XPATH, '//button[.//span[contains(.,"Hết hàng")]]')
        target = [choice for choice in choices if fixture_medicine["name"] in choice.text]
        assert target, f"Out-of-stock fixture {fixture_medicine['name']} was not shown"
        assert not target[0].is_enabled()
    elif case_id == "CP_02_FN_09":
        medicine_search(d).send_keys("amox")
        WebDriverWait(d, TIMEOUT).until(lambda x: x.find_elements(By.XPATH, '//button[.//p[contains(.,"Amoxicillin")]]'))
        assert d.find_elements(By.XPATH, '//button[.//p[contains(.,"Amoxicillin")]]')
    elif case_id == "CP_02_FN_10":
        _add_and_expand(d)
        body_text = d.find_element(By.TAG_NAME, "body").text
        match = re.search(r"Tồn kho:\s*(\d+)", body_text)
        assert match, f"Stock amount not shown on the prescription form: {body_text}"
        stock = int(match.group(1))
        qty = d.find_element(By.CSS_SELECTOR, 'input[type="number"]')
        qty.clear(); qty.send_keys(str(stock + 1))
        d.find_element(By.XPATH, '//button[contains(.,"Lưu đơn thuốc")]').click()
        assert "vượt quá tồn kho" in d.find_element(By.TAG_NAME, "body").text.lower()
    elif case_id == "CP_02_FN_11":
        choose_medicine(d, fixture_medicine["name"])
        expand_first_item(d)
        _set_quantity(d, 5)
        # The UI has cached stock=5. Deplete it after selection so the asynchronous
        # reservation step must reject the stale quantity at the service boundary.
        set_sqa_lot_quantity(fixture_medicine["lot_id"], 0)
        _submit_button(d).click()
        _wait_for_rx_stock_failure(d)
        assert _status_for_history(http, rx_context["medical_history_id"]) != "RELEASED"
    elif case_id == "CP_02_FN_12":
        choose_medicine(d, fixture_medicine["name"])
        expand_first_item(d)
        _set_quantity(d, 5)
        _fill_directions(d)
        _submit_button(d).click()
        _wait_for_rx_success(d, http, rx_context)
    elif case_id == "CP_02_FN_13":
        choose_medicine(d, fixture_medicine["name"])
        # Leave dosage, frequency, duration, and instruction blank as the case specifies.
        _submit_button(d).click()
        _wait_for_rx_success(d, http, rx_context)
    elif case_id == "CP_02_FN_14":
        choose_medicine(d, fixture_medicine["name"])
        expand_first_item(d)
        _fill_directions(d)
        before = int(_psql("inventory-service", f"SELECT COUNT(*) FROM dispense_order WHERE medical_history_id='{rx_context['medical_history_id']}';"))
        button = _submit_button(d)
        d.execute_script("arguments[0].click(); arguments[0].click();", button)
        _wait_for_rx_success(d, http, rx_context)
        WebDriverWait(d, TIMEOUT).until(
            lambda _: int(_psql("inventory-service", f"SELECT COUNT(*) FROM dispense_order WHERE medical_history_id='{rx_context['medical_history_id']}';")) >= before + 1
        )
        after = int(_psql("inventory-service", f"SELECT COUNT(*) FROM dispense_order WHERE medical_history_id='{rx_context['medical_history_id']}';"))
        assert after - before == 1, f"Double click created {after - before} dispense orders"
    elif case_id == "CP_02_FN_15":
        choose_medicine(d, fixture_medicine["name"])
        expand_first_item(d)
        _fill_directions(d)
        button = _submit_button(d)
        button.click()
        WebDriverWait(d, 3).until(lambda _: "Đang xử lý" in button.text)
        _wait_for_rx_success(d, http, rx_context)
    elif case_id in ("CP_02_FN_16", "CP_02_FN_17"):
        choose_medicine(d, fixture_medicine["name"])
        _submit_button(d).click()
        _wait_for_rx_stock_failure(d)
        assert _status_for_history(http, rx_context["medical_history_id"]) != "RELEASED"
    elif case_id == "CP_02_FN_18":
        choose_medicine(d, fixture_medicine["name"])
        expand_first_item(d)
        _fill_directions(d)
        _submit_button(d).click()
        _wait_for_rx_success(d, http, rx_context)
    elif case_id == "CP_02_FN_19":
        name = choose_medicine(d, "Amoxicillin")
        remove_first_item(d)
        name2 = choose_medicine(d, "Amoxicillin")
        assert name == name2 and _added_names(d).count(name) == 1

    save_evidence(d, case_id)
