package com.main_project.appointment_service.service;

import com.main_project.appointment_service.entity.MedicalService;
import com.main_project.appointment_service.feignclient.DoctorServiceClient;
import com.main_project.appointment_service.repository.AppointmentRepository;
import com.main_project.appointment_service.repository.MedicalServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses isolated Redis and repository mocks to exercise the lock and duration contracts. */
@ExtendWith(MockitoExtension.class)
class SlotServiceTest {
    @Mock private AppointmentRepository appointments;
    @Mock private MedicalServiceRepository medicalServices;
    @Mock private RedisTemplate<String, Object> redis;
    @Mock private ValueOperations<String, Object> redisValues;
    @Mock private DoctorServiceClient doctorClient;

    private final UUID doctorId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final ZonedDateTime start = ZonedDateTime.parse("2030-03-15T09:00:00+07:00");
    private final ZonedDateTime end = start.plusMinutes(30);
    private final String slotKey = "slot:" + doctorId + ":20300315:0900";
    private SlotService service;

    @BeforeEach
    void createService() {
        service = new SlotService(appointments, medicalServices, redis, doctorClient);
    }

    // Test Case ID: UT_SLOT_001. Acquire the slot with a 900-second expiry
    @Test
    @DisplayName("UT_SLOT_001 — Acquire the slot with a 900-second expiry")
    void UT_SLOT_001_lockSlot_success() {
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.setIfAbsent(slotKey, payload(patientId, end), 900L, TimeUnit.SECONDS)).thenReturn(true);

