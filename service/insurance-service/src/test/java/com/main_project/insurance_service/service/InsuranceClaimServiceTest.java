package com.main_project.insurance_service.service;

import com.do_an.common.model.InvoiceCheckerRequest;
import com.do_an.common.model.InvoiceItemCheckerRequest;
import com.do_an.common.model.InvoiceItemResponse;
import com.main_project.insurance_service.dto.BhytCatalogueDTO;
import com.main_project.insurance_service.dto.ClaimItemDTO;
import com.main_project.insurance_service.dto.ClaimItemRequestDTO;
import com.main_project.insurance_service.dto.InvoiceDTO;
import com.main_project.insurance_service.entity.InsuranceClaim;
import com.main_project.insurance_service.entity.InsurancePolicy;
import com.main_project.insurance_service.entity.PatientInsurance;
import com.main_project.insurance_service.repository.InsuranceClaimRepository;
import com.main_project.insurance_service.util.EntityDTOMapper;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test cho InsuranceClaimService (INS-01 ... INS-07).
 *
 * - DB: H2 in-memory, moi test chay trong 1 transaction va tu ROLLBACK (@DataJpaTest).
 * - Cac service phu thuoc (BhytCatalogue, ClaimItem, InsurancePolicy) duoc MOCK.
 * - Luu y: KHONG goi toString()/hashCode() cua entity (Lombok @Data + quan he 2 chieu de bi de quy).
 */
@DataJpaTest
class InsuranceClaimServiceTest {

    @Configuration
    @EntityScan(basePackageClasses = InsuranceClaim.class)
    @EnableJpaRepositories(basePackageClasses = InsuranceClaimRepository.class)
    @Import({InsuranceClaimService.class, EntityDTOMapper.class})
    static class TestConfig {
    }

    @MockBean
    private IBhytCatalogueService bhytCatalogueService;
    @MockBean
    private IClaimItemService claimItemService;
    @MockBean
    private IInsurancePolicyService insurancePolicyService;

    @Autowired
    private IInsuranceClaimService insuranceClaimService;
    @Autowired
    private InsuranceClaimRepository insuranceClaimRepository;
    @Autowired
    private TestEntityManager em;

    // ------------------------------------------------------------------ helpers

    private InsurancePolicy persistPolicy(int coveragePercent) {
        InsurancePolicy policy = new InsurancePolicy();
        policy.setPolicyNumber("POL-" + UUID.randomUUID());
        policy.setPolicyType("BHYT");
        policy.setCoverageAmount(coveragePercent);
        policy.setStatus("ACTIVE");
        return em.persistAndFlush(policy);
    }

    private PatientInsurance persistPatientInsurance(UUID patientId, InsurancePolicy policy,
                                                     String status, LocalDate expiryDate) {
        PatientInsurance insurance = new PatientInsurance();
        insurance.setPatientId(patientId);
        insurance.setInsurancePolicy(policy);
        insurance.setStatus(status);
        insurance.setIssueDate(LocalDate.now().minusYears(1));
        insurance.setExpiryDate(expiryDate);
        return em.persistAndFlush(insurance);
    }

    private InvoiceItemCheckerRequest itemRequest(UUID referenceId, int qty, int unitPrice) {
        return new InvoiceItemCheckerRequest(UUID.randomUUID(), referenceId, "SERVICE", qty, "Dich vu", unitPrice);
    }

    private InvoiceCheckerRequest invoiceRequest(UUID patientId, int totalAmount, Set<InvoiceItemCheckerRequest> items) {
        InvoiceCheckerRequest request = new InvoiceCheckerRequest();
        request.setId(UUID.randomUUID());
        request.setPatientId(patientId);
        request.setReceptionistId(UUID.randomUUID());
        request.setAppointmentId(UUID.randomUUID());
        request.setTotalAmount(totalAmount);
        request.setCurrency("VND");
        request.setStatus("PENDING");
        request.setItems(items);
        return request;
    }

