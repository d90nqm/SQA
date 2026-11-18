package com.do_an.invoiceservice.service;

import com.do_an.common.model.InvoiceItemCheckerRequest;
import com.do_an.common.model.InvoiceItemResponse;
import com.do_an.common.model.MedicineItem;
import com.do_an.invoiceservice.client.AppointmentClient;
import com.do_an.invoiceservice.dto.request.CreateInvoiceItemRequestDTO;
import com.do_an.invoiceservice.dto.request.CreateInvoiceRequestDTO;
import com.do_an.invoiceservice.dto.response.InvoiceItemResponseDTO;
import com.do_an.invoiceservice.dto.response.InvoiceResponseDTO;
import com.do_an.invoiceservice.entity.Invoice;
import com.do_an.invoiceservice.entity.InvoiceItem;
import com.do_an.invoiceservice.iservice.IInvoiceService;
import com.do_an.invoiceservice.mapper.InvoiceItemMapperImpl;
import com.do_an.invoiceservice.mapper.InvoiceMapperImpl;
import com.do_an.invoiceservice.repository.InvoiceItemRepository;
import com.do_an.invoiceservice.repository.InvoiceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit test cho InvoiceService (INV-01 ... INV-07).
 *
 * - Dung H2 in-memory (khong dung Postgres that).
 * - Moi test chay trong 1 transaction va tu ROLLBACK khi ket thuc (@DataJpaTest),
 *   nen du lieu test khong de lai gi.
 * - Chi nap phan JPA + InvoiceService + MapStruct mapper, KHONG nap Axon/Eureka/Security/Feign.
 */
@DataJpaTest
class InvoiceServiceTest {

    @Configuration
    @EntityScan(basePackageClasses = Invoice.class)
    @EnableJpaRepositories(basePackageClasses = InvoiceRepository.class)
    @Import({InvoiceService.class, InvoiceMapperImpl.class, InvoiceItemMapperImpl.class})
    static class TestConfig {
    }

    @MockBean
    private AppointmentClient appointmentClient;

    @Autowired
    private IInvoiceService invoiceService;
    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private InvoiceItemRepository invoiceItemRepository;
    @Autowired
    private TestEntityManager em;

    // ------------------------------------------------------------------ helpers

    private Invoice newInvoice(String status, int total, int patientPay, int insurancePay) {
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setReceptionistId(UUID.randomUUID());
        invoice.setAppointmentId(UUID.randomUUID());
        invoice.setCurrency("VND");
        invoice.setStatus(status);
        invoice.setIssueAt(LocalDateTime.now());
        invoice.setTotalAmount(total);
        invoice.setPatientTotalPay(patientPay);
        invoice.setInsuranceTotalPay(insurancePay);
        return invoice;
    }

    private InvoiceItem addItem(Invoice invoice, String type, int qty, int unitPrice, String description) {
        InvoiceItem item = new InvoiceItem();
        item.setId(UUID.randomUUID());
        item.setReferenceId(UUID.randomUUID());
        item.setServiceType(type);
        item.setQuantity(qty);
        item.setUnitPrice(unitPrice);
        item.setDescription(description);
        item.setInsurancePayAmount(0);
        item.setPatientPayAmount(qty * unitPrice);
        invoice.addItem(item);
        return item;
    }

    private CreateInvoiceItemRequestDTO serviceItemRequest(int qty, int unitPrice, String description) {
        CreateInvoiceItemRequestDTO dto = new CreateInvoiceItemRequestDTO();
        dto.setId(UUID.randomUUID());
        dto.setReferenceId(UUID.randomUUID());
        dto.setServiceType("SERVICE");
        dto.setQuantity(qty);
        dto.setUnitPrice(unitPrice);
        dto.setDescription(description);
        return dto;
    }

