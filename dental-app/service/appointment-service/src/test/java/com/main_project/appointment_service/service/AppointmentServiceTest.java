package com.main_project.appointment_service.service;

import com.do_an.common.event.AppointmentCreatedEvent;
import com.main_project.appointment_service.dto.AppointmentDTO;
import com.main_project.appointment_service.dto.AppointmentRequestDTO;
import com.main_project.appointment_service.entity.Appointment;
import com.main_project.appointment_service.entity.MedicalService;
import com.main_project.appointment_service.enums.AppointmentStatus;
import com.main_project.appointment_service.enums.MedicalServiceStatus;
import com.main_project.appointment_service.exceptions.AppException;
import com.main_project.appointment_service.exceptions.enums.ErrorCode;
import com.main_project.appointment_service.feignclient.DoctorServiceClient;
import com.main_project.appointment_service.feignclient.UserServiceClient;
import com.main_project.appointment_service.repository.AppointmentRepository;
import com.main_project.appointment_service.repository.MedicalServiceRepository;
import com.main_project.appointment_service.util.EntityDTOMapper;
import jakarta.persistence.EntityManager;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventhandling.EventMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.transaction.AfterTransaction;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;



import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises appointment rules and checks repository writes and notification interactions. */
@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {
    @Mock private UserServiceClient userClient;
    @Mock private AppointmentRepository appointments;
    @Mock private MedicalServiceRepository medicalServices;
    @Mock private SlotService slots;
    @Mock private EventBus events;

    private final UUID doctorId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final ZonedDateTime start = ZonedDateTime.parse("2030-03-15T09:00:00+07:00");
    private AppointmentService service;

    @BeforeEach
    void createService() {
        service = new AppointmentService(userClient, appointments, medicalServices, slots,
                new EntityDTOMapper(), events);
    }

    // Test Case ID: UT_APPT_001. Reject a null service list
    @Test
    @DisplayName("UT_APPT_001 — Reject a null service list")
    void UT_APPT_001_createAppointment_fail_nullServiceList() {
        assertEquals(ErrorCode.LIST_MEDICAL_SERVICE_EMPTY,
                assertThrows(AppException.class, () -> service.createAppointment(request(null))).getErrorCode());
        verifyNoInteractions(appointments, medicalServices, slots, events);
    }

    // Test Case ID: UT_APPT_002. Reject an empty service list
    @Test
    @DisplayName("UT_APPT_002 — Reject an empty service list")
    void UT_APPT_002_createAppointment_fail_emptyServiceList() {
        assertEquals(ErrorCode.LIST_MEDICAL_SERVICE_EMPTY,
                assertThrows(AppException.class, () -> service.createAppointment(request(List.of()))).getErrorCode());
        verifyNoInteractions(appointments, medicalServices, slots, events);
    }

    // Test Case ID: UT_APPT_003. Reject a partially valid service list
    @Test
    @DisplayName("UT_APPT_003 — Reject a partially valid service list")
    void UT_APPT_003_createAppointment_fail_partialServiceNotFound() {
        MedicalService found = medicalService(30);
        List<UUID> ids = List.of(found.getId(), UUID.randomUUID());
        when(medicalServices.findAllById(ids)).thenReturn(List.of(found));
        assertEquals(ErrorCode.INVALID_MEDICAL_SERVICE_ID,
                assertThrows(AppException.class, () -> service.createAppointment(request(ids))).getErrorCode());
        verify(medicalServices).findAllById(ids);
        verifyNoInteractions(appointments, slots, events);
    }

    // Test Case ID: UT_APPT_004. Reject a list with no matching services
    @Test
    @DisplayName("UT_APPT_004 — Reject a list with no matching services")
    void UT_APPT_004_createAppointment_fail_allServicesNotFound() {
        List<UUID> ids = List.of(UUID.randomUUID());
        when(medicalServices.findAllById(ids)).thenReturn(List.of());
        assertEquals(ErrorCode.INVALID_MEDICAL_SERVICE_ID,
                assertThrows(AppException.class, () -> service.createAppointment(request(ids))).getErrorCode());
        verify(medicalServices).findAllById(ids);
        verifyNoInteractions(appointments, slots, events);
    }

    // Test Case ID: UT_APPT_005. Reject an expired or invalid Redis slot
    @Test
    @DisplayName("UT_APPT_005 — Reject an expired or invalid Redis slot")
    void UT_APPT_005_createAppointment_fail_slotExpiredOrInvalid() {
        MedicalService consultation = medicalService(30);
        List<UUID> ids = List.of(consultation.getId());
        when(medicalServices.findAllById(ids)).thenReturn(List.of(consultation));
        when(slots.validateAndUnlockSlot(doctorId, patientId, start, start.plusMinutes(30))).thenReturn(false);
        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.createAppointment(request(ids)));
        assertTrue(failure.getMessage().contains("Slot đã hết hạn"));
        verify(slots).validateAndUnlockSlot(doctorId, patientId, start, start.plusMinutes(30));
        verifyNoInteractions(appointments, events);
    }

    // Test Case ID: UT_APPT_006. Reject an overlapping appointment found by the repository
    @Test
    @DisplayName("UT_APPT_006 — Reject an overlapping appointment found by the repository")
    void UT_APPT_006_createAppointment_fail_dbOverlap() {
        MedicalService consultation = medicalService(30);
        List<UUID> ids = List.of(consultation.getId());
        ZonedDateTime end = start.plusMinutes(30);
        when(medicalServices.findAllById(ids)).thenReturn(List.of(consultation));
        when(slots.validateAndUnlockSlot(doctorId, patientId, start, end)).thenReturn(true);
        when(appointments.findOverlappingAppointments(doctorId, start, end)).thenReturn(List.of(new Appointment()));

        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.createAppointment(request(ids)));
        assertTrue(failure.getMessage().contains("not available"));
        verify(appointments).findOverlappingAppointments(doctorId, start, end);
        verify(appointments, never()).save(any());
        verify(slots, atLeastOnce()).unlockSlot(doctorId, start);
        verifyNoInteractions(events);
    }

    // Test Case ID: UT_APPT_007. Create a confirmed appointment for one 30-minute service
    @Test
    @DisplayName("UT_APPT_007 — Create a confirmed appointment for one 30-minute service")
    void UT_APPT_007_createAppointment_success_oneService() {
        AppointmentRequestDTO booking = availableBooking(List.of(medicalService(30)), 30);
        AppointmentDTO confirmed = service.createAppointment(booking);

        ArgumentCaptor<Appointment> row = ArgumentCaptor.forClass(Appointment.class);
        verify(appointments).save(row.capture());
        assertEquals(AppointmentStatus.CONFIRMED, row.getValue().getStatus());
        assertEquals(AppointmentStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(patientId, row.getValue().getPatientId());
        assertEquals(doctorId, row.getValue().getDoctorId());
        assertEquals(start.plusMinutes(30), row.getValue().getAppointmentEndTime());
        verify(events).publish(argThat((EventMessage<?> message) ->
                message.getPayload() instanceof AppointmentCreatedEvent event
                        && confirmed.getId().equals(event.getAppointmentId())));
        verify(slots).unlockSlot(doctorId, start);
    }

    // Test Case ID: UT_APPT_008. Use 75 minutes for services of 30 and 45 minutes
    @Test
    @DisplayName("UT_APPT_008 — Use 75 minutes for services of 30 and 45 minutes")
    void UT_APPT_008_createAppointment_success_multipleServices() {
        AppointmentRequestDTO booking = availableBooking(List.of(medicalService(30), medicalService(45)), 75);
        AppointmentDTO confirmed = service.createAppointment(booking);

        verify(slots).validateAndUnlockSlot(doctorId, patientId, start, start.plusMinutes(75));
        verify(appointments).save(argThat(row -> row.getMedicalServices().size() == 2
                && row.getAppointmentEndTime().equals(start.plusMinutes(75))));
        assertEquals(start.plusMinutes(75), confirmed.getAppointmentEndTime());
    }

    // Test Case ID: UT_APPT_009. Keep the booking when publishing the notification fails
    @Test
    @DisplayName("UT_APPT_009 — Keep the booking when publishing the notification fails")
    void UT_APPT_009_createAppointment_success_eventBusFailsButSwallowed() {
        AppointmentRequestDTO booking = availableBooking(List.of(medicalService(30)), 30);
        doThrow(new RuntimeException("Notification delivery failed")).when(events).publish(any(EventMessage.class));

        AppointmentDTO confirmed = assertDoesNotThrow(() -> service.createAppointment(booking));
        assertNotNull(confirmed);
        assertEquals(AppointmentStatus.CONFIRMED, confirmed.getStatus());
        InOrder sequence = inOrder(appointments, events);
        sequence.verify(appointments).save(any(Appointment.class));
        sequence.verify(events).publish(any(EventMessage.class));
    }

    // Test Case ID: UT_APPT_010. Save an existing appointment as CANCELLED with a new timestamp
    @Test
    @DisplayName("UT_APPT_010 — Save an existing appointment as CANCELLED with a new timestamp")
    void UT_APPT_010_deleteAppointment_success_setsStatusCancelled() {
        UUID appointmentId = UUID.randomUUID();
        Appointment existing = Appointment.builder().id(appointmentId).status(AppointmentStatus.CONFIRMED)
                .updatedAt(start.minusYears(20)).build();
        ZonedDateTime oldTimestamp = existing.getUpdatedAt();
        when(appointments.findById(appointmentId)).thenReturn(Optional.of(existing));

        service.deleteAppointment(appointmentId);

        verify(appointments).findById(appointmentId);
        verify(appointments).save(argThat(row -> row.getId().equals(appointmentId)
                && row.getStatus() == AppointmentStatus.CANCELLED
                && row.getUpdatedAt().isAfter(oldTimestamp)));
        verify(appointments, never()).delete(any());
    }

    // Test Case ID: UT_APPT_011. Reject cancellation when the appointment ID does not exist
    @Test
    @DisplayName("UT_APPT_011 — Reject cancellation when the appointment ID does not exist")
    void UT_APPT_011_deleteAppointment_fail_notFound() {
        UUID missingId = UUID.randomUUID();
        when(appointments.findById(missingId)).thenReturn(Optional.empty());
        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.deleteAppointment(missingId));
        assertTrue(failure.getMessage().contains("Appointment not found"));
        verify(appointments).findById(missingId);
        verify(appointments, never()).save(any());
    }

    private AppointmentRequestDTO request(List<UUID> serviceIds) {
        return AppointmentRequestDTO.builder().doctorId(doctorId).patientId(patientId)
                .appointmentStartTime(start).MedicalServiceIds(serviceIds).build();
    }

    private MedicalService medicalService(int durationMinutes) {
        return MedicalService.builder().id(UUID.randomUUID()).serviceTime(durationMinutes).build();
    }

    private AppointmentRequestDTO availableBooking(List<MedicalService> services, int durationMinutes) {
        List<UUID> ids = services.stream().map(MedicalService::getId).toList();
        ZonedDateTime end = start.plusMinutes(durationMinutes);
        when(medicalServices.findAllById(ids)).thenReturn(services);
        when(slots.validateAndUnlockSlot(doctorId, patientId, start, end)).thenReturn(true);
        when(appointments.findOverlappingAppointments(doctorId, start, end)).thenReturn(List.of());
        // Simulate the generated ID normally assigned by JPA on insert.
        when(appointments.save(any(Appointment.class))).thenAnswer(call -> {
            Appointment saved = call.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        return request(ids);
    }
}

/** Complements Mockito verifications with physical JPA writes and reads in an isolated database. */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = AppointmentPersistenceTest.JpaConfiguration.class)
class AppointmentPersistenceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Appointment.class)
    @EnableJpaRepositories(basePackageClasses = AppointmentRepository.class)
    static class JpaConfiguration {}

    @Autowired EntityManager database;
    @Autowired JdbcTemplate sql;
    @Autowired AppointmentRepository appointments;
    @Autowired MedicalServiceRepository medicalServices;

    // Test Case IDs: UT_APPT_006, UT_APPT_007, UT_APPT_008, UT_APPT_010, UT_SLOT_013 (supplemental CheckDB).
    @Test
    @DisplayName("CheckDB - persist, read, detect overlap and cancel an appointment")
    @SuppressWarnings("unchecked")
    void bookingAndCancellationArePersistedAndRolledBack() {
        SlotService slotLock = mock(SlotService.class);
        AppointmentService service = new AppointmentService(mock(UserServiceClient.class), appointments,
                medicalServices, slotLock, new EntityDTOMapper(), mock(EventBus.class));
        SlotService durationService = new SlotService(appointments, medicalServices, mock(RedisTemplate.class),
                mock(DoctorServiceClient.class));
        List<UUID> serviceIds = List.of(storeService(30), storeService(45));
        UUID doctorId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        ZonedDateTime start = ZonedDateTime.parse("2030-03-15T09:00:00+07:00");
        ZonedDateTime end = start.plusMinutes(75);
        AppointmentRequestDTO request = AppointmentRequestDTO.builder()
                .doctorId(doctorId).patientId(patientId).appointmentStartTime(start)
                .MedicalServiceIds(serviceIds).build();
        when(slotLock.validateAndUnlockSlot(doctorId, patientId, start, end)).thenReturn(true);

        database.clear();
        assertEquals(75, durationService.calculateServiceDurationMinutes(serviceIds));
        UUID appointmentId = service.createAppointment(request).getId();
        Appointment created = reload(appointmentId);
        assertEquals(AppointmentStatus.CONFIRMED, created.getStatus());
        assertEquals(patientId, created.getPatientId());
        assertEquals(doctorId, created.getDoctorId());
        assertEquals(end.toInstant(), created.getAppointmentEndTime().toInstant());
        assertEquals(2, created.getMedicalServices().size());
        assertTrue(assertThrows(RuntimeException.class, () -> service.createAppointment(request))
                .getMessage().contains("not available"));
        assertEquals(1, appointments.count());

        service.deleteAppointment(appointmentId);
        assertEquals(AppointmentStatus.CANCELLED, reload(appointmentId).getStatus());
    }

    @AfterTransaction
    void confirmRollbackRestoredEmptyDatabase() {
        for (String table : List.of("appointment_medical_service", "appointment", "medical_service")) {
            assertEquals(0L, sql.queryForObject("select count(*) from " + table, Long.class),
                    () -> "Rollback must remove test rows from " + table);
        }
    }

    private Appointment reload(UUID appointmentId) {
        database.flush();
        database.clear();
        return appointments.findById(appointmentId).orElseThrow();
    }

    private UUID storeService(int minutes) {
        MedicalService service = MedicalService.builder().serviceName("Test service " + minutes)
                .serviceType("CONSULTATION").serviceTime(minutes).price(1000F)
                .status(MedicalServiceStatus.ACTIVE).build();
        return medicalServices.saveAndFlush(service).getId();
    }
}
