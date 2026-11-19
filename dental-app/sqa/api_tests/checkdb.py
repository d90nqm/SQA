"""Loopback-only fixture and CheckDB service for the PA Postman collection."""

import argparse
import hmac
import json
import re
import secrets
import subprocess
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.request import Request, urlopen
from uuid import UUID, uuid4


ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ["docker", "compose", "-f", str(ROOT / "compose.local.json")]
# Send UTC to match the appointment service's Jackson date-time deserialization.
VISIT_ZONE = timezone.utc
PATIENT_A = "00000000-0000-0000-0000-000000000101"
RUNS = {}


def docker(*args):
    result = subprocess.run([*COMPOSE, *args], cwd=ROOT, capture_output=True,
                            encoding="utf-8", errors="replace", timeout=25)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout.strip()


def sql(database, statement):
    return docker("exec", "-T", "postgres", "psql", "-X", "-qAt", "-v",
                  "ON_ERROR_STOP=1", "-U", "postgres", "-d", database, "-c", statement)


def rowset(database, select):
    return json.loads(sql(database, f"SELECT coalesce(json_agg(q), '[]') FROM ({select}) q"))


def redis(*args):
    return docker("exec", "-T", "redis", "redis-cli", "--raw", *args)


def service():
    rows = rowset("appointment-service", """
        SELECT id, service_time AS minutes, price::integer FROM medical_service
        WHERE status = 'ACTIVE' AND service_time BETWEEN 1 AND 120 ORDER BY id LIMIT 1
    """)
    if not rows:
        raise RuntimeError("No active seeded medical service exists.")
    return rows[0]


def booking(fixture, label):
    start = fixture["slots"][label]
    doctor_id, patient_id = fixture["doctorId"], fixture["patientAId"]
    rows = rowset("appointment-service", f"""
        SELECT a.id, a.doctor_id, a.patient_id, a.status,
            coalesce((SELECT json_agg(s.medical_service_id ORDER BY s.medical_service_id)
                      FROM appointment_medical_service s WHERE s.appointment_id = a.id), '[]') AS services
        FROM appointment a
        WHERE a.doctor_id = '{doctor_id}' AND a.appointment_start_time = '{start}'
        ORDER BY a.id
    """)
    key = redis_key(fixture, label)
    lock = redis("GET", key)
    return {"rows": rows, "lock": lock, "patientId": patient_id}


def redis_key(fixture, label):
    start = datetime.fromisoformat(fixture["slots"][label])
    return f"slot:{fixture['doctorId']}:{start:%Y%m%d}:{start:%H%M}"


def create_patient(fixture, label):
    phone = f"089{secrets.randbelow(10000000):07d}"
    email = f"pa02-{uuid4()}@sqa.example"
    password = secrets.token_hex(12)
    fixture["temporaryEmails"].append(email)
    # Use the app's normal user creation path to provision its password and patient profile.
    request = Request("http://127.0.0.1:8080/user-service/users",
                      method="POST", headers={"Content-Type": "application/json"},
                      data=json.dumps({"email": email, "phone": phone, "password": password,
                                       "fullName": f"SQA PA02 Patient {label}", "roleNames": ["PATIENT"]}).encode())
    with urlopen(request, timeout=15) as response:
        if response.status != 201:
            raise RuntimeError(f"Patient setup HTTP {response.status}")
        patient_id = str(UUID(json.load(response)["id"]))
    profile = sql("patient-service", f"SELECT count(*) FROM patient WHERE user_id = '{patient_id}'")
    if profile != "1":
        raise RuntimeError("Temporary patient was created without a patient profile.")
    return patient_id, phone, password