    /** Day xuong DB, xoa cache Hibernate de lan doc sau la doc that tu DB. */
    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("INV-01 createInvoice: tao hoa don PENDING va tinh tong tien ban dau")
    void inv01_createInvoice_shouldCreatePendingInvoiceAndCalculateInitialTotals() {
        UUID invoiceId = UUID.randomUUID();
        CreateInvoiceRequestDTO request = new CreateInvoiceRequestDTO();
        request.setId(invoiceId);
        request.setReceptionistId(UUID.randomUUID());
        request.setAppointmentId(UUID.randomUUID());
        request.setCurrency("VND");
        request.setItems(List.of(
                serviceItemRequest(2, 100, "Cao voi rang"),
                serviceItemRequest(3, 200, "Tram rang")));

        InvoiceResponseDTO dto = invoiceService.createInvoice(request);

        // --- kiem tra DTO tra ve
        assertEquals("PENDING", dto.getStatus());
        assertEquals(800, dto.getTotalAmount());
        assertEquals(800, dto.getPatientTotalPay());
        assertEquals(0, dto.getInsuranceTotalPay());

        // --- kiem tra DB
        flushAndClear();
        Invoice db = invoiceRepository.findById(invoiceId).orElseThrow();
        assertEquals("PENDING", db.getStatus());
        assertEquals(800, db.getTotalAmount());
        assertEquals(800, db.getPatientTotalPay());
        assertEquals(0, db.getInsuranceTotalPay());
        assertEquals(2, invoiceItemRepository.findByInvoiceId(invoiceId).size());
    }

    @Test
    @DisplayName("INV-02 addMedicineCharges: them thuoc vao hoa don va tang tong tien")
    void inv02_addMedicineCharges_shouldPersistMedicineItemsAndIncreaseTotals() {
        Invoice invoice = newInvoice("PENDING", 500, 500, 0);
        addItem(invoice, "SERVICE", 1, 500, "Kham tong quat");
        em.persistAndFlush(invoice);
        UUID invoiceId = invoice.getId();

        UUID medicineId1 = UUID.randomUUID();
        UUID medicineId2 = UUID.randomUUID();
        InvoiceItemCheckerRequest charge1 =
                new InvoiceItemCheckerRequest(UUID.randomUUID(), medicineId1, "MEDICINE", 2, "x", 150);
        InvoiceItemCheckerRequest charge2 =
                new InvoiceItemCheckerRequest(UUID.randomUUID(), medicineId2, "MEDICINE", 1, "y", 200);

        MedicineItem medicine1 = new MedicineItem();
        medicine1.setId(charge1.getId());
        medicine1.setMedicineId(medicineId1);
        medicine1.setName("Amoxicillin");
        MedicineItem medicine2 = new MedicineItem();
        medicine2.setId(charge2.getId());
        medicine2.setMedicineId(medicineId2);
        medicine2.setName("Paracetamol");

        InvoiceResponseDTO dto = invoiceService.addMedicineCharges(
                invoiceId, Set.of(charge1, charge2), List.of(medicine1, medicine2));

        // --- DTO
        assertEquals(1000, dto.getTotalAmount());
        assertEquals(1000, dto.getPatientTotalPay());
        assertEquals(0, dto.getInsuranceTotalPay());

        // --- DB
        flushAndClear();
        Invoice db = invoiceRepository.findById(invoiceId).orElseThrow();
        assertEquals(1000, db.getTotalAmount());
        assertEquals(1000, db.getPatientTotalPay());
        assertEquals(0, db.getInsuranceTotalPay());

        List<InvoiceItem> medicineRows = invoiceItemRepository.findByInvoiceId(invoiceId).stream()
                .filter(i -> "MEDICINE".equals(i.getServiceType()))
                .collect(Collectors.toList());
        assertEquals(2, medicineRows.size());
        Set<String> names = medicineRows.stream().map(InvoiceItem::getDescription).collect(Collectors.toSet());
        assertEquals(Set.of("Amoxicillin", "Paracetamol"), names);
    }

