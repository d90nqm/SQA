package com.main_project.patient_service.service;

import com.main_project.patient_service.dto.ConditionRequestDTO;
import com.main_project.patient_service.dto.ConditionResponseDTO;
import com.main_project.patient_service.dto.MedicalHistoryRequestDTO;
import com.main_project.patient_service.dto.MedicalHistoryResponseDTO;
import com.main_project.patient_service.entity.Condition;
import com.main_project.patient_service.entity.MedicalHistory;
import com.main_project.patient_service.entity.Patient;
import com.main_project.patient_service.entity.ToothIssue;
import com.main_project.patient_service.repository.MedicalHistoryRepository;
import com.main_project.patient_service.repository.PatientRepository;
import com.main_project.patient_service.util.EntityMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicalHistoryServiceTest {

    @Mock
    private MedicalHistoryRepository medicalHistoryRepository;

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private EntityMapper mapper;

    @InjectMocks
    private MedicalHistoryService medicalHistoryService;

    private UUID samplePatientId;
    private UUID sampleAppointmentId;
    private UUID sampleHistoryId;

    private Patient samplePatient;
    private MedicalHistory sampleHistory;
    private MedicalHistoryResponseDTO sampleResponseDTO;

    @BeforeEach
    void setUp() {
        samplePatientId = UUID.randomUUID();
        sampleAppointmentId = UUID.randomUUID();
        sampleHistoryId = UUID.randomUUID();

        samplePatient = Patient.builder()
                .userId(samplePatientId)
                .toothIssues(new HashSet<>())
                .medicalHistories(new HashSet<>())
                .build();

        sampleHistory = MedicalHistory.builder()
                .id(sampleHistoryId)
                .appointmentId(sampleAppointmentId)
                .symptoms("Đau chân răng")
                .patient(samplePatient)
                .conditions(new HashSet<>())
                .createdAt(ZonedDateTime.now())
                .updatedAt(ZonedDateTime.now())
                .build();

        sampleResponseDTO = new MedicalHistoryResponseDTO();
        sampleResponseDTO.setId(sampleHistoryId);
        sampleResponseDTO.setPatientId(samplePatientId);
        sampleResponseDTO.setAppointmentId(sampleAppointmentId);
        sampleResponseDTO.setSymptoms("Đau chân răng");
    }

    // ==========================================
    // UT_MHS_001 -> UT_MHS_014: createMedicalHistory
    // ==========================================

    @Test
    @DisplayName("UT_MHS_001 - createMedicalHistory_withValidPatient_returnsDTO")
    void createMedicalHistory_withValidPatient_returnsDTO() {
        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(samplePatientId);
        request.setAppointmentId(sampleAppointmentId);
        request.setSymptoms("Đau chân răng");

        when(patientRepository.findById(samplePatientId)).thenReturn(Optional.of(samplePatient));
        when(mapper.toMedicalHistoryEntity(request, samplePatient)).thenReturn(sampleHistory);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toMedicalHistoryResponse(sampleHistory)).thenReturn(sampleResponseDTO);

        MedicalHistoryResponseDTO result = medicalHistoryService.createMedicalHistory(request);

        assertNotNull(result);
        assertEquals(sampleHistoryId, result.getId());
        assertEquals("Đau chân răng", result.getSymptoms());
        verify(medicalHistoryRepository, times(1)).save(sampleHistory);
    }

    @Test
    @DisplayName("UT_MHS_002 - createMedicalHistory_withEmptyConditions_success")
    void createMedicalHistory_withEmptyConditions_success() {
        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(samplePatientId);
        request.setAppointmentId(sampleAppointmentId);
        request.setConditions(Collections.emptyList());

        when(patientRepository.findById(samplePatientId)).thenReturn(Optional.of(samplePatient));
        when(mapper.toMedicalHistoryEntity(request, samplePatient)).thenReturn(sampleHistory);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toMedicalHistoryResponse(sampleHistory)).thenReturn(sampleResponseDTO);

        MedicalHistoryResponseDTO result = medicalHistoryService.createMedicalHistory(request);

        assertNotNull(result);
        verify(medicalHistoryRepository, times(1)).save(sampleHistory);
    }

    @Test
    @DisplayName("UT_MHS_003 - createMedicalHistory_withNullSymptoms_success")
    void createMedicalHistory_withNullSymptoms_success() {
        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(samplePatientId);
        request.setAppointmentId(sampleAppointmentId);
        request.setSymptoms(null);

        when(patientRepository.findById(samplePatientId)).thenReturn(Optional.of(samplePatient));
        when(mapper.toMedicalHistoryEntity(request, samplePatient)).thenReturn(sampleHistory);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toMedicalHistoryResponse(sampleHistory)).thenReturn(sampleResponseDTO);

        MedicalHistoryResponseDTO result = medicalHistoryService.createMedicalHistory(request);

        assertNotNull(result);
        verify(medicalHistoryRepository, times(1)).save(sampleHistory);
    }

    @Test
    @DisplayName("UT_MHS_004 - createMedicalHistory_withNullAppointmentId_success")
    void createMedicalHistory_withNullAppointmentId_success() {
        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(samplePatientId);
        request.setAppointmentId(null);

        when(patientRepository.findById(samplePatientId)).thenReturn(Optional.of(samplePatient));
        when(mapper.toMedicalHistoryEntity(request, samplePatient)).thenReturn(sampleHistory);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toMedicalHistoryResponse(sampleHistory)).thenReturn(sampleResponseDTO);

        MedicalHistoryResponseDTO result = medicalHistoryService.createMedicalHistory(request);

        assertNotNull(result);
        verify(medicalHistoryRepository, times(1)).save(sampleHistory);
    }

    @Test
    @DisplayName("UT_MHS_005 - createMedicalHistory_withMultipleConditions_success")
    void createMedicalHistory_withMultipleConditions_success() {
        ConditionRequestDTO cond1 = new ConditionRequestDTO();
        cond1.setToothNumber(11);
        cond1.setStatus("CARIES");

        ConditionRequestDTO cond2 = new ConditionRequestDTO();
        cond2.setToothNumber(12);
        cond2.setStatus("TREATED");

        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(samplePatientId);
        request.setAppointmentId(sampleAppointmentId);
        request.setConditions(List.of(cond1, cond2));

        when(patientRepository.findById(samplePatientId)).thenReturn(Optional.of(samplePatient));
        when(mapper.toMedicalHistoryEntity(request, samplePatient)).thenReturn(sampleHistory);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toMedicalHistoryResponse(sampleHistory)).thenReturn(sampleResponseDTO);

        MedicalHistoryResponseDTO result = medicalHistoryService.createMedicalHistory(request);

        assertNotNull(result);
        verify(medicalHistoryRepository, times(1)).save(sampleHistory);
    }

    @Test
    @DisplayName("UT_MHS_006 - createMedicalHistory_withLongSymptoms_success")
    void createMedicalHistory_withLongSymptoms_success() {
        String longSymptoms = "a".repeat(255);
        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(samplePatientId);
        request.setAppointmentId(sampleAppointmentId);
        request.setSymptoms(longSymptoms);

        when(patientRepository.findById(samplePatientId)).thenReturn(Optional.of(samplePatient));
        when(mapper.toMedicalHistoryEntity(request, samplePatient)).thenReturn(sampleHistory);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toMedicalHistoryResponse(sampleHistory)).thenReturn(sampleResponseDTO);

        MedicalHistoryResponseDTO result = medicalHistoryService.createMedicalHistory(request);

        assertNotNull(result);
        verify(medicalHistoryRepository, times(1)).save(sampleHistory);
    }

    @Test
    @DisplayName("UT_MHS_007 - createMedicalHistory_withEmptySymptoms_success")
    void createMedicalHistory_withEmptySymptoms_success() {
        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(samplePatientId);
        request.setAppointmentId(sampleAppointmentId);
        request.setSymptoms("");

        when(patientRepository.findById(samplePatientId)).thenReturn(Optional.of(samplePatient));
        when(mapper.toMedicalHistoryEntity(request, samplePatient)).thenReturn(sampleHistory);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toMedicalHistoryResponse(sampleHistory)).thenReturn(sampleResponseDTO);

        MedicalHistoryResponseDTO result = medicalHistoryService.createMedicalHistory(request);

        assertNotNull(result);
        verify(medicalHistoryRepository, times(1)).save(sampleHistory);
    }

    @Test
    @DisplayName("UT_MHS_008 - createMedicalHistory_withSpecialCharacters_success")
    void createMedicalHistory_withSpecialCharacters_success() {
        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(samplePatientId);
        request.setAppointmentId(sampleAppointmentId);
        request.setSymptoms("Đau @#$% nhức");

        when(patientRepository.findById(samplePatientId)).thenReturn(Optional.of(samplePatient));
        when(mapper.toMedicalHistoryEntity(request, samplePatient)).thenReturn(sampleHistory);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toMedicalHistoryResponse(sampleHistory)).thenReturn(sampleResponseDTO);

        MedicalHistoryResponseDTO result = medicalHistoryService.createMedicalHistory(request);

        assertNotNull(result);
        verify(medicalHistoryRepository, times(1)).save(sampleHistory);
    }

    @Test
    @DisplayName("UT_MHS_009 - createMedicalHistory_withValidToothNumber_success")
    void createMedicalHistory_withValidToothNumber_success() {
        ConditionRequestDTO cond = new ConditionRequestDTO();
        cond.setToothNumber(25);

        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(samplePatientId);
        request.setAppointmentId(sampleAppointmentId);
        request.setConditions(List.of(cond));

        when(patientRepository.findById(samplePatientId)).thenReturn(Optional.of(samplePatient));
        when(mapper.toMedicalHistoryEntity(request, samplePatient)).thenReturn(sampleHistory);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toMedicalHistoryResponse(sampleHistory)).thenReturn(sampleResponseDTO);

        MedicalHistoryResponseDTO result = medicalHistoryService.createMedicalHistory(request);

        assertNotNull(result);
        verify(medicalHistoryRepository, times(1)).save(sampleHistory);
    }

    @Test
    @DisplayName("UT_MHS_010 - createMedicalHistory_withTreatment_success")
    void createMedicalHistory_withTreatment_success() {
        ConditionRequestDTO cond = new ConditionRequestDTO();
        cond.setToothNumber(16);
        cond.setStatus("CARIES");
        cond.setTreatment("Trám thẳng");

        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(samplePatientId);
        request.setAppointmentId(sampleAppointmentId);
        request.setConditions(List.of(cond));

        when(patientRepository.findById(samplePatientId)).thenReturn(Optional.of(samplePatient));
        when(mapper.toMedicalHistoryEntity(request, samplePatient)).thenReturn(sampleHistory);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toMedicalHistoryResponse(sampleHistory)).thenReturn(sampleResponseDTO);

        MedicalHistoryResponseDTO result = medicalHistoryService.createMedicalHistory(request);

        assertNotNull(result);
        verify(medicalHistoryRepository, times(1)).save(sampleHistory);
    }

    @Test
    @DisplayName("UT_MHS_011 - createMedicalHistory_withInvalidPatientId_throwException")
    void createMedicalHistory_withInvalidPatientId_throwException() {
        UUID invalidPatientId = UUID.randomUUID();
        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(invalidPatientId);

        when(patientRepository.findById(invalidPatientId)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> medicalHistoryService.createMedicalHistory(request));
        verify(medicalHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT_MHS_012 - createMedicalHistory_blankAppointmentId_shouldValidate [INTENDED FAIL]")
    void createMedicalHistory_blankAppointmentId_shouldValidate() {
        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(samplePatientId);
        request.setAppointmentId(null);

        when(patientRepository.findById(samplePatientId)).thenReturn(Optional.of(samplePatient));
        when(mapper.toMedicalHistoryEntity(request, samplePatient)).thenReturn(sampleHistory);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toMedicalHistoryResponse(sampleHistory)).thenReturn(sampleResponseDTO);

        // Expected output per sheet MHS: Should throw IllegalArgumentException containing "appointmentId"
        // Current service does NOT validate -> test fails intentionally
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> medicalHistoryService.createMedicalHistory(request));
        assertTrue(exception.getMessage().contains("appointmentId"));
    }

    @Test
    @DisplayName("UT_MHS_013 - createMedicalHistory_invalidToothNumber_shouldReject [INTENDED FAIL]")
    void createMedicalHistory_invalidToothNumber_shouldReject() {
        ConditionRequestDTO cond = new ConditionRequestDTO();
        cond.setToothNumber(99); // outside FDI range

        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(samplePatientId);
        request.setAppointmentId(sampleAppointmentId);
        request.setConditions(List.of(cond));

        when(patientRepository.findById(samplePatientId)).thenReturn(Optional.of(samplePatient));
        when(mapper.toMedicalHistoryEntity(request, samplePatient)).thenReturn(sampleHistory);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toMedicalHistoryResponse(sampleHistory)).thenReturn(sampleResponseDTO);

        // Expected output per sheet MHS: Should throw IllegalArgumentException containing "FDI"
        // Current service does NOT validate FDI range -> test fails intentionally
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> medicalHistoryService.createMedicalHistory(request));
        assertTrue(exception.getMessage().contains("FDI"));
    }

    @Test
    @DisplayName("UT_MHS_014 - createMedicalHistory_withExistingToothIssue_updatesIssue")
    void createMedicalHistory_withExistingToothIssue_updatesIssue() {
        ToothIssue existingIssue = ToothIssue.builder()
                .id(UUID.randomUUID())
                .toothNumber(11)
                .status("ACTIVE")
                .description("Old issue")
                .diagnosedDate(LocalDate.now().minusDays(10))
                .note("Old note")
                .patient(samplePatient)
                .build();

        samplePatient.getToothIssues().add(existingIssue);

        ConditionRequestDTO cond = new ConditionRequestDTO();
        cond.setToothNumber(11);
        cond.setStatus("CARIES");
        cond.setName("Sâu răng mặt nhai");
        cond.setTreatment("Trám composite");

        MedicalHistoryRequestDTO request = new MedicalHistoryRequestDTO();
        request.setPatientId(samplePatientId);
        request.setAppointmentId(sampleAppointmentId);
        request.setConditions(List.of(cond));

        when(patientRepository.findById(samplePatientId)).thenReturn(Optional.of(samplePatient));
        when(mapper.toMedicalHistoryEntity(request, samplePatient)).thenReturn(sampleHistory);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toMedicalHistoryResponse(sampleHistory)).thenReturn(sampleResponseDTO);

        medicalHistoryService.createMedicalHistory(request);

        assertEquals("CARIES", existingIssue.getStatus());
        assertEquals("Sâu răng mặt nhai", existingIssue.getDescription());
        assertEquals("Trám composite", existingIssue.getNote());
        verify(patientRepository, times(1)).save(samplePatient);
    }

    // ==========================================
    // UT_MHS_015: addConditionToMedicalHistory
    // ==========================================

    @Test
    @DisplayName("UT_MHS_015 - addConditionToMedicalHistory_addsConditionAndReturnsResponse")
    void addConditionToMedicalHistory_addsConditionAndReturnsResponse() {
        UUID conditionId = UUID.randomUUID();
        ConditionRequestDTO condRequest = new ConditionRequestDTO();
        condRequest.setId(conditionId);
        condRequest.setToothNumber(14);
        condRequest.setStatus("CARIES");

        Condition conditionEntity = Condition.builder()
                .id(conditionId)
                .toothNumber(14)
                .status("CARIES")
                .medicalHistory(sampleHistory)
                .build();

        ConditionResponseDTO condResponse = new ConditionResponseDTO();
        condResponse.setId(conditionId);
        condResponse.setToothNumber(14);
        condResponse.setStatus("CARIES");

        when(medicalHistoryRepository.findById(sampleHistoryId)).thenReturn(Optional.of(sampleHistory));
        when(mapper.toConditionEntity(condRequest, sampleHistory)).thenReturn(conditionEntity);
        when(medicalHistoryRepository.save(sampleHistory)).thenReturn(sampleHistory);
        when(mapper.toConditionResponse(any(Condition.class))).thenReturn(condResponse);

        ConditionResponseDTO result = medicalHistoryService.addConditionToMedicalHistory(sampleHistoryId, condRequest);

        assertNotNull(result);
        assertEquals(conditionId, result.getId());
        assertEquals("CARIES", result.getStatus());
        verify(medicalHistoryRepository, times(1)).save(sampleHistory);
    }
}