    private BhytCatalogueDTO coveredCatalogue(int maxCoverage) {
        BhytCatalogueDTO dto = new BhytCatalogueDTO();
        dto.setId(UUID.randomUUID());
        dto.setIsCovered(true);
        dto.setMaxCoverageAmount(maxCoverage);
        return dto;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("INS-01 processInvoiceClaim: tao claim PENDING va chia tien dung")
    void ins01_processInvoiceClaim_shouldCreatePendingClaimAndReturnCorrectPaymentSplit() {
        UUID patientId = UUID.randomUUID();
        persistPatientInsurance(patientId, persistPolicy(80), "ACTIVE", LocalDate.now().plusYears(1));

        UUID coveredRef = UUID.randomUUID();
        UUID uncoveredRef = UUID.randomUUID();
        InvoiceItemCheckerRequest covered = itemRequest(coveredRef, 1, 500);     // BHYT tra toi da 600
        InvoiceItemCheckerRequest uncovered = itemRequest(uncoveredRef, 1, 300); // khong nam trong danh muc

        BhytCatalogueDTO catalogue = coveredCatalogue(600);
        when(bhytCatalogueService.getBhytCatalogueByServiceCode(coveredRef.toString())).thenReturn(catalogue);
        when(bhytCatalogueService.getBhytCatalogueByServiceCode(uncoveredRef.toString()))
                .thenThrow(new RuntimeException("not in catalogue"));
        ClaimItemDTO createdClaimItem = new ClaimItemDTO();
        createdClaimItem.setId(UUID.randomUUID());
        when(claimItemService.createClaimItem(any(ClaimItemRequestDTO.class))).thenReturn(createdClaimItem);

        InvoiceDTO result = insuranceClaimService.processInvoiceClaim(
                invoiceRequest(patientId, 800, Set.of(covered, uncovered)));

        // covered: 500 * 80% = 400 (< tran 600) -> BHYT 400, benh nhan 100; uncovered: benh nhan 300
        assertEquals(800, result.getTotalAmount());
        assertEquals(400, result.getInsuranceTotalPay());
        assertEquals(400, result.getPatientTotalPay());
        assertNotNull(result.getInsuranceClaimId());

        // claim PENDING da duoc luu vao DB
        em.flush();
        em.clear();
        List<InsuranceClaim> claims = insuranceClaimRepository.findAll();
        assertEquals(1, claims.size());
        assertEquals("PENDING", claims.get(0).getStatus());
        assertEquals(400, claims.get(0).getTotalInsurancePay());
        assertEquals(400, claims.get(0).getPatientPayAmount());
        assertEquals(800, claims.get(0).getTotalClaimAmount());

        // chi item duoc BHYT chi tra moi tao claim-item
        verify(claimItemService, times(1)).createClaimItem(any(ClaimItemRequestDTO.class));
    }

    @Test
    @DisplayName("INS-02 processInvoiceClaim: tu choi khi BHYT het han hoac khong ACTIVE")
    void ins02_processInvoiceClaim_shouldThrowWhenInsuranceExpiredOrInactive() {
        InsurancePolicy policy = persistPolicy(80);
        UUID expiredPatient = UUID.randomUUID();
        UUID inactivePatient = UUID.randomUUID();
        persistPatientInsurance(expiredPatient, policy, "ACTIVE", LocalDate.now().minusDays(1));
        persistPatientInsurance(inactivePatient, policy, "INACTIVE", LocalDate.now().plusYears(1));

        Set<InvoiceItemCheckerRequest> items = Set.of(itemRequest(UUID.randomUUID(), 1, 500));

        assertThrows(RuntimeException.class,
                () -> insuranceClaimService.processInvoiceClaim(invoiceRequest(expiredPatient, 500, items)));
        assertThrows(RuntimeException.class,
                () -> insuranceClaimService.processInvoiceClaim(invoiceRequest(inactivePatient, 500, items)));

        assertEquals(0, insuranceClaimRepository.count());
        verifyNoInteractions(bhytCatalogueService, claimItemService, insurancePolicyService);
    }

    @Test
    @DisplayName("INS-03 processInvoiceItemsForValidation: tinh chia tien tung item, khong luu claim")
    void ins03_processInvoiceItemsForValidation_shouldReturnPerItemSplitWithCoverageCapAndUncoveredCase() {
        UUID patientId = UUID.randomUUID();
        persistPatientInsurance(patientId, persistPolicy(80), "ACTIVE", LocalDate.now().plusYears(1));

        UUID coveredRef = UUID.randomUUID();
        UUID uncoveredRef = UUID.randomUUID();
        InvoiceItemCheckerRequest covered = itemRequest(coveredRef, 1, 500);   // tran BHYT 300
        InvoiceItemCheckerRequest uncovered = itemRequest(uncoveredRef, 1, 200);

        when(bhytCatalogueService.getBhytCatalogueByServiceCode(coveredRef.toString()))
                .thenReturn(coveredCatalogue(300));
        when(bhytCatalogueService.getBhytCatalogueByServiceCode(uncoveredRef.toString()))
                .thenThrow(new RuntimeException("not in catalogue"));

        Set<InvoiceItemResponse> responses = insuranceClaimService.processInvoiceItemsForValidation(
                invoiceRequest(patientId, 700, Set.of(covered, uncovered)), patientId);

        assertEquals(2, responses.size());
        Map<UUID, InvoiceItemResponse> byId = responses.stream()
                .collect(Collectors.toMap(InvoiceItemResponse::getId, Function.identity()));

        // min(300, 500 * 0.8 = 400) = 300 -> BHYT 300, benh nhan 200
        assertEquals(300, byId.get(covered.getId()).getInsurancePayAmount());
        assertEquals(200, byId.get(covered.getId()).getPatientPayAmount());
        // khong thuoc danh muc BHYT -> benh nhan tra het
        assertEquals(0, byId.get(uncovered.getId()).getInsurancePayAmount());
        assertEquals(200, byId.get(uncovered.getId()).getPatientPayAmount());

        // khong luu claim nao
        assertEquals(0, insuranceClaimRepository.count());
    }

    @Test
    @DisplayName("INS-04 createClaimFromValidationEvent: gop tong tien cac item va luu claim PENDING")
    void ins04_createClaimFromValidationEvent_shouldAggregateItemTotalsAndPersistClaim() {
        UUID patientId = UUID.randomUUID();
        persistPatientInsurance(patientId, persistPolicy(80), "ACTIVE", LocalDate.now().plusYears(1));
        UUID claimId = UUID.randomUUID();

        InvoiceItemResponse item1 = InvoiceItemResponse.builder()
                .id(UUID.randomUUID()).insurancePayAmount(300).patientPayAmount(100).build();
        InvoiceItemResponse item2 = InvoiceItemResponse.builder()
                .id(UUID.randomUUID()).insurancePayAmount(0).patientPayAmount(200).build();

        insuranceClaimService.createClaimFromValidationEvent(claimId, patientId, Set.of(item1, item2));

        em.flush();
        em.clear();
        InsuranceClaim claim = insuranceClaimRepository.findById(claimId).orElseThrow();
        assertEquals("PENDING", claim.getStatus());
        assertEquals(300, claim.getTotalInsurancePay());
        assertEquals(300, claim.getPatientPayAmount());
        assertEquals(600, claim.getTotalClaimAmount());
    }

    @Test
    @DisplayName("INS-05 createClaimItemRequestFromInvoiceItem: map item va tinh ty le BHYT")
    void ins05_createClaimItemRequestFromInvoiceItem_shouldCalculateInsurancePayRatio() {
        UUID claimId = UUID.randomUUID();
        UUID claimItemId = UUID.randomUUID();
        UUID bhytId = UUID.randomUUID();
        InvoiceItemResponse item = InvoiceItemResponse.builder()
                .id(UUID.randomUUID())
                .claimItemId(claimItemId)
                .bhytCatalogueId(bhytId)
                .quantity(2)
                .unitPrice(250)
                .insurancePayAmount(300)
                .patientPayAmount(200)
                .build();

        ClaimItemRequestDTO request = insuranceClaimService.createClaimItemRequestFromInvoiceItem(item, claimId);

        assertEquals(500, request.getTotalAmount());
        assertEquals(0.6f, request.getInsurancePayRatio(), 0.0001f);
        assertEquals(claimId, request.getInsuranceClaimId());
        assertEquals(claimItemId, request.getId());
        assertEquals(bhytId, request.getBhytCatalogueId());
    }

    @Test
    @DisplayName("INS-06 createClaimItemRequestFromInvoiceItem: tong tien = 0 khong bi chia cho 0")
    void ins06_createClaimItemRequestFromInvoiceItem_shouldHandleZeroAmountWithoutDivideByZero() {
        InvoiceItemResponse item = InvoiceItemResponse.builder()
                .id(UUID.randomUUID())
                .quantity(0)
                .unitPrice(500)
                .insurancePayAmount(0)
                .patientPayAmount(0)
                .build();

        ClaimItemRequestDTO request = insuranceClaimService.createClaimItemRequestFromInvoiceItem(item, UUID.randomUUID());

        assertEquals(0, request.getTotalAmount());
        assertEquals(0f, request.getInsurancePayRatio(), 0.0f);
    }

    @Test
    @DisplayName("INS-07 processInvoiceItemsForValidation: tu choi khi the BHYT khong gan chinh sach")
    void ins07_processInvoiceItemsForValidation_shouldRejectPatientInsuranceWithoutPolicy() {
        UUID patientId = UUID.randomUUID();
        persistPatientInsurance(patientId, null, "ACTIVE", LocalDate.now().plusYears(1));
        Set<InvoiceItemCheckerRequest> items = Set.of(itemRequest(UUID.randomUUID(), 1, 500));

        assertThrows(RuntimeException.class,
                () -> insuranceClaimService.processInvoiceItemsForValidation(
                        invoiceRequest(patientId, 500, items), patientId));

        assertEquals(0, insuranceClaimRepository.count());
        verify(claimItemService, never()).createClaimItem(any());
        verifyNoInteractions(bhytCatalogueService, claimItemService, insurancePolicyService);
    }
}