    @Test
    @DisplayName("INV-03 applyInsuranceDiscount: cap nhat chia tien BHYT o muc hoa don va tung item")
    void inv03_applyInsuranceDiscount_shouldUpdateInvoiceSplitAndPersistItemAmounts() {
        Invoice invoice = newInvoice("PENDING", 1000, 1000, 0);
        InvoiceItem serviceItem = addItem(invoice, "SERVICE", 1, 600, "Tram rang");
        InvoiceItem medicineItem = addItem(invoice, "MEDICINE", 1, 400, "Amoxicillin");
        em.persistAndFlush(invoice);
        UUID invoiceId = invoice.getId();
        UUID claimId = UUID.randomUUID();

        InvoiceItemResponse serviceSplit = splitResponse(serviceItem, 200, 400);
        InvoiceItemResponse medicineSplit = splitResponse(medicineItem, 100, 300);

        InvoiceResponseDTO dto = invoiceService.applyInsuranceDiscount(
                invoiceId, claimId, 300, Set.of(serviceSplit, medicineSplit));

        // --- DTO
        assertEquals(300, dto.getInsuranceTotalPay());
        assertEquals(700, dto.getPatientTotalPay());
        assertEquals(claimId, dto.getInsuranceClaimId());

        // --- DB
        flushAndClear();
        Invoice db = invoiceRepository.findById(invoiceId).orElseThrow();
        assertEquals(300, db.getInsuranceTotalPay());
        assertEquals(700, db.getPatientTotalPay());
        assertEquals(claimId, db.getInsuranceClaimId());

        Map<UUID, InvoiceItem> rows = invoiceItemRepository.findByInvoiceId(invoiceId).stream()
                .collect(Collectors.toMap(InvoiceItem::getId, Function.identity()));
        assertEquals(200, rows.get(serviceItem.getId()).getInsurancePayAmount());
        assertEquals(400, rows.get(serviceItem.getId()).getPatientPayAmount());
        assertEquals(100, rows.get(medicineItem.getId()).getInsurancePayAmount());
        assertEquals(300, rows.get(medicineItem.getId()).getPatientPayAmount());
    }

    @Test
    @DisplayName("INV-04 applyInsuranceDiscount: giam gia BHYT > tong hoa don (hanh vi hien tai cua code)")
    void inv04_applyInsuranceDiscount_shouldReflectCurrentBehaviorWhenDiscountExceedsInvoiceTotal() {
        Invoice invoice = newInvoice("PENDING", 1000, 1000, 0);
        addItem(invoice, "SERVICE", 1, 1000, "Boc rang su");
        em.persistAndFlush(invoice);
        UUID invoiceId = invoice.getId();

        InvoiceResponseDTO dto = invoiceService.applyInsuranceDiscount(
                invoiceId, UUID.randomUUID(), 1200, Set.of());

        // Code hien tai: patient = max(0, 1000 - 1200) = 0, nhung insurance van = 1200 (> tong tien)
        assertEquals(0, dto.getPatientTotalPay());
        assertEquals(1200, dto.getInsuranceTotalPay());

        flushAndClear();
        Invoice db = invoiceRepository.findById(invoiceId).orElseThrow();
        assertEquals(0, db.getPatientTotalPay());
        assertEquals(1200, db.getInsuranceTotalPay());
    }