def make_booking(run_id, configured_phone):
    doctor_id = sql("doctor-service", "SELECT user_id FROM doctor ORDER BY user_id LIMIT 1")
    doctor_id = str(UUID(doctor_id))
    chosen = service()
    fixture = {"kind": "PA02", "doctorId": doctor_id, "serviceId": chosen["id"],
               "slots": {}, "temporaryEmails": []}
    RUNS[run_id] = fixture
    if configured_phone:
        if not re.fullmatch(r"[0-9]{8,15}", configured_phone):
            raise ValueError("Patient phone must be an 8–15 digit string.")
        actor_id = sql("user-service", f"""
            SELECT u.id FROM users u JOIN user_role ur ON u.id = ur.user_id
            JOIN role r ON r.id = ur.role_id
            WHERE u.phone = '{configured_phone}' AND r.role_name = 'PATIENT' AND u.is_active
        """)
        if not actor_id:
            raise ValueError("Configured patient does not exist or is inactive. Clear the patient credentials to use a fixture.")
        fixture["patientAId"] = str(UUID(actor_id))
    else:
        actor_id, phone, password = create_patient(fixture, "A")
        fixture.update({"patientAId": actor_id, "fixturePhone": phone, "fixturePassword": password})
    fixture["patientBId"], _, _ = create_patient(fixture, "B")

    patient_id = fixture["patientAId"]
    base = datetime.now(VISIT_ZONE).replace(hour=8, minute=0, second=0, microsecond=0)
    base += timedelta(days=60 + secrets.randbelow(365))
    for label, days in (("hold", 0), ("create", 1), ("absent", 2)):
        # These dates are independent of the seeded appointments and of other runs.
        start = base + timedelta(days=days, minutes=secrets.randbelow(360))
        state = {"doctorId": doctor_id, "patientAId": patient_id, "slots": {label: start.isoformat()}}
        prefix = redis_key(state, label).rsplit(":", 1)[0] + ":*"
        existing = sql("appointment-service", f"""
            SELECT count(*) FROM appointment WHERE doctor_id = '{doctor_id}'
              AND appointment_start_time >= '{start.date()}T00:00:00+00:00'
              AND appointment_start_time < '{start.date() + timedelta(days=1)}T00:00:00+00:00'
        """)
        if existing != "0" or redis("--scan", "--pattern", prefix):
            raise RuntimeError("Generated test date is in use. Run again to choose a new date.")
        fixture["slots"][label] = start.isoformat()
    return fixture


def make_prescription(run_id, doctor_id):
    doctor_id = str(UUID(doctor_id))
    patient_id = sql("patient-service", f"SELECT user_id FROM patient WHERE user_id = '{PATIENT_A}'")
    patient_id = str(UUID(patient_id))
    fixture = {"kind": "PA01", "doctorId": doctor_id, "patientId": patient_id,
               "histories": {}, "medicine": {}}
    RUNS[run_id] = fixture
    chosen = service()
    for label, count in (("valid", 12), ("insufficient", 4)):
        appointment_id, history_id, invoice_id = (str(uuid4()) for _ in range(3))
        fixture["histories"][label] = {
            "appointmentId": appointment_id, "historyId": history_id, "invoiceId": invoice_id,
        }
        sql("appointment-service", f"""
            BEGIN;
            INSERT INTO appointment (id, doctor_id, patient_id, appointment_start_time,
                appointment_end_time, status, created_at, updated_at)
            VALUES ('{appointment_id}', '{doctor_id}', '{patient_id}', now(),
                now() + interval '30 minutes', 'IN_PROGRESS', now(), now());
            INSERT INTO appointment_medical_service VALUES ('{appointment_id}', '{chosen['id']}');
            COMMIT;
        """)
        sql("patient-service", f"""
            INSERT INTO medical_history (id, appointment_id, symptoms, created_at, updated_at, patient_profile_id)
            VALUES ('{history_id}', '{appointment_id}', 'SQA {run_id} {label}', now(), now(), '{patient_id}');
        """)
        sql("invoice-service", f"""
            BEGIN;
            INSERT INTO invoice (id, appointment_id, currency, issue_at, total_amount, patient_total_pay,
                insurance_total_pay, status)
            VALUES ('{invoice_id}', '{appointment_id}', 'VND', now(), {chosen['price']}, {chosen['price']}, 0, 'PENDING');
            INSERT INTO invoice_item (id, invoice_id, service_type, reference_id, description,
                quantity, unit_price, patient_pay_amount, insurance_pay_amount)
            VALUES ('{uuid4()}', '{invoice_id}', 'MEDICAL_SERVICE', '{chosen['id']}', 'SQA visit',
                1, {chosen['price']}, {chosen['price']}, 0);
            COMMIT;
        """)
        medicine_id = str(uuid4())
        medicine_name = f"SQA PA01 {medicine_id}"
        lots = [str(uuid4()) for _ in range(2)]
        fixture["medicine"][label] = {"id": medicine_id, "name": medicine_name,
                                       "lots": lots, "quantities": [count, count]}
        sql("inventory-service", f"""
            BEGIN;
            INSERT INTO medicine (id, name, unit, description, sale_price)
            VALUES ('{medicine_id}', '{medicine_name}', 'tablet', 'SQA API fixture', 1000);
            INSERT INTO inventory_lot (id, lot_no, expire_date, quantity_on_hand, cost_price, medicine_id)
            VALUES ('{lots[0]}', 'SQA-{lots[0]}', '{date.today() + timedelta(days=30)}', {count}, 500, '{medicine_id}'),
                   ('{lots[1]}', 'SQA-{lots[1]}', '{date.today() + timedelta(days=60)}', {count}, 500, '{medicine_id}');
            COMMIT;
        """)
    return fixture


