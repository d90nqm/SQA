package com.main_project.labtest_service.service;

import com.main_project.labtest_service.dto.LabTestDTO;
import com.main_project.labtest_service.dto.LabTestRequestDTO;
import com.main_project.labtest_service.entity.LabTechnician;
import com.main_project.labtest_service.entity.LabTest;
import com.main_project.labtest_service.entity.LabTestType;
import com.main_project.labtest_service.feignclient.InvoiceServiceClient;
import com.main_project.labtest_service.repository.LabTechnicianRepository;
import com.main_project.labtest_service.repository.LabTestRepository;
import com.main_project.labtest_service.repository.LabTestTypeRepository;
import com.main_project.labtest_service.util.EntityDTOMapper;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventhandling.EventMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LabTestServiceTest {

    @Mock
    private LabTestRepository labTestRepository;

    @Mock
    private LabTechnicianRepository labTechnicianRepository;

    @Mock
    private LabTestTypeRepository labTestTypeRepository;

    @Mock
    private EntityDTOMapper mapper;

    @Mock
    private InvoiceServiceClient invoiceServiceClient;

    @Mock
    private EventBus eventBus;

    @InjectMocks
    private LabTestService labTestService;

    private UUID sampleId;
    private UUID sampleTypeId;
    private UUID sampleTechId;
    private UUID sampleDoctorId;
    private UUID sampleAppointmentId;
    private UUID sampleMedicalHistoryId;

    private LabTestType sampleType;
    private LabTechnician sampleTechnician;
    private LabTest sampleEntity;
    private LabTestDTO sampleDTO;

    @BeforeEach
    void setUp() {
        sampleId = UUID.randomUUID();
        sampleTypeId = UUID.randomUUID();
        sampleTechId = UUID.randomUUID();
        sampleDoctorId = UUID.randomUUID();
        sampleAppointmentId = UUID.randomUUID();
        sampleMedicalHistoryId = UUID.randomUUID();

        sampleType = LabTestType.builder()
                .id(sampleTypeId)
                .name("Blood Test")
                .description("Complete Blood Count")
                .build();

        sampleTechnician = LabTechnician.builder()
                .userId(sampleTechId)
                .licenseNumber("TECH-001")
                .build();

        sampleEntity = LabTest.builder()
                .id(sampleId)
                .appointmentId(sampleAppointmentId)
                .medicalHistoryId(sampleMedicalHistoryId)
                .doctorId(sampleDoctorId)
                .labTechnician(sampleTechnician)
                .labTestType(sampleType)
                .price(200000)
                .status("PENDING")
                .instructions("Test instructions")
                .createdAt(ZonedDateTime.now())
                .updatedAt(ZonedDateTime.now())
                .build();

        sampleDTO = LabTestDTO.builder()
                .id(sampleId)
                .appointmentId(sampleAppointmentId)
                .medicalHistoryId(sampleMedicalHistoryId)
                .doctorId(sampleDoctorId)
                .labTechnicianId(sampleTechId)
                .price(200000)
                .status("PENDING")
                .instructions("Test instructions")
                .build();
    }

    // ==========================================
    // UT_LT_001 -> UT_LT_008: createLabTest
    // ==========================================

    @Test
    @DisplayName("UT_LT_001 - createLabTest_withValidTypeAndTechnician_returnsDTO")
    void createLabTest_withValidTypeAndTechnician_returnsDTO() {
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .labTestTypeId(sampleTypeId)
                .labTechnicianId(sampleTechId)
                .price(200000)
                .status("PENDING")
                .build();

        when(labTestTypeRepository.findById(sampleTypeId)).thenReturn(Optional.of(sampleType));
        when(labTechnicianRepository.findById(sampleTechId)).thenReturn(Optional.of(sampleTechnician));
        when(mapper.toLabTestEntity(requestDTO, sampleType, sampleTechnician)).thenReturn(sampleEntity);
        when(labTestRepository.findByIdWithRelations(sampleEntity.getId())).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(sampleDTO);

        LabTestDTO result = labTestService.createLabTest(requestDTO);

        assertNotNull(result);
        assertEquals("PENDING", result.getStatus());
        verify(labTestRepository, times(1)).save(sampleEntity);
    }

    @Test
    @DisplayName("UT_LT_002 - createLabTest_withoutTechnician_doesNotCallTechRepo")
    void createLabTest_withoutTechnician_doesNotCallTechRepo() {
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .labTestTypeId(sampleTypeId)
                .labTechnicianId(null)
                .price(200000)
                .status("PENDING")
                .build();

        when(labTestTypeRepository.findById(sampleTypeId)).thenReturn(Optional.of(sampleType));
        when(mapper.toLabTestEntity(requestDTO, sampleType, null)).thenReturn(sampleEntity);
        when(labTestRepository.findByIdWithRelations(sampleEntity.getId())).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(sampleDTO);

        LabTestDTO result = labTestService.createLabTest(requestDTO);

        assertNotNull(result);
        verify(labTechnicianRepository, never()).findById(any());
    }

    @Test
    @DisplayName("UT_LT_003 - createLabTest_typeNotFound_throwsRuntimeException")
    void createLabTest_typeNotFound_throwsRuntimeException() {
        UUID nonExistentTypeId = UUID.randomUUID();
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .labTestTypeId(nonExistentTypeId)
                .build();

        when(labTestTypeRepository.findById(nonExistentTypeId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> labTestService.createLabTest(requestDTO));

        assertTrue(exception.getMessage().contains("LabTestType not found"));
        verify(labTestRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT_LT_004 - createLabTest_technicianNotFound_throwsRuntimeException")
    void createLabTest_technicianNotFound_throwsRuntimeException() {
        UUID nonExistentTechId = UUID.randomUUID();
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .labTestTypeId(sampleTypeId)
                .labTechnicianId(nonExistentTechId)
                .build();

        when(labTestTypeRepository.findById(sampleTypeId)).thenReturn(Optional.of(sampleType));
        when(labTechnicianRepository.findById(nonExistentTechId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> labTestService.createLabTest(requestDTO));

        assertTrue(exception.getMessage().contains("LabTechnician not found"));
    }

    @Test
    @DisplayName("UT_LT_005 - createLabTest_negativePrice_shouldRejectButCurrentlyDoesNot [INTENTIONAL FAIL]")
    void createLabTest_negativePrice_shouldRejectButCurrentlyDoesNot() {
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .labTestTypeId(sampleTypeId)
                .price(-500)
                .build();

        when(labTestTypeRepository.findById(sampleTypeId)).thenReturn(Optional.of(sampleType));
        when(mapper.toLabTestEntity(eq(requestDTO), eq(sampleType), any())).thenReturn(sampleEntity);
        when(labTestRepository.findByIdWithRelations(any())).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(sampleDTO);

        // Expected output per sheet LT: SHOULD throw IllegalArgumentException with message containing "price"
        // Service currently does NOT validate negative price, so this assertion will FAIL intentionally.
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> labTestService.createLabTest(requestDTO));
        assertTrue(exception.getMessage().toLowerCase().contains("price"));
    }

    @Test
    @DisplayName("UT_LT_006 - createLabTest_nullInstructions_createsSuccessfully")
    void createLabTest_nullInstructions_createsSuccessfully() {
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .labTestTypeId(sampleTypeId)
                .instructions(null)
                .price(200000)
                .build();

        when(labTestTypeRepository.findById(sampleTypeId)).thenReturn(Optional.of(sampleType));
        when(mapper.toLabTestEntity(requestDTO, sampleType, null)).thenReturn(sampleEntity);
        when(labTestRepository.findByIdWithRelations(sampleEntity.getId())).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(sampleDTO);

        LabTestDTO result = labTestService.createLabTest(requestDTO);

        assertNotNull(result);
        verify(labTestRepository, times(1)).save(sampleEntity);
    }

    @Test
    @DisplayName("UT_LT_007 - createLabTest_nullRequest_throwsNPE")
    void createLabTest_nullRequest_throwsNPE() {
        assertThrows(NullPointerException.class, () -> labTestService.createLabTest(null));
    }

    @Test
    @DisplayName("UT_LT_008 - createLabTest_repositorySaveFails_propagatesException")
    void createLabTest_repositorySaveFails_propagatesException() {
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .labTestTypeId(sampleTypeId)
                .build();

        when(labTestTypeRepository.findById(sampleTypeId)).thenReturn(Optional.of(sampleType));
        when(mapper.toLabTestEntity(requestDTO, sampleType, null)).thenReturn(sampleEntity);
        doThrow(new RuntimeException("DB connection failed")).when(labTestRepository).save(sampleEntity);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> labTestService.createLabTest(requestDTO));

        assertEquals("DB connection failed", exception.getMessage());
    }

    // ==========================================
    // UT_LT_009 -> UT_LT_014: updateLabTest
    // ==========================================

    @Test
    @DisplayName("UT_LT_009 - updateLabTest_withValidData_updatesAllFields")
    void updateLabTest_withValidData_updatesAllFields() {
        ZonedDateTime now = ZonedDateTime.now();
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .doctorId(sampleDoctorId)
                .labTechnicianId(sampleTechId)
                .price(300000)
                .status("ACCEPTED")
                .instructions("Updated instructions")
                .abnormalFlag("Normal")
                .units("mg/dL")
                .structureJson("{\"key\":\"value\"}")
                .referenceRange("70-99")
                .resultDate(now)
                .build();

        LabTestDTO updatedDTO = LabTestDTO.builder()
                .id(sampleId)
                .status("ACCEPTED")
                .price(300000)
                .build();

        when(labTestRepository.findById(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(labTechnicianRepository.findById(sampleTechId)).thenReturn(Optional.of(sampleTechnician));
        when(labTestRepository.findByIdWithRelations(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(updatedDTO);

        LabTestDTO result = labTestService.updateLabTest(sampleId, requestDTO);

        assertNotNull(result);
        assertEquals("ACCEPTED", result.getStatus());
        assertEquals(300000, result.getPrice());
        verify(labTestRepository, times(1)).save(sampleEntity);
    }

    @Test
    @DisplayName("UT_LT_010 - updateLabTest_idNotFound_throwsRuntimeException")
    void updateLabTest_idNotFound_throwsRuntimeException() {
        UUID nonExistentId = UUID.randomUUID();
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder().build();

        when(labTestRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> labTestService.updateLabTest(nonExistentId, requestDTO));

        assertTrue(exception.getMessage().contains("LabTest not found"));
    }

    @Test
    @DisplayName("UT_LT_011 - updateLabTest_nullTechnicianId_unlinksExistingTechnician")
    void updateLabTest_nullTechnicianId_unlinksExistingTechnician() {
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .labTechnicianId(null)
                .build();

        when(labTestRepository.findById(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(labTestRepository.findByIdWithRelations(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(sampleDTO);

        labTestService.updateLabTest(sampleId, requestDTO);

        assertNull(sampleEntity.getLabTechnician());
        verify(labTechnicianRepository, never()).findById(any());
    }

    @Test
    @DisplayName("UT_LT_012 - updateLabTest_newTechnicianNotFound_throwsRuntimeException")
    void updateLabTest_newTechnicianNotFound_throwsRuntimeException() {
        UUID nonExistentTechId = UUID.randomUUID();
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .labTechnicianId(nonExistentTechId)
                .build();

        when(labTestRepository.findById(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(labTechnicianRepository.findById(nonExistentTechId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> labTestService.updateLabTest(sampleId, requestDTO));

        assertTrue(exception.getMessage().contains("LabTechnician not found"));
    }

    @Test
    @DisplayName("UT_LT_013 - updateLabTest_nullResultDate_keepsExistingResultDate")
    void updateLabTest_nullResultDate_keepsExistingResultDate() {
        ZonedDateTime oldResultDate = ZonedDateTime.now().minusDays(1);
        sampleEntity.setResultDate(oldResultDate);

        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .resultDate(null)
                .build();

        when(labTestRepository.findById(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(labTestRepository.findByIdWithRelations(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(sampleDTO);

        labTestService.updateLabTest(sampleId, requestDTO);

        assertEquals(oldResultDate, sampleEntity.getResultDate());
    }

    @Test
    @DisplayName("UT_LT_014 - updateLabTest_invalidStatus_shouldRejectButCurrentlyAllows [INTENTIONAL FAIL]")
    void updateLabTest_invalidStatus_shouldRejectButCurrentlyAllows() {
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .status("INVALID_STATUS")
                .build();

        when(labTestRepository.findById(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(labTestRepository.findByIdWithRelations(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(sampleDTO);

        // Expected output per sheet LT: SHOULD throw IllegalArgumentException [FAIL - missing status validation]
        // Currently service accepts any String as status, so this assertion will FAIL intentionally.
        assertThrows(IllegalArgumentException.class,
                () -> labTestService.updateLabTest(sampleId, requestDTO));
    }

    // ==========================================
    // UT_LT_015 -> UT_LT_017: acceptLabTest
    // ==========================================

    @Test
    @DisplayName("UT_LT_015 - acceptLabTest_fromRequest_transitionsToAccepted")
    void acceptLabTest_fromRequest_transitionsToAccepted() {
        sampleEntity.setStatus("REQUEST");

        LabTestDTO acceptedDTO = LabTestDTO.builder()
                .id(sampleId)
                .status("ACCEPTED")
                .build();

        when(labTestRepository.findById(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(labTechnicianRepository.findById(sampleTechId)).thenReturn(Optional.of(sampleTechnician));
        when(labTestRepository.findByIdWithRelations(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(acceptedDTO);

        LabTestDTO result = labTestService.acceptLabTest(sampleId, sampleTechId);

        assertNotNull(result);
        assertEquals("ACCEPTED", result.getStatus());
        assertEquals("ACCEPTED", sampleEntity.getStatus());
        verify(labTestRepository, times(1)).save(sampleEntity);
    }

    @Test
    @DisplayName("UT_LT_016 - acceptLabTest_notFromRequest_throwsIllegalStateException")
    void acceptLabTest_notFromRequest_throwsIllegalStateException() {
        sampleEntity.setStatus("ACCEPTED");

        when(labTestRepository.findById(sampleId)).thenReturn(Optional.of(sampleEntity));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> labTestService.acceptLabTest(sampleId, sampleTechId));

        assertTrue(exception.getMessage().contains("REQUEST"));
    }

    @Test
    @DisplayName("UT_LT_017 - acceptLabTest_labTestNotFound_throwsException")
    void acceptLabTest_labTestNotFound_throwsException() {
        UUID nonExistentId = UUID.randomUUID();
        when(labTestRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> labTestService.acceptLabTest(nonExistentId, sampleTechId));

        assertTrue(exception.getMessage().contains("LabTest not found"));
    }

    // ==========================================
    // UT_LT_018 -> UT_LT_020: startLabTest
    // ==========================================

    @Test
    @DisplayName("UT_LT_018 - startLabTest_fromAccepted_transitionsToInProgress")
    void startLabTest_fromAccepted_transitionsToInProgress() {
        sampleEntity.setStatus("ACCEPTED");

        LabTestDTO inProgressDTO = LabTestDTO.builder()
                .id(sampleId)
                .status("IN_PROGRESS")
                .build();

        when(labTestRepository.findById(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(labTestRepository.findByIdWithRelations(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(inProgressDTO);

        LabTestDTO result = labTestService.startLabTest(sampleId);

        assertNotNull(result);
        assertEquals("IN_PROGRESS", result.getStatus());
        assertEquals("IN_PROGRESS", sampleEntity.getStatus());
    }

    @Test
    @DisplayName("UT_LT_019 - startLabTest_notFromAccepted_throwsIllegalStateException")
    void startLabTest_notFromAccepted_throwsIllegalStateException() {
        sampleEntity.setStatus("REQUEST");

        when(labTestRepository.findById(sampleId)).thenReturn(Optional.of(sampleEntity));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> labTestService.startLabTest(sampleId));

        assertTrue(exception.getMessage().contains("ACCEPTED"));
    }

    @Test
    @DisplayName("UT_LT_020 - startLabTest_notFound_throwsRuntimeException")
    void startLabTest_notFound_throwsRuntimeException() {
        UUID nonExistentId = UUID.randomUUID();
        when(labTestRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> labTestService.startLabTest(nonExistentId));

        assertTrue(exception.getMessage().contains("LabTest not found"));
    }

    // ==========================================
    // UT_LT_021 -> UT_LT_024: completeLabTest
    // ==========================================

    @Test
    @DisplayName("UT_LT_021 - completeLabTest_fromInProgress_completesAndPublishesEvents")
    void completeLabTest_fromInProgress_completesAndPublishesEvents() {
        sampleEntity.setStatus("IN_PROGRESS");
        sampleEntity.setAppointmentId(sampleAppointmentId);

        ZonedDateTime now = ZonedDateTime.now();
        LabTestRequestDTO completeDTO = LabTestRequestDTO.builder()
                .structureJson("{\"result\":\"ok\"}")
                .abnormalFlag("Normal")
                .units("g/L")
                .referenceRange("120-160")
                .resultDate(now)
                .build();

        LabTestDTO completeResponseDTO = LabTestDTO.builder()
                .id(sampleId)
                .status("COMPLETE")
                .resultDate(now)
                .build();

        when(labTestRepository.findById(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(labTestRepository.findByIdWithRelations(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(completeResponseDTO);

        LabTestDTO result = labTestService.completeLabTest(sampleId, completeDTO);

        assertNotNull(result);
        assertEquals("COMPLETE", result.getStatus());
        assertEquals("COMPLETE", sampleEntity.getStatus());
        assertNotNull(sampleEntity.getResultDate());
        verify(invoiceServiceClient, times(1)).addLabTestCharge(any());
        verify(eventBus, times(1)).publish(any(EventMessage.class));
    }

    @Test
    @DisplayName("UT_LT_022 - completeLabTest_notFromInProgress_throwsIllegalStateException")
    void completeLabTest_notFromInProgress_throwsIllegalStateException() {
        sampleEntity.setStatus("ACCEPTED");

        when(labTestRepository.findById(sampleId)).thenReturn(Optional.of(sampleEntity));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> labTestService.completeLabTest(sampleId, new LabTestRequestDTO()));

        assertTrue(exception.getMessage().contains("IN_PROGRESS"));
    }

    @Test
    @DisplayName("UT_LT_023 - completeLabTest_invoiceServiceFails_propagatesException")
    void completeLabTest_invoiceServiceFails_propagatesException() {
        sampleEntity.setStatus("IN_PROGRESS");
        sampleEntity.setAppointmentId(sampleAppointmentId);

        when(labTestRepository.findById(sampleId)).thenReturn(Optional.of(sampleEntity));
        doThrow(new RuntimeException("Invoice microservice connection error"))
                .when(invoiceServiceClient).addLabTestCharge(any());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> labTestService.completeLabTest(sampleId, null));

        assertTrue(exception.getMessage().contains("Add lab test charge failed"));
    }

    @Test
    @DisplayName("UT_LT_024 - completeLabTest_nullDto_onlyChangesStatus")
    void completeLabTest_nullDto_onlyChangesStatus() {
        sampleEntity.setStatus("IN_PROGRESS");
        sampleEntity.setAppointmentId(sampleAppointmentId);

        LabTestDTO completeResponseDTO = LabTestDTO.builder()
                .id(sampleId)
                .status("COMPLETE")
                .build();

        when(labTestRepository.findById(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(labTestRepository.findByIdWithRelations(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(completeResponseDTO);

        assertDoesNotThrow(() -> {
            LabTestDTO result = labTestService.completeLabTest(sampleId, null);
            assertEquals("COMPLETE", result.getStatus());
            assertEquals("COMPLETE", sampleEntity.getStatus());
        });
    }

    // ==========================================
    // UT_LT_025 -> UT_LT_026: deleteLabTest
    // ==========================================

    @Test
    @DisplayName("UT_LT_025 - deleteLabTest_existingId_deletesSuccessfully")
    void deleteLabTest_existingId_deletesSuccessfully() {
        when(labTestRepository.existsById(sampleId)).thenReturn(true);

        labTestService.deleteLabTest(sampleId);

        verify(labTestRepository, times(1)).deleteById(sampleId);
    }

    @Test
    @DisplayName("UT_LT_026 - deleteLabTest_notFound_throwsRuntimeException")
    void deleteLabTest_notFound_throwsRuntimeException() {
        UUID nonExistentId = UUID.randomUUID();
        when(labTestRepository.existsById(nonExistentId)).thenReturn(false);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> labTestService.deleteLabTest(nonExistentId));

        assertTrue(exception.getMessage().contains("LabTest not found"));
        verify(labTestRepository, never()).deleteById(any());
    }

    // ==========================================
    // UT_LT_027 -> UT_LT_028: getLabTestById
    // ==========================================

    @Test
    @DisplayName("UT_LT_027 - getLabTestById_existingId_returnsDTO")
    void getLabTestById_existingId_returnsDTO() {
        when(labTestRepository.findByIdWithRelations(sampleId)).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(sampleDTO);

        LabTestDTO result = labTestService.getLabTestById(sampleId);

        assertNotNull(result);
        assertEquals(sampleDTO.getId(), result.getId());
    }

    @Test
    @DisplayName("UT_LT_028 - getLabTestById_notFound_throwsRuntimeException")
    void getLabTestById_notFound_throwsRuntimeException() {
        UUID nonExistentId = UUID.randomUUID();
        when(labTestRepository.findByIdWithRelations(nonExistentId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> labTestService.getLabTestById(nonExistentId));

        assertTrue(exception.getMessage().contains("LabTest not found"));
    }

    // ==========================================
    // UT_LT_029: getLabTestsByStatus
    // ==========================================

    @Test
    @DisplayName("UT_LT_029 - getLabTestsByStatus_returnsFilteredList")
    void getLabTestsByStatus_returnsFilteredList() {
        LabTest test1 = LabTest.builder().id(UUID.randomUUID()).status("REQUEST").build();
        LabTest test2 = LabTest.builder().id(UUID.randomUUID()).status("REQUEST").build();
        LabTestDTO dto1 = LabTestDTO.builder().id(test1.getId()).status("REQUEST").build();
        LabTestDTO dto2 = LabTestDTO.builder().id(test2.getId()).status("REQUEST").build();

        when(labTestRepository.findByStatus("REQUEST")).thenReturn(Arrays.asList(test1, test2));
        when(mapper.toLabTestDTO(test1)).thenReturn(dto1);
        when(mapper.toLabTestDTO(test2)).thenReturn(dto2);

        List<LabTestDTO> result = labTestService.getLabTestsByStatus("REQUEST");

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(dto -> "REQUEST".equals(dto.getStatus())));
    }

    // ==========================================
    // UT_LT_030 -> UT_LT_031: requestLabTest
    // ==========================================

    @Test
    @DisplayName("UT_LT_030 - requestLabTest_withValidType_savesAndPublishesEvent")
    void requestLabTest_withValidType_savesAndPublishesEvent() {
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .appointmentId(sampleAppointmentId)
                .medicalHistoryId(sampleMedicalHistoryId)
                .doctorId(sampleDoctorId)
                .labTestTypeId(sampleTypeId)
                .price(150000)
                .instructions("Check iron levels")
                .build();

        LabTestDTO requestedDTO = LabTestDTO.builder()
                .id(sampleId)
                .status("REQUEST")
                .build();

        when(labTestTypeRepository.findById(sampleTypeId)).thenReturn(Optional.of(sampleType));
        when(labTestRepository.findByIdWithRelations(any())).thenReturn(Optional.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(requestedDTO);

        LabTestDTO result = labTestService.requestLabTest(requestDTO);

        assertNotNull(result);
        assertEquals("REQUEST", result.getStatus());
        verify(labTestRepository, times(1)).save(any(LabTest.class));
        verify(eventBus, times(1)).publish(any(EventMessage.class));
    }

    @Test
    @DisplayName("UT_LT_031 - requestLabTest_typeNotFound_throwsRuntimeException")
    void requestLabTest_typeNotFound_throwsRuntimeException() {
        UUID nonExistentTypeId = UUID.randomUUID();
        LabTestRequestDTO requestDTO = LabTestRequestDTO.builder()
                .labTestTypeId(nonExistentTypeId)
                .build();

        when(labTestTypeRepository.findById(nonExistentTypeId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> labTestService.requestLabTest(requestDTO));

        assertTrue(exception.getMessage().contains("LabTestType not found"));
        verify(labTestRepository, never()).save(any());
    }

    // ==========================================
    // UT_LT_032: getAllLabTests
    // ==========================================

    @Test
    @DisplayName("UT_LT_032 - getAllLabTests_returnsAllMappedDtos")
    void getAllLabTests_returnsAllMappedDtos() {
        LabTest test1 = LabTest.builder().id(UUID.randomUUID()).status("REQUEST").build();
        LabTest test2 = LabTest.builder().id(UUID.randomUUID()).status("COMPLETE").build();
        LabTestDTO dto1 = LabTestDTO.builder().id(test1.getId()).status("REQUEST").build();
        LabTestDTO dto2 = LabTestDTO.builder().id(test2.getId()).status("COMPLETE").build();

        when(labTestRepository.findAllWithRelations()).thenReturn(Arrays.asList(test1, test2));
        when(mapper.toLabTestDTO(test1)).thenReturn(dto1);
        when(mapper.toLabTestDTO(test2)).thenReturn(dto2);

        List<LabTestDTO> result = labTestService.getAllLabTests();

        assertEquals(2, result.size());
        assertEquals("REQUEST", result.get(0).getStatus());
        assertEquals("COMPLETE", result.get(1).getStatus());
    }

    // ==========================================
    // UT_LT_033 -> UT_LT_036: Query methods by relations
    // ==========================================

    @Test
    @DisplayName("UT_LT_033 - getLabTestsByDoctorId_returnsMappedList")
    void getLabTestsByDoctorId_returnsMappedList() {
        when(labTestRepository.findByDoctorId(sampleDoctorId)).thenReturn(List.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(sampleDTO);

        List<LabTestDTO> result = labTestService.getLabTestsByDoctorId(sampleDoctorId);

        assertEquals(1, result.size());
        assertEquals(sampleEntity.getId(), result.get(0).getId());
    }

    @Test
    @DisplayName("UT_LT_034 - getLabTestsByLabTechnicianId_returnsMappedList")
    void getLabTestsByLabTechnicianId_returnsMappedList() {
        when(labTestRepository.findByLabTechnician_UserId(sampleTechId)).thenReturn(List.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(sampleDTO);

        List<LabTestDTO> result = labTestService.getLabTestsByLabTechnicianId(sampleTechId);

        assertEquals(1, result.size());
        assertEquals(sampleEntity.getId(), result.get(0).getId());
    }

    @Test
    @DisplayName("UT_LT_035 - getLabTestsByAppointmentId_returnsMappedList")
    void getLabTestsByAppointmentId_returnsMappedList() {
        when(labTestRepository.findByAppointmentId(sampleAppointmentId)).thenReturn(List.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(sampleDTO);

        List<LabTestDTO> result = labTestService.getLabTestsByAppointmentId(sampleAppointmentId);

        assertEquals(1, result.size());
        assertEquals(sampleEntity.getId(), result.get(0).getId());
    }

    @Test
    @DisplayName("UT_LT_036 - getLabTestsByMedicalHistoryId_returnsMappedList")
    void getLabTestsByMedicalHistoryId_returnsMappedList() {
        when(labTestRepository.findByMedicalHistoryId(sampleMedicalHistoryId)).thenReturn(List.of(sampleEntity));
        when(mapper.toLabTestDTO(sampleEntity)).thenReturn(sampleDTO);

        List<LabTestDTO> result = labTestService.getLabTestsByMedicalHistoryId(sampleMedicalHistoryId);

        assertEquals(1, result.size());
        assertEquals(sampleEntity.getId(), result.get(0).getId());
    }
}