    @Test
    @DisplayName("INV-05 removeMedicineCharges: xoa thuoc va tinh lai tong tien tu cac item con lai")
    void inv05_removeMedicineCharges_shouldDeleteMedicineItemsAndRecalculateRemainingTotals() {
        Invoice invoice = newInvoice("PENDING", 800, 800, 0);
        addItem(invoice, "SERVICE", 1, 400, "Nho rang khon");
        addItem(invoice, "MEDICINE", 2, 100, "Amoxicillin");
        addItem(invoice, "MEDICINE", 1, 200, "Paracetamol");
        em.persistAndFlush(invoice);
        UUID invoiceId = invoice.getId();
        // KHONG clear persistence context: invoice + items dang duoc Hibernate quan ly,
        // dung nhu luc service vua nap hoa don cung cac item cua no.

        InvoiceResponseDTO dto = invoiceService.removeMedicineCharges(invoiceId);

        // Mong doi: con lai SERVICE 400 -> tong = 400
        assertEquals(400, dto.getTotalAmount());
        assertEquals(400, dto.getPatientTotalPay());

        flushAndClear();
        Invoice db = invoiceRepository.findById(invoiceId).orElseThrow();
        assertEquals(400, db.getTotalAmount());
        assertEquals(400, db.getPatientTotalPay());
        List<InvoiceItem> rows = invoiceItemRepository.findByInvoiceId(invoiceId);
        assertEquals(1, rows.size());
        assertEquals("SERVICE", rows.get(0).getServiceType());
    }

    @Test
    @DisplayName("INV-06 revertInsuranceDiscount: huy ap dung BHYT, benh nhan tra lai toan bo")
    void inv06_revertInsuranceDiscount_shouldClearClaimAndRestorePatientPay() {
        Invoice invoice = newInvoice("PENDING", 1000, 700, 300);
        invoice.setInsuranceClaimId(UUID.randomUUID());
        addItem(invoice, "SERVICE", 1, 1000, "Tay trang rang");
        em.persistAndFlush(invoice);
        UUID invoiceId = invoice.getId();

        InvoiceResponseDTO dto = invoiceService.revertInsuranceDiscount(invoiceId);

        assertNull(dto.getInsuranceClaimId());
        assertEquals(0, dto.getInsuranceTotalPay());
        assertEquals(1000, dto.getPatientTotalPay());
        assertEquals(1000, dto.getTotalAmount());

        flushAndClear();
        Invoice db = invoiceRepository.findById(invoiceId).orElseThrow();
        assertNull(db.getInsuranceClaimId());
        assertEquals(0, db.getInsuranceTotalPay());
        assertEquals(1000, db.getPatientTotalPay());
        assertEquals(1000, db.getTotalAmount());
    }

    @Test
    @DisplayName("INV-07 markAsPaid: tu choi hoa don khong o trang thai PENDING")
    void inv07_markAsPaid_shouldRejectNonPendingInvoiceAndKeepStatusUnchanged() {
        Invoice invoice = newInvoice("CANCELLED", 1000, 1000, 0);
        addItem(invoice, "SERVICE", 1, 1000, "Kham tong quat");
        em.persistAndFlush(invoice);
        UUID invoiceId = invoice.getId();

        assertThrows(IllegalStateException.class, () -> invoiceService.markAsPaid(invoiceId));

        flushAndClear();
        Invoice db = invoiceRepository.findById(invoiceId).orElseThrow();
        assertEquals("CANCELLED", db.getStatus());
        assertNull(db.getPaidAt());
        assertEquals(1000, db.getTotalAmount());
        assertEquals(1000, db.getPatientTotalPay());
        assertEquals(0, db.getInsuranceTotalPay());
        assertNotNull(db.getId());
    }

    // ------------------------------------------------------------------ test data builders

    /** Tao InvoiceItemResponse giu nguyen thong tin item, chi doi phan chia tien BHYT / benh nhan. */
    private InvoiceItemResponse splitResponse(InvoiceItem item, int insurancePay, int patientPay) {
        return InvoiceItemResponse.builder()
                .id(item.getId())
                .referenceId(item.getReferenceId())
                .serviceType(item.getServiceType())
                .quantity(item.getQuantity())
                .description(item.getDescription())
                .unitPrice(item.getUnitPrice())
                .insurancePayAmount(insurancePay)
                .patientPayAmount(patientPay)
                .build();
    }
}