def rx(fixture, label, inspect_failure=False):
    medicine = fixture["medicine"][label]
    history = fixture["histories"][label]
    inventory = rowset("inventory-service", f"""
        SELECT id, prescription, status, doctor_id, medical_history_id
        FROM dispense_order WHERE medical_history_id = '{history['historyId']}' ORDER BY id
    """)
    lots = rowset("inventory-service", f"""
        SELECT id, expire_date, quantity_on_hand FROM inventory_lot
        WHERE medicine_id = '{medicine['id']}' ORDER BY expire_date
    """)
    invoice_items = rowset("invoice-service", f"""
        SELECT reference_id, quantity, unit_price FROM invoice_item
        WHERE invoice_id = '{history['invoiceId']}' AND service_type = 'MEDICINE'
    """)
    ledger = int(sql("inventory-service", f"""
        SELECT count(*) FROM stock_ledger s JOIN inventory_lot l ON l.id = s.inventory_lot_id
        WHERE l.medicine_id = '{medicine['id']}'
    """))
    insufficient = False
    if inspect_failure:
        logs = docker("logs", "--since", "10m", "--tail", "1500", "prescription-service")
        # The reservation command reports the unique fixture's medicine name and requested quantity.
        insufficient = any(medicine["name"] in line and "9999" in line for line in logs.splitlines())
    return {"orders": inventory, "lots": lots, "invoiceItems": invoice_items,
            "ledgerCount": ledger, "insufficientStockLogged": insufficient}


def cleanup_booking(fixture):
    doctor_id = fixture["doctorId"]
    patients = (fixture.get("patientAId"), fixture.get("patientBId"))
    for label, start in fixture["slots"].items():
        key = redis_key(fixture, label)
        # Remove either fixture patient's lock in case the duplicate hold unexpectedly succeeded.
        redis("EVAL", """
            local owner = redis.call('GET', KEYS[1])
            if owner and (string.find(owner, ARGV[1], 1, true) or string.find(owner, ARGV[2], 1, true)) then
                return redis.call('DEL', KEYS[1])
            end
            return 0
        """, "1", key, *patients)
        rows = booking(fixture, label)["rows"]
        for row in rows:
            owner = row["patient_id"]
            if owner not in patients:
                continue
            appointment_id = str(UUID(row["id"]))
            sql("appointment-service", f"""
                DELETE FROM appointment WHERE id = '{appointment_id}' AND doctor_id = '{doctor_id}'
                    AND patient_id = '{owner}' AND appointment_start_time = '{start}'
            """)
            # The notification message includes the first eight characters of the appointment ID.
            sql("notification-service", f"""
                DELETE FROM notification WHERE user_id = '{owner}' AND template_id = 'APPOINTMENT_CREATED'
                    AND right(message, 8) = '{appointment_id[:8]}'
            """)
        state = booking(fixture, label)
        if any(row["patient_id"] in patients for row in state["rows"]) or any(
            patient in state["lock"] for patient in patients
        ):
            raise RuntimeError(f"PA02 cleanup left fixture state for {label}.")
    for email in fixture["temporaryEmails"]:
        patient_id = sql("user-service", f"SELECT id FROM users WHERE email = '{email}'")
        if not patient_id:
            continue
        patient_id = str(UUID(patient_id))
        sql("patient-service", f"DELETE FROM patient WHERE user_id = '{patient_id}'")
        count = sql("user-service", f"DELETE FROM users WHERE id = '{patient_id}' AND email = '{email}'; "
                                    f"SELECT count(*) FROM users WHERE id = '{patient_id}'")
        if count != "0":
            raise RuntimeError("PA02 cleanup left a temporary user.")