        assertTrue(service.lockSlot(doctorId, patientId, start, end));
        verify(redisValues).setIfAbsent(slotKey, payload(patientId, end), 900L, TimeUnit.SECONDS);
    }

    // Test Case ID: UT_SLOT_002. Reject a slot with an existing Redis key
    @Test
    @DisplayName("UT_SLOT_002 — Reject a slot with an existing Redis key")
    void UT_SLOT_002_lockSlot_fail_alreadyLocked() {
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.setIfAbsent(slotKey, payload(patientId, end), 900L, TimeUnit.SECONDS)).thenReturn(false);

        assertFalse(service.lockSlot(doctorId, patientId, start, end));
        verify(redisValues).setIfAbsent(slotKey, payload(patientId, end), 900L, TimeUnit.SECONDS);
    }

    // Test Case ID: UT_SLOT_003. Return false safely when Redis returns null
    @Test
    @DisplayName("UT_SLOT_003 — Return false safely when Redis returns null")
    void UT_SLOT_003_lockSlot_fail_redisReturnsNull() {
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.setIfAbsent(slotKey, payload(patientId, end), 900L, TimeUnit.SECONDS)).thenReturn(null);

        assertFalse(service.lockSlot(doctorId, patientId, start, end));
        verify(redisValues).setIfAbsent(slotKey, payload(patientId, end), 900L, TimeUnit.SECONDS);
    }

    // Test Case ID: UT_SLOT_004. Delete the exact Redis key when releasing a slot
    @Test
    @DisplayName("UT_SLOT_004 — Delete the exact Redis key when releasing a slot")
    void UT_SLOT_004_unlockSlot_success_deletesRedisKey() {
        service.unlockSlot(doctorId, start);
        verify(redis).delete(slotKey);
    }

    // Test Case ID: UT_SLOT_005. Release a slot held by the matching patient and time
    @Test
    @DisplayName("UT_SLOT_005 — Release a slot held by the matching patient and time")
    void UT_SLOT_005_validateAndUnlockSlot_success() {
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.get(slotKey)).thenReturn(payload(patientId, end));

        assertTrue(service.validateAndUnlockSlot(doctorId, patientId, start, end));
        verify(redisValues).get(slotKey);
        verify(redis).delete(slotKey);
    }

    // Test Case ID: UT_SLOT_006. Keep Redis unchanged when the lock has expired
    @Test
    @DisplayName("UT_SLOT_006 — Keep Redis unchanged when the lock has expired")
    void UT_SLOT_006_validateAndUnlockSlot_fail_expired() {
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.get(slotKey)).thenReturn(null);

        assertFalse(service.validateAndUnlockSlot(doctorId, patientId, start, end));
        verify(redisValues).get(slotKey);
        verify(redis, never()).delete(slotKey);
    }

    // Test Case ID: UT_SLOT_007. Keep the lock held by a different patient
    @Test
    @DisplayName("UT_SLOT_007 — Keep the lock held by a different patient")
    void UT_SLOT_007_validateAndUnlockSlot_fail_differentPatient() {
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.get(slotKey)).thenReturn(payload(UUID.randomUUID(), end));

        assertFalse(service.validateAndUnlockSlot(doctorId, patientId, start, end));
        verify(redisValues).get(slotKey);
        verify(redis, never()).delete(slotKey);
    }

    // Test Case ID: UT_SLOT_008. Keep the lock when the requested end time differs
    @Test
    @DisplayName("UT_SLOT_008 — Keep the lock when the requested end time differs")
    void UT_SLOT_008_validateAndUnlockSlot_fail_slotEndMismatch() {
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.get(slotKey)).thenReturn(payload(patientId, end));

        assertFalse(service.validateAndUnlockSlot(doctorId, patientId, start, start.plusMinutes(60)));
        verify(redisValues).get(slotKey);
        verify(redis, never()).delete(slotKey);
    }

    // Test Case ID: UT_SLOT_009. Reject a null list of service IDs
    @Test
    @DisplayName("UT_SLOT_009 — Reject a null list of service IDs")
    void UT_SLOT_009_calculateDuration_fail_nullList() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.calculateServiceDurationMinutes(null));
        assertTrue(failure.getMessage().contains("At least one medical service"));
        verifyNoInteractions(medicalServices);
    }

    // Test Case ID: UT_SLOT_010. Reject an empty list of service IDs
    @Test
    @DisplayName("UT_SLOT_010 — Reject an empty list of service IDs")
    void UT_SLOT_010_calculateDuration_fail_emptyList() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.calculateServiceDurationMinutes(List.of()));
        assertTrue(failure.getMessage().contains("At least one medical service"));
        verifyNoInteractions(medicalServices);
    }

    // Test Case ID: UT_SLOT_011. Reject duration calculation when a service is missing
    @Test
    @DisplayName("UT_SLOT_011 — Reject duration calculation when a service is missing")
    void UT_SLOT_011_calculateDuration_fail_partialNotFound() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(medicalServices.findAllById(ids)).thenReturn(List.of(serviceWithDuration(ids.get(0), 30)));

        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.calculateServiceDurationMinutes(ids));
        assertTrue(failure.getMessage().contains("Some medical services not found"));
        verify(medicalServices).findAllById(ids);
    }

    // Test Case ID: UT_SLOT_012. Return 30 minutes for one service
    @Test
    @DisplayName("UT_SLOT_012 — Return 30 minutes for one service")
    void UT_SLOT_012_calculateDuration_success_oneService() {
        UUID serviceId = UUID.randomUUID();
        List<UUID> ids = List.of(serviceId);
        when(medicalServices.findAllById(ids)).thenReturn(List.of(serviceWithDuration(serviceId, 30)));

        assertEquals(30, service.calculateServiceDurationMinutes(ids));
        verify(medicalServices).findAllById(ids);
    }

    // Test Case ID: UT_SLOT_013. Sum 30 and 45 minutes across two services
    @Test
    @DisplayName("UT_SLOT_013 — Sum 30 and 45 minutes across two services")
    void UT_SLOT_013_calculateDuration_success_multipleServices() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(medicalServices.findAllById(ids))
                .thenReturn(List.of(serviceWithDuration(ids.get(0), 30), serviceWithDuration(ids.get(1), 45)));

        assertEquals(75, service.calculateServiceDurationMinutes(ids));
        verify(medicalServices).findAllById(ids);
    }

    private String payload(UUID holder, ZonedDateTime storedEnd) {
        return holder + "|" + start + "|" + storedEnd;
    }

    private MedicalService serviceWithDuration(UUID id, int durationMinutes) {
        return MedicalService.builder().id(id).serviceTime(durationMinutes).build();
    }
}
