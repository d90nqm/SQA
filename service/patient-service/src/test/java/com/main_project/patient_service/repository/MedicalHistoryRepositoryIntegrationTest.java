package com.main_project.patient_service.repository;

import com.main_project.patient_service.entity.MedicalHistory;
import com.main_project.patient_service.entity.Patient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional
class MedicalHistoryRepositoryIntegrationTest {

    @Autowired
    private MedicalHistoryRepository medicalHistoryRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Test
    @DisplayName("UT_MHS_IT_001 - saveMedicalHistory_shouldPersistToDatabase")
    void saveMedicalHistory_shouldPersistToDatabase() {
        UUID userId = UUID.randomUUID();
        Patient patient = Patient.builder()
                .userId(userId)
                .build();
        patientRepository.save(patient);

        UUID historyId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        MedicalHistory history = MedicalHistory.builder()
                .id(historyId)
                .appointmentId(appointmentId)
                .symptoms("Đau chân răng")
                .patient(patient)
                .build();

        MedicalHistory saved = medicalHistoryRepository.save(history);

        assertNotNull(saved.getId());
        Optional<MedicalHistory> found = medicalHistoryRepository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("Đau chân răng", found.get().getSymptoms());
        assertEquals(userId, found.get().getPatient().getUserId());
        assertEquals(1L, medicalHistoryRepository.count());
    }

    @Test
    @DisplayName("UT_MHS_IT_002 - deleteMedicalHistory_shouldRemoveFromDatabase")
    void deleteMedicalHistory_shouldRemoveFromDatabase() {
        UUID userId = UUID.randomUUID();
        Patient patient = Patient.builder()
                .userId(userId)
                .build();
        patientRepository.save(patient);

        UUID historyId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        MedicalHistory history = MedicalHistory.builder()
                .id(historyId)
                .appointmentId(appointmentId)
                .symptoms("Đau chân răng")
                .patient(patient)
                .build();

        MedicalHistory saved = medicalHistoryRepository.save(history);
        assertNotNull(saved.getId());

        medicalHistoryRepository.deleteById(saved.getId());

        Optional<MedicalHistory> found = medicalHistoryRepository.findById(saved.getId());
        assertTrue(found.isEmpty());
        assertEquals(0L, medicalHistoryRepository.count());
    }
}
