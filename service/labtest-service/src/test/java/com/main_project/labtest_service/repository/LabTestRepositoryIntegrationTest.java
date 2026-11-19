package com.main_project.labtest_service.repository;

import com.main_project.labtest_service.entity.LabTest;
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
class LabTestRepositoryIntegrationTest {

    @Autowired
    private LabTestRepository labTestRepository;

    @Test
    @DisplayName("UT_LT_IT_001 - saveLabTest_shouldPersistToDatabase")
    void saveLabTest_shouldPersistToDatabase() {
        UUID id = UUID.randomUUID();
        LabTest labTest = LabTest.builder()
                .id(id)
                .status("REQUEST")
                .price(150000)
                .build();

        LabTest saved = labTestRepository.save(labTest);

        assertNotNull(saved.getId());
        Optional<LabTest> found = labTestRepository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("REQUEST", found.get().getStatus());
        assertEquals(150000, found.get().getPrice());
        assertEquals(1L, labTestRepository.count());
    }

    @Test
    @DisplayName("UT_LT_IT_002 - deleteLabTest_shouldRemoveFromDatabase")
    void deleteLabTest_shouldRemoveFromDatabase() {
        UUID id = UUID.randomUUID();
        LabTest labTest = LabTest.builder()
                .id(id)
                .status("REQUEST")
                .price(150000)
                .build();

        LabTest saved = labTestRepository.save(labTest);
        assertNotNull(saved.getId());

        labTestRepository.deleteById(saved.getId());

        Optional<LabTest> found = labTestRepository.findById(saved.getId());
        assertTrue(found.isEmpty());
        assertEquals(0L, labTestRepository.count());
    }
}
