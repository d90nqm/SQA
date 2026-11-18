package com.main_project.inventory_service.service;

import com.do_an.common.command.MarkPrescripAsReleaseCommand;
import com.do_an.common.command.ReserveMedicineCommand;
import com.do_an.common.command.ReturnMedicineReservationCommand;
import com.do_an.common.event.MedicineReservationReleaseEvent;
import com.do_an.common.event.MedicineReservationReturnEvent;
import com.do_an.common.event.MedicineReservedEvent;
import com.do_an.common.model.MedicineItem;
import com.main_project.inventory_service.aggregate.InventoryAggregate;
import com.main_project.inventory_service.aggregate.InventoryCommandHandler;
import com.main_project.inventory_service.aggregate.InventoryEventHandler;
import com.main_project.inventory_service.client.InvoiceClient;
import com.main_project.inventory_service.client.PatientClient;
import com.main_project.inventory_service.client.UserClient;
import com.main_project.inventory_service.dto.InventoryRestoreRequest;
import com.main_project.inventory_service.dto.ManualExportRequest;
import com.main_project.inventory_service.dto.ManualExportResponse;
import com.main_project.inventory_service.entity.DispenseItem;
import com.main_project.inventory_service.entity.InventoryLot;
import com.main_project.inventory_service.entity.Medicine;
import com.main_project.inventory_service.entity.Pharmacist;
import com.main_project.inventory_service.entity.StockLedger;
import com.main_project.inventory_service.repository.*;
import jakarta.persistence.EntityManager;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.modelling.command.Repository;
import org.axonframework.test.aggregate.AggregateTestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.transaction.AfterTransaction;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** IS worksheet tests with a private H2 database and Axon command and event fixtures. */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = InventorySagaTest.JpaConfiguration.class)
class InventorySagaTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = InventoryLot.class)
    @EnableJpaRepositories(basePackageClasses = InventoryLotRepository.class)
    static class JpaConfiguration {}

    @Autowired EntityManager database;
    @Autowired JdbcTemplate sql;
    @Autowired InventoryLotRepository lots;
    @Autowired MedicineRepository medicines;
    @Autowired DispenseItemRepository items;
    @Autowired StockLedgerRepository ledgers;
    @Autowired PharmacistRepository pharmacists;
    @Autowired DispenseOrderRepository orders;

    InventoryLotService lotService;
    DispenseOrderService orderService;
    InventoryCommandHandler reservationCommands;
    InventoryEventHandler reservationEvents;
    Repository<InventoryAggregate> aggregates;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void configureServices() {
        // The mock repository detects unexpected aggregate creation in negative cases.
        // Successful cases use the in-memory Axon fixture with the same production handler.
        EventBus eventBus = mock(EventBus.class);
        aggregates = mock(Repository.class);
        lotService = new InventoryLotService(lots, medicines, items, ledgers, pharmacists, orders);
        orderService = new DispenseOrderService(orders, pharmacists, mock(InvoiceClient.class),
                mock(PatientClient.class), mock(UserClient.class), eventBus);
        reservationCommands = new InventoryCommandHandler(new MedicineService(medicines),
                lotService, orderService, aggregates);
        reservationEvents = new InventoryEventHandler(orderService,
                new DispenseItemService(items, lots, orders), lotService, eventBus);
    }

    @AfterTransaction
    void checkDatabaseReturnedToBaseline() {
        // Spring runs this check outside the completed test transaction, including after failed assertions.
        for (String table : List.of("stock_ledger", "dispense_item", "dispense_order",
                "inventory_lot", "medicine", "pharmacist")) {
            assertEquals(0L, sql.queryForObject("select count(*) from " + table, Long.class),
                    () -> "Rollback must remove test rows from " + table);
        }
    }

    // Inventory lots and stock operations (TC-UT-SAGA-01 to TC-UT-SAGA-11).
    // Test Case ID: TC-UT-SAGA-01. Reserve 20 units from a stock lot of 100.
    @Test
    @DisplayName("TC-UT-SAGA-01 — Reserve 20 units from a stock lot of 100")
    void checkStock_sufficientQuantity() throws Exception {
        InventoryLot lot = stockLot("MED-001", 100, LocalDate.now().plusDays(90));
        assertReservationDeducts(lot, 20, 80);
    }

    // Test Case ID: TC-UT-SAGA-02. Reserve all 50 available units and keep the empty lot.
    @Test
    @DisplayName("TC-UT-SAGA-02 — Reserve all 50 available units and keep the empty lot")
    void checkStock_exactAvailableQuantity() throws Exception {
        InventoryLot lot = stockLot("MED-002", 50, LocalDate.now().plusDays(90));
        assertReservationDeducts(lot, 50, 0);
    }

    // Test Case ID: TC-UT-SAGA-03. Sum two valid lots of 50 and 30.
    @Test
    @DisplayName("TC-UT-SAGA-03 — Sum two valid lots of 50 and 30")
    void getTotalAvailableQuantity_sumsValidLots() {
        InventoryLot firstLot = stockLot("MED-001", 50, LocalDate.now().plusDays(30));
        UUID medicineId = firstLot.getMedicine().getId();
        stockLot(firstLot.getMedicine(), 30, LocalDate.now().plusDays(60));
        flushAndClear();
        assertEquals(80, lotService.getTotalAvailableQuantity(medicineId));
    }

    // Test Case ID: TC-UT-SAGA-04. Exclude the expired lot of 100 units.
    @Test
    @DisplayName("TC-UT-SAGA-04 — Exclude the expired lot of 100 units")
    void getTotalAvailableQuantity_excludesExpiredLots() {
        InventoryLot expired = stockLot("MED-001", 100, LocalDate.now().minusDays(1));
        UUID medicineId = expired.getMedicine().getId();
        stockLot(expired.getMedicine(), 40, LocalDate.now().plusDays(30));
        flushAndClear();
        assertEquals(40, lotService.getTotalAvailableQuantity(medicineId));
    }

    // Test Case ID: TC-UT-SAGA-05. Export 15 damaged units and record the export.
    @Test
    @DisplayName("TC-UT-SAGA-05 — Export 15 damaged units and record the export")
    void exportStock_damagedLotDecrementsAndWritesLedger() {
        InventoryLot lot = stockLot("MED-001", 50, LocalDate.now().plusDays(30));
        ManualExportResponse exported = export(lot.getId(), 15, "Damaged");

        assertEquals(35, persistedQuantity(lot.getId()));
        StockLedger ledger = ledgers.findByReferenceIdAndType(lot.getId(), "OUT").stream()
                .findFirst().orElseThrow();
        assertEquals(15, ledger.getQuantity());
        assertEquals(lot.getId(), ledger.getInventoryLot().getId());
        assertEquals(ledger.getId(), exported.getId());
        assertEquals("Damaged", exported.getReason());
    }

    // Test Case ID: TC-UT-SAGA-06. Reject a zero-quantity reservation.
    @Test
    @DisplayName("TC-UT-SAGA-06 — Reject a zero-quantity reservation")
    void checkStock_rejectsZeroQuantity() {
        InventoryLot lot = stockLot("MED-001", 50, LocalDate.now().plusDays(30));
        var zeroQuantity = commandFor(reservation(lot.getMedicine().getId(), 0));
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> reservationCommands.handle(zeroQuantity)),
                () -> assertEquals(50, persistedQuantity(lot.getId())),
                () -> verifyNoInteractions(aggregates));
    }

    // Test Case ID: TC-UT-SAGA-07. Reject a null medicine ID.
    @Test
    @DisplayName("TC-UT-SAGA-07 — Reject a null medicine ID")
    void getTotalAvailableQuantity_rejectsNullId() {
        stockLot("MED-001", 50, LocalDate.now().plusDays(30));
        assertThrows(IllegalArgumentException.class, () -> lotService.getTotalAvailableQuantity(null));
    }

    // Test Case ID: TC-UT-SAGA-08. Export all 20 units and persist the empty lot.
    @Test
    @DisplayName("TC-UT-SAGA-08 — Export all 20 units and persist the empty lot")
    void exportStock_exactDepletionKeepsLot() {
        InventoryLot lot = stockLot("MED-001", 20, LocalDate.now().plusDays(30));
        export(lot.getId(), 20, "Damaged");

        assertEquals(0, persistedQuantity(lot.getId()));
        assertEquals(20, ledgers.findByReferenceIdAndType(lot.getId(), "OUT").get(0).getQuantity());
    }

    // Test Case ID: TC-UT-SAGA-09. Restore 5 then 3 units to a lot of 10.
    @Test
    @DisplayName("TC-UT-SAGA-09 — Restore 5 then 3 units to a lot of 10")
    void restoreQuantityToLot_accumulatesTwoAdjustments() {
        InventoryLot lot = stockLot("MED-001", 10, LocalDate.now().plusDays(30));
        restore(lot.getId(), 5);
        assertEquals(15, persistedQuantity(lot.getId()));
        restore(lot.getId(), 3);
        assertEquals(18, persistedQuantity(lot.getId()));
    }

    // Test Case ID: TC-UT-SAGA-10. Sum only the queried medicine's 30 units.
    @Test
    @DisplayName("TC-UT-SAGA-10 — Sum only the queried medicine's 30 units")
    void getTotalAvailableQuantity_filtersByMedicine() {
        InventoryLot requested = stockLot("Medicine A", 30, LocalDate.now().plusDays(30));
        stockLot("Medicine B", 50, LocalDate.now().plusDays(30));
        flushAndClear();
        assertEquals(30, lotService.getTotalAvailableQuantity(requested.getMedicine().getId()));
    }

    // Test Case ID: TC-UT-SAGA-11. Include a lot of 50 units with no expiry date.
    @Test
    @DisplayName("TC-UT-SAGA-11 — Include a lot of 50 units with no expiry date")
    void getTotalAvailableQuantity_includesNullExpiry() {
        InventoryLot lot = stockLot("MED-001", 50, null);
        flushAndClear();
        assertEquals(50, lotService.getTotalAvailableQuantity(lot.getMedicine().getId()));
    }

    // Dispense orders and compensation (TC-UT-SAGA-12 to TC-UT-SAGA-19).
    // Test Case ID: TC-UT-SAGA-12. Check the status after the successful reservation and release.
    @Test
    @DisplayName("TC-UT-SAGA-12 — Completed prescription order")
    void create_successfulReservationHasCompletedStatus() throws Exception {
        InventoryLot lot = stockLot("MED-001", 100, LocalDate.now().plusDays(90));
        MedicineReservedEvent request = reservation(lot.getMedicine().getId(), 20);
        reserveAndProject(request);
        releaseAndProject(request);

        assertEquals(80, persistedQuantity(lot.getId()));
        // COMPLETED is the workbook contract. The product currently persists RELEASED.
        assertEquals("COMPLETED", orderService.getById(request.getDispenseOrderId()).getStatus());
    }

    // Test Case ID: TC-UT-SAGA-13. A null medicine ID must fail before any order is written.
    @Test
    @DisplayName("TC-UT-SAGA-13 — Reject a null medicine ID")
    void create_rejectsNullMedicineId() {
        MedicineReservedEvent request = reservation(null, 10);
        assertAll(
                // Call the guard directly to check the exception type from the product code.
                () -> assertThrows(IllegalArgumentException.class,
                        () -> reservationCommands.handle(commandFor(request))),
                () -> assertFalse(orders.existsById(request.getDispenseOrderId())),
                () -> verifyNoInteractions(aggregates));
    }

    // Test Case ID: TC-UT-SAGA-14. Reload the order by ID and check every mapped field.
    @Test
    @DisplayName("TC-UT-SAGA-14 — Retrieve the reservation by ID")
    void create_retrievesAllOrderFieldsFromDatabase() throws Exception {
        InventoryLot lot = stockLot("MED-001", 100, LocalDate.now().plusDays(90));
        MedicineReservedEvent request = reservation(lot.getMedicine().getId(), 20);
        reserveAndProject(request);

        checkPersistedOrder(request, lot);
    }

    // Test Case ID: TC-UT-SAGA-15. A lot with today's expiry must be accepted per this sheet.
    @Test
    @DisplayName("TC-UT-SAGA-15 — Include a lot expiring today")
    void create_allocatesFromLotExpiringToday() throws Exception {
        InventoryLot expiringToday = stockLot("MED-001", 50, LocalDate.now());
        MedicineReservedEvent request = reservation(expiringToday.getMedicine().getId(), 10);
        reserveAndProject(request);

        assertEquals(40, persistedQuantity(expiringToday.getId()));
        checkPersistedOrder(request, expiringToday);
    }

    // Test Case ID: TC-UT-SAGA-16. Simulate the Saga return after a downstream failure.
    @Test
    @DisplayName("TC-UT-SAGA-16 — Restore the full stock quantity after failure")
    void create_downstreamFailureRestores25Units() throws Exception {
        InventoryLot lot = stockLot("MED-001", 100, LocalDate.now().plusDays(90));
        MedicineReservedEvent request = reservation(lot.getMedicine().getId(), 25);
        reserveAndProject(request);
        assertEquals(75, persistedQuantity(lot.getId()));

        returnAndProject(request);

        assertEquals(100, persistedQuantity(lot.getId()));
        assertCompensationRecorded(request, lot.getId(), 25);
    }

    // Test Case ID: TC-UT-SAGA-17. Each compensation must independently restore its own quantity.
    @Test
    @DisplayName("TC-UT-SAGA-17 — Restore quantities from two separate orders")
    void create_compensatesTwoOrdersAgainstTheSameLot() throws Exception {
        InventoryLot lot = stockLot("MED-001", 100, LocalDate.now().plusDays(90));
        UUID medicineId = lot.getMedicine().getId();
        MedicineReservedEvent firstOrder = reservation(medicineId, 20);
        MedicineReservedEvent secondOrder = reservation(medicineId, 15);
        reserveAndProject(firstOrder);
        reserveAndProject(secondOrder);
        assertEquals(65, persistedQuantity(lot.getId()));

        returnAndProject(firstOrder);
        assertEquals(85, persistedQuantity(lot.getId()));
        returnAndProject(secondOrder);
        assertEquals(100, persistedQuantity(lot.getId()));
        assertTrue(items.findAllByDispenseOrderId(firstOrder.getDispenseOrderId()).isEmpty());
        assertTrue(items.findAllByDispenseOrderId(secondOrder.getDispenseOrderId()).isEmpty());
        assertCompensationRecorded(firstOrder, lot.getId(), 20);
        assertCompensationRecorded(secondOrder, lot.getId(), 15);
    }

    // Test Case ID: TC-UT-SAGA-18. Verify both stock restoration and the status specified in Excel.
    @Test
    @DisplayName("TC-UT-SAGA-18 — Compensation status and restored stock")
    void create_compensationPersistsCompensatedStatus() throws Exception {
        InventoryLot lot = stockLot("MED-001", 100, LocalDate.now().plusDays(90));
        MedicineReservedEvent request = reservation(lot.getMedicine().getId(), 25);
        reserveAndProject(request);
        assertEquals(75, persistedQuantity(lot.getId()));

        returnAndProject(request);

        assertEquals(100, persistedQuantity(lot.getId()));
        assertCompensationRecorded(request, lot.getId(), 25);
        // The workbook specifies COMPENSATED. Report a mismatch if the code saves CANCELLED.
        assertEquals("COMPENSATED", orderService.getById(request.getDispenseOrderId()).getStatus());
    }

    // Test Case ID: TC-UT-SAGA-19. Check the rows written for the order and its allocated item.
    @Test
    @DisplayName("TC-UT-SAGA-19 — Verify order and item after insert")
    void create_persistsConsistentOrderAndAllocatedItem() throws Exception {
        InventoryLot lot = stockLot("MED-001", 100, LocalDate.now().plusDays(90));
        MedicineReservedEvent request = reservation(lot.getMedicine().getId(), 20);
        reserveAndProject(request);

        checkPersistedOrder(request, lot);
        assertEquals(80, persistedQuantity(lot.getId()));
        assertEquals(20, ledgers.findByInventoryLotId(lot.getId()).get(0).getQuantity());
    }

    // Shared test data and commands.

    InventoryLot stockLot(String medicineName, int quantity, LocalDate expiryDate) {
        Medicine medicine = new Medicine();
        medicine.setName(medicineName);
        medicine.setUnit("Viên");
        medicine.setSalePrice(1000);
        return stockLot(medicines.save(medicine), quantity, expiryDate);
    }

    InventoryLot stockLot(Medicine medicine, int quantity, LocalDate expiryDate) {
        InventoryLot lot = new InventoryLot();
        lot.setLotNo("TEST-" + UUID.randomUUID());
        lot.setMedicine(medicine);
        lot.setQuantityOnHand(quantity);
        lot.setExpireDate(expiryDate);
        return lots.saveAndFlush(lot);
    }

    int persistedQuantity(UUID lotId) {
        flushAndClear();
        return lots.findById(lotId).orElseThrow().getQuantityOnHand();
    }

    void flushAndClear() {
        // CheckDB: force SQL before clearing the persistence context, then fetch a fresh row.
        database.flush();
        database.clear();
    }

    MedicineReservedEvent reservation(UUID medicineId, int quantity) {
        MedicineItem item = new MedicineItem();
        item.setMedicineId(medicineId);
        item.setName("MED-001");
        item.setQuantity(quantity);
        item.setUnitPrice(1000);
        item.setDosage("Test dosage");
        item.setFrequency("Test frequency");
        item.setDuration("Test duration");
        item.setInstruction("Test directions");
        return new MedicineReservedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), List.of(item));
    }

    ReserveMedicineCommand commandFor(MedicineReservedEvent event) {
        return new ReserveMedicineCommand(event.getDispenseOrderId(), event.getPrescriptionId(),
                event.getDoctorId(), event.getMedicalHistoryId(), event.getItems());
    }

    void reserveAndProject(MedicineReservedEvent event) {
        // Verify the real command publishes the expected event before applying its database projection.
        inventoryAggregate().givenNoPriorActivity().when(commandFor(event))
                .expectSuccessfulHandlerExecution().expectEvents(event);
        reservationEvents.on(event);
    }

    void releaseAndProject(MedicineReservedEvent reservation) {
        var release = new MedicineReservationReleaseEvent(reservation.getDispenseOrderId());
        inventoryAggregate().given(reservation).when(new MarkPrescripAsReleaseCommand(reservation.getDispenseOrderId()))
                .expectSuccessfulHandlerExecution().expectEvents(release);
        reservationEvents.on(release);
    }

    void returnAndProject(MedicineReservedEvent reservation) {
        // Model the return command sent after a downstream failure, using the real inventory aggregate.
        UUID orderId = reservation.getDispenseOrderId();
        UUID prescriptionId = reservation.getPrescriptionId();
        var returned = new MedicineReservationReturnEvent(prescriptionId, orderId);
        inventoryAggregate().given(reservation).when(new ReturnMedicineReservationCommand(orderId, prescriptionId))
                .expectSuccessfulHandlerExecution().expectEvents(returned);
        reservationEvents.on(returned);
    }

    private AggregateTestFixture<InventoryAggregate> inventoryAggregate() {
        var fixture = new AggregateTestFixture<>(InventoryAggregate.class);
        fixture.registerAnnotatedCommandHandler(new InventoryCommandHandler(new MedicineService(medicines),
                lotService, orderService, fixture.getRepository()));
        return fixture;
    }

    private void assertReservationDeducts(InventoryLot lot, int requested, int remaining) throws Exception {
        // The workbook calls this checkStock(). The service splits it into a guard and a projection.
        var reservation = reservation(lot.getMedicine().getId(), requested);
        reserveAndProject(reservation);
        assertEquals(remaining, persistedQuantity(lot.getId()));
        assertEquals(requested, items.findAllByDispenseOrderId(reservation.getDispenseOrderId())
                .get(0).getQuantity());
    }

    private ManualExportResponse export(UUID lotId, int quantity, String reason) {
        Pharmacist pharmacist = new Pharmacist();
        pharmacist.setUserId(UUID.randomUUID());
        pharmacists.save(pharmacist);
        return lotService.exportStock(new ManualExportRequest(lotId, quantity, reason, null,
                pharmacist.getUserId()));
    }

    private void restore(UUID lotId, int quantity) {
        lotService.restoreQuantityToLot(InventoryRestoreRequest.builder()
                .inventoryLotId(lotId).quantity(quantity).build());
    }

    private void assertCompensationRecorded(MedicineReservedEvent order, UUID lotId, int quantity) {
        assertTrue(items.findAllByDispenseOrderId(order.getDispenseOrderId()).isEmpty());
        // Find the original OUT record's item reference so two returns from the same lot
        // cannot both be satisfied by a single IN record.
        var outEntries = ledgers.findByInventoryLotId(lotId).stream()
                .filter(entry -> "AUTO_DISPENSE".equals(entry.getReferenceType())
                        && entry.getQuantity() == quantity)
                .toList();
        assertEquals(1, outEntries.size());
        var restored = ledgers.findByReferenceIdAndType(outEntries.get(0).getReferenceId(), "IN");
        assertEquals(1, restored.size());
        assertEquals(quantity, restored.get(0).getQuantity());
        assertEquals("AUTO_ROLLBACK", restored.get(0).getReferenceType());
    }

    private void checkPersistedOrder(MedicineReservedEvent request, InventoryLot lot) {
        flushAndClear();
        var order = orderService.getById(request.getDispenseOrderId());
        assertEquals(request.getDispenseOrderId(), order.getId());
        assertEquals(request.getPrescriptionId(), order.getPrescription());
        // The inventory schema stores the medical-history link. Patient data belongs to patient-service.
        assertEquals(request.getMedicalHistoryId(), order.getMedicalHistoryId());
        assertEquals(request.getDoctorId(), order.getDoctorId());
        assertEquals("RESERVED", order.getStatus());
        assertNotNull(order.getCreateAt());
        assertNotNull(order.getUpdateAt());
        assertNull(order.getPharmacistId());

        var allocated = items.findAllByDispenseOrderId(order.getId());
        assertEquals(1, allocated.size());
        DispenseItem actual = allocated.get(0);
        MedicineItem expected = request.getItems().get(0);
        assertEquals(lot.getId(), actual.getInventoryLot().getId());
        assertEquals(expected.getMedicineId(), actual.getInventoryLot().getMedicine().getId());
        assertEquals(expected.getQuantity(), actual.getQuantity());
        assertEquals(expected.getUnitPrice(), actual.getPriceAtDispense());
        assertEquals(expected.getDosage(), actual.getDosage());
        assertEquals(expected.getDuration(), actual.getDuration());
        assertEquals(expected.getFrequency(), actual.getFrequency());
        assertEquals(expected.getInstruction(), actual.getUsageInstructions());
    }
}
