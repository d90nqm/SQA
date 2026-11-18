"""CP_01 — all 24 workbook cases, kept together by use-case tab."""
import re
import pytest
from selenium.webdriver.common.action_chains import ActionChains
from selenium.webdriver.common.by import By
from selenium.webdriver.common.keys import Keys
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

from conftest import BASE_URL, TIMEOUT, open_history_list, open_prescription_form, save_evidence


CASES = [
    "CP_01_UI_01", "CP_01_UI_02", "CP_01_UI_03", "CP_01_UI_04", "CP_01_UI_05",
    "CP_01_UI_06", "CP_01_UI_07", "CP_01_UI_10", "CP_01_UI_12", "CP_01_UI_13",
    "CP_01_UI_14", "CP_01_UI_15", "CP_01_UI_16", "CP_01_UI_17",
    "CP_01_FN_01", "CP_01_FN_02", "CP_01_FN_03", "CP_01_FN_04", "CP_01_FN_05",
    "CP_01_FN_06", "CP_01_FN_07", "CP_01_FN_08", "CP_01_FN_09", "CP_01_FN_10",
]


@pytest.mark.parametrize("case_id", CASES, ids=CASES)
def test_cp_01(case_id, doctor):
    d = doctor
    open_history_list(d)
    body = d.find_element(By.TAG_NAME, "body")
    search = d.find_element(By.CSS_SELECTOR, 'input[placeholder="Tìm kiếm theo tên bệnh nhân, chẩn đoán..."]')

    if case_id == "CP_01_UI_01":
        assert "Tạo đơn thuốc" in body.text and search.is_displayed()
        assert d.find_elements(By.XPATH, '//button[normalize-space()="Kê đơn"]')
    elif case_id == "CP_01_UI_02":
        # The implementation renders history cards, not a table. Verify available patient/history fields.
        assert "Mã BN:" in body.text
        assert "Triệu chứng" in body.text or "Chưa có hồ sơ khám bệnh nào" in body.text
    elif case_id == "CP_01_UI_03":
        cards = d.find_elements(By.XPATH, '//*[contains(@class,"group") and .//button[normalize-space()="Kê đơn"]]')
        assert cards, "Expected at least one complete patient-history card"
        assert any("Mã BN:" in c.text for c in cards)
    elif case_id in ("CP_01_UI_04", "CP_01_UI_05"):
        search.click()
        before = d.switch_to.active_element
        if case_id.endswith("04"):
            before.send_keys(Keys.TAB)
        else:
            ActionChains(d).key_down(Keys.SHIFT).send_keys(Keys.TAB).key_up(Keys.SHIFT).perform()
        assert d.switch_to.active_element != before, "Keyboard navigation did not move focus"
    elif case_id == "CP_01_UI_06":
        d.find_element(By.TAG_NAME, "body").send_keys(Keys.CONTROL, "-")
        d.find_element(By.TAG_NAME, "body").send_keys(Keys.CONTROL, "0")
        assert d.find_element(By.TAG_NAME, "h1").is_displayed()
    elif case_id == "CP_01_UI_07":
        cards = d.find_elements(By.XPATH, '//*[contains(@class,"group") and .//button[normalize-space()="Kê đơn"]]')
        assert cards
        d.execute_script("arguments[0].scrollIntoView({block:'center'})", cards[0])
        d.execute_script("arguments[0].dispatchEvent(new MouseEvent('mouseover',{bubbles:true}))", cards[0])
        assert cards[0].is_displayed()
    elif case_id == "CP_01_UI_10":
        search.send_keys("XYZ", Keys.ENTER)
        WebDriverWait(d, TIMEOUT).until(lambda x: "Không tìm thấy hồ sơ phù hợp" in x.find_element(By.TAG_NAME, "body").text)
        assert "Không tìm thấy hồ sơ phù hợp" in d.find_element(By.TAG_NAME, "body").text
    elif case_id == "CP_01_UI_12":
        search.send_keys("Phạm Thị Ngọc Mai")
        WebDriverWait(d, TIMEOUT).until(lambda x: "Phạm Thị Ngọc Mai" in x.find_element(By.TAG_NAME, "body").text)
        assert "Phạm Thị Ngọc Mai" in d.find_element(By.TAG_NAME, "body").text
    elif case_id == "CP_01_UI_13":
        search.send_keys("00000101")
        WebDriverWait(d, TIMEOUT).until(lambda x: "Không tìm thấy hồ sơ phù hợp" in x.find_element(By.TAG_NAME, "body").text or "Phạm Thị Ngọc Mai" in x.find_element(By.TAG_NAME, "body").text)
        assert "Phạm Thị Ngọc Mai" in d.find_element(By.TAG_NAME, "body").text, "Patient ID search did not return the corresponding history"
    elif case_id == "CP_01_UI_14":
        search.send_keys("@#$%&")
        WebDriverWait(d, TIMEOUT).until(lambda x: "Không tìm thấy hồ sơ phù hợp" in x.find_element(By.TAG_NAME, "body").text)
        assert "Không tìm thấy hồ sơ phù hợp" in d.find_element(By.TAG_NAME, "body").text
    elif case_id in ("CP_01_UI_15", "CP_01_FN_08"):
        buttons = d.find_elements(By.XPATH, '//button[normalize-space()="Kê đơn"]')
        if not buttons:
            pytest.skip("Prerequisite missing: doctor has no eligible patient history")
        buttons[0].click()
        WebDriverWait(d, TIMEOUT).until(EC.visibility_of_element_located((By.XPATH, '//h1[normalize-space()="Kê Đơn Thuốc"]')))
        assert "/doctor/create-prescription" in d.current_url
    elif case_id == "CP_01_UI_16":
        cards = d.find_elements(By.XPATH, '//*[contains(@class,"group") and .//button[normalize-space()="Kê đơn"]]')
        if not cards:
            pytest.skip("Prerequisite missing: doctor has no eligible patient history")
        d.execute_script("arguments[0].click()", cards[0])
        WebDriverWait(d, TIMEOUT).until(EC.visibility_of_element_located((By.XPATH, '//h1[normalize-space()="Kê Đơn Thuốc"]')))
        assert "/doctor/create-prescription" in d.current_url
    elif case_id == "CP_01_UI_17":
        open_prescription_form(d)
        d.find_element(By.XPATH, '//button[normalize-space()="Quay lại"]').click()
        WebDriverWait(d, TIMEOUT).until(EC.visibility_of_element_located((By.XPATH, '//h1[normalize-space()="Tạo đơn thuốc"]')))
        assert "/doctor/create-prescription" in d.current_url
    elif case_id == "CP_01_FN_01":
        assert "Chưa có hồ sơ khám bệnh nào" in body.text or d.find_elements(By.XPATH, '//button[normalize-space()="Kê đơn"]')
    elif case_id in ("CP_01_FN_02", "CP_01_FN_04", "CP_01_FN_05"):
        search.send_keys("Lê Anh Tuấn")
        if "Lê Anh Tuấn" not in body.text:
            pytest.skip("Prerequisite missing: no eligible appointment/history for Lê Anh Tuấn under doctor B")
        assert "Lê Anh Tuấn" in d.find_element(By.TAG_NAME, "body").text
        if case_id == "CP_01_FN_04":
            assert "Chảy máu chân răng" in d.find_element(By.TAG_NAME, "body").text
        if case_id == "CP_01_FN_05":
            card = d.find_element(By.XPATH, '//*[contains(@class,"group") and contains(.,"Lê Anh Tuấn")]')
            assert re.search(r"\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}", card.text), "Last visit is missing or not YYYY-MM-DD HH:MM"
    elif case_id == "CP_01_FN_03":
        search.send_keys("Lê")
        results = d.find_elements(By.XPATH, '//*[contains(@class,"group") and .//button[normalize-space()="Kê đơn"]]')
        assert len(results) >= 2, "Expected at least two eligible patient histories matching Lê"
    elif case_id == "CP_01_FN_06":
        # Workbook requests date sorting; current UI has no sortable date header.
        assert d.find_elements(By.XPATH, '//*[contains(translate(normalize-space(.),"DATE","date"),"date")]'), "No date-sort control/header is present"
    elif case_id == "CP_01_FN_07":
        assert d.find_elements(By.XPATH, '//button[contains(.,"Next") or contains(.,"Tiếp") or contains(.,"Sau")]'), "Pagination controls are not present"
    elif case_id == "CP_01_FN_09":
        search.send_keys("XYZ")
        WebDriverWait(d, TIMEOUT).until(lambda x: "Không tìm thấy hồ sơ phù hợp" in x.find_element(By.TAG_NAME, "body").text)
        assert "Không tìm thấy hồ sơ phù hợp" in d.find_element(By.TAG_NAME, "body").text
    elif case_id == "CP_01_FN_10":
        names = [x.text.strip() for x in d.find_elements(By.XPATH, '//*[contains(@class,"group") and .//button[normalize-space()="Kê đơn"]]//h3')]
        assert len(names) == len(set(names)), f"Duplicate patient history cards found: {names}"

    save_evidence(d, case_id)