def cleanup_prescription(fixture):
    for label, history in fixture["histories"].items():
        history_id, appointment_id, invoice_id = history["historyId"], history["appointmentId"], history["invoiceId"]
        medicine = fixture["medicine"].get(label)
        if medicine:
            medicine_id = medicine["id"]
            sql("inventory-service", f"""
                BEGIN;
                DELETE FROM stock_ledger WHERE inventory_lot_id IN
                    (SELECT id FROM inventory_lot WHERE medicine_id = '{medicine_id}');
                DELETE FROM dispense_order WHERE medical_history_id = '{history_id}';
                DELETE FROM medicine WHERE id = '{medicine_id}';
                COMMIT;
            """)
        claims = sql("invoice-service", f"SELECT insurance_claim_id FROM invoice WHERE id = '{invoice_id}'")
        if claims:
            claim_id = str(UUID(claims))
            sql("insurance-service", f"DELETE FROM insurance_claim WHERE id = '{claim_id}'")
        for database, table, identifier in (
            ("invoice-service", "invoice", invoice_id),
            ("patient-service", "medical_history", history_id),
            ("appointment-service", "appointment", appointment_id),
        ):
            remaining = sql(database, f"DELETE FROM {table} WHERE id = '{identifier}'; "
                                      f"SELECT count(*) FROM {table} WHERE id = '{identifier}'")
            if remaining != "0":
                raise RuntimeError(f"PA01 cleanup left a {table} fixture for {label}.")
        if medicine:
            state = rx(fixture, label)
            if state["orders"] or state["lots"] or state["invoiceItems"] or state["ledgerCount"]:
                raise RuntimeError(f"PA01 cleanup left inventory or invoice items for {label}.")


def finish(run_id):
    fixture = RUNS[run_id]
    cleanup = cleanup_booking if fixture["kind"] == "PA02" else cleanup_prescription
    cleanup(fixture)
    del RUNS[run_id]
    return {"clean": True}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if not hmac.compare_digest(self.headers.get("X-SQA-Key", ""), self.server.key):
            self.reply(403, {"error": "Set checkdbKey to the current terminal session key."})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 4096:
                raise ValueError("Invalid request size")
            body = json.loads(self.rfile.read(length))
            result = self.dispatch(self.path, body)
            self.reply(200, result)
        except (ValueError, KeyError, TypeError) as error:
            self.reply(400, {"error": str(error)})
        except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
            self.reply(503, {"error": str(error)})

    def dispatch(self, path, body):
        if path == "/v1/begin":
            run_id = str(uuid4())
            try:
                if body["kind"] == "PA01":
                    fixture = make_prescription(run_id, body["actorId"])
                elif body["kind"] == "PA02":
                    fixture = make_booking(run_id, body.get("patientPhone", ""))
                else:
                    raise ValueError("Unknown fixture kind")
            except Exception:
                if run_id in RUNS:
                    finish(run_id)
                raise
            return {"runId": run_id, **fixture}
        run_id = str(UUID(body["runId"]))
        fixture = RUNS[run_id]
        if path == "/v1/finish":
            return finish(run_id)
        if path == "/v1/booking" and fixture["kind"] == "PA02":
            return booking(fixture, body["label"])
        if path == "/v1/rx" and fixture["kind"] == "PA01":
            return rx(fixture, body["label"], body["label"] == "insufficient")
        raise ValueError("Unknown CheckDB operation")

    def reply(self, status, payload):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8795)
    args = parser.parse_args()
    try:
        sql("postgres", "SELECT 1")
        if redis("PING") != "PONG":
            raise RuntimeError("Redis did not answer PING.")
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f"Start Docker Desktop and compose.local.json first.\n{error}\n")
    with HTTPServer(("127.0.0.1", args.port), Handler) as server:
        server.key = secrets.token_urlsafe(24)
        print(f"checkdbUrl = http://127.0.0.1:{args.port}\ncheckdbKey = {server.key}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            for run_id in list(RUNS):
                try:
                    finish(run_id)
                    print(f"Cleaned interrupted run {run_id}")
                except Exception as error:
                    print(f"Cleanup failed for {run_id}: {error}")


if __name__ == "__main__":
    main()
